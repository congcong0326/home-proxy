# 生产环境多 Worker 升级运行手册

本手册用于将现有的单 Worker 部署升级到支持每个 Worker 独立令牌的控制端/Worker 2.0 版本。本文假设控制端主机和每台 Worker 主机都已将仓库检出到 `$ROOT`。

在新 Worker 上线并成功应用有效配置前，不要将已启用的入站配置分配给它。重新分配后，旧 Worker 在下一次拉取配置时将不再获得该入站配置。

## 1. 定义升级参数

在控制端主机上运行以下命令。请使用不可变的发布标签或提交，不要使用包含未提交修改的工作区。

```bash
export ROOT=/opt/home-proxy
export RELEASE=<immutable-release-tag-or-commit>
export CONTROL_ENV=/opt/home-proxy/env/control.env
export BACKUP_DIR=/opt/home-proxy/backups/$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"
cd "$ROOT"
git status --short
git rev-parse HEAD
```

本次发布必须包含 `V20__create_worker_metric_samples.sql`、
`V21__create_proxy_worker_registry.sql` 和
`V22__bind_inbounds_tasks_and_status_to_worker.sql`。

更改控制端之前，记录每个正在使用的入站配置及其计划迁移到的目标 Worker。数据库迁移会先将现有入站配置和未消费任务分配给 `default`。

## 2. 备份并预检数据库

使用生产环境的备份机制备份 MySQL、ClickHouse 数据和 `data/tls`。同时使用不可变的本地标签保留当前应用镜像。

```bash
mkdir -p "$BACKUP_DIR/tls"
cp -a data/tls/. "$BACKUP_DIR/tls/"
docker image tag home-proxy-control-manager:latest "home-proxy-control-manager:pre-worker-v2-$(date +%Y%m%d-%H%M%S)"
docker image tag home-proxy-worker:latest "home-proxy-worker:pre-worker-v2-$(date +%Y%m%d-%H%M%S)"
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events "$MYSQL_DATABASE"' \
  > "$BACKUP_DIR/tpproxy.sql"
```

部署前检查 Flyway 历史记录。如果 V20 已存在，其脚本名称和校验和必须与预期结果一致。不要通过运行 `flyway repair` 绕过不匹配问题。

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT version, script, checksum, success
FROM flyway_schema_history
WHERE version >= 19
ORDER BY installed_rank;

SELECT worker_id, COUNT(*) AS status_rows
FROM worker_status
GROUP BY worker_id;

SELECT COUNT(*) AS pending_tasks
FROM worker_tasks
WHERE consumed_at IS NULL;

SELECT id, username, ip_address
FROM users
WHERE username = '\''Anonymous user'\'' OR ip_address = '\''127.0.0.1'\'';"'
```

预期的 Flyway 状态如下：

- V20 不存在，或者其脚本为 `V20__create_worker_metric_samples.sql`，校验和为 `-468473104`。
- 首次尝试升级前，V21 和 V22 均不存在。
- 所有查询到的记录均为 `success=1`。

如出现其他结果，请停止发布。V21/V22 中的 MySQL DDL 不具备自动回滚边界；在更改生产环境前，应先恢复或修复一个预发布环境的数据库副本。

## 3. 准备 TLS 和控制端环境

新的 Worker Compose 配置连接到 `https://proxy-manager.internal:18443`。控制端证书的 DNS 使用者可选名称中必须包含 `proxy-manager.internal`。只向 Worker 分发 `control-manager.crt`，绝不能分发 P12 文件或密钥库密码。

在发布控制端时，将以下临时兼容配置添加到 `$CONTROL_ENV`：

```dotenv
WORKER_AUTH_LEGACY_ENABLED=true
```

`legacy` 模式只会将未认证的旧 Worker 认证为 `default`。它不会保留各个历史 Worker 的归属关系，并且必须在所有 Worker 都开始使用令牌后关闭。

当前控制端 Compose 配置会直接启用 HTTPS。切换控制端后，仍使用旧 HTTP 端点的 Worker 将无法再接收新配置，但会继续使用当前已应用的配置提供服务，直到它们重启。尽量缩短控制端与 Worker 的切换间隔；在旧 Worker 的 HTTPS、证书和令牌文件准备就绪前，不要重启它。

更改容器前，验证配置插值结果：

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml config --quiet
```

## 4. 升级控制端

构建包含前端静态资源的完整发布版本，然后只启动控制端服务。

```bash
make package
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml up -d --build control-manager
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml ps
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml logs --tail=200 control-manager
```

等待 Flyway 报告 V21 和 V22 已应用，并确认 `ControlManagerApplication` 已启动。然后验证迁移后的数据：

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT worker_id, enabled, token_hash IS NOT NULL AS token_present
FROM proxy_worker ORDER BY worker_id;

SELECT worker_id, COUNT(*) AS inbounds
FROM inbound_configs GROUP BY worker_id;

SELECT worker_id, COUNT(*) AS pending_tasks
FROM worker_tasks WHERE consumed_at IS NULL GROUP BY worker_id;

SELECT COUNT(*) AS null_default_references
FROM inbound_configs
WHERE fallback_route_id IS NULL OR anonymous_user_id IS NULL;"'
```

所有现有入站配置和未完成任务的 `worker_id` 都应为 `default`。`null_default_references` 必须为零。

## 5. 创建 Worker 凭据和环境文件

使用控制端界面为 `default` 创建令牌，然后为其他每个目标 Worker 创建令牌。令牌只会显示一次。不要将令牌写入 Git、Shell 历史记录或 Compose 环境文件。

在每台 Worker 主机上创建对应的令牌文件和本地环境文件。启动 Worker 前替换所有占位值。

```bash
export ROOT=/opt/home-proxy
export WORKER_ID=shanghai-01
export CONTROL_MANAGER_HOST_IP=192.168.1.10
export WORKER_ENV=/opt/home-proxy/env/workers/${WORKER_ID}.env
export TOKEN_FILE=/opt/home-proxy/secrets/${WORKER_ID}.token

install -d -m 700 "$(dirname "$TOKEN_FILE")" "$(dirname "$WORKER_ENV")" "$ROOT/data/tls"
install -m 600 /dev/null "$TOKEN_FILE"
# 使用能够保持 0600 权限的编辑器，将仅显示一次的令牌写入 $TOKEN_FILE。
cp /secure-transfer/control-manager.crt "$ROOT/data/tls/control-manager.crt"

cat > "$WORKER_ENV" <<EOF
WORKER_ID=${WORKER_ID}
CONTROL_MANAGER_HOST_IP=${CONTROL_MANAGER_HOST_IP}
CONTROL_HTTPS_HOST_PORT=18443
WORKER_TOKEN_HOST_FILE=${TOKEN_FILE}
EOF
chmod 600 "$WORKER_ENV"
```

对于现有的单 Worker，请使用 `WORKER_ID=default`。此步骤中不要将正在使用的入站配置迁移到其他 Worker。

## 6. 逐台升级 Worker

在每台 Worker 主机上，先验证其 Compose 配置，再使用本地环境文件构建并启动 Worker。

```bash
cd "$ROOT"
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml config --quiet
make docker-up-worker WORKER_ENV_FILE="$WORKER_ENV"
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml ps
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml logs --tail=200 proxy-worker
```

在控制端确认该 Worker 满足以下全部条件前，不要继续升级下一台：

- `last_seen_at` 为最新时间。
- `apply_success=1`。
- `apply_error` 为空。
- `applied_config_hash` 已填充。

使用控制端界面或查询 MySQL：

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT worker_id, last_seen_at, applied_config_hash, apply_success, apply_error, applied_at
FROM worker_status ORDER BY worker_id;"'
```

Worker 的 `last-known-good-config.json` 数据卷可以让升级后的 Worker 在重启时，即使控制端尚不可访问，也能先恢复最近一次成功应用的配置。

## 7. 迁移入站配置并验证流量

目标 Worker 健康后，在界面中仅将计划迁移的入站配置分配给该 Worker。等待目标 Worker 应用新配置后，再迁移下一个入站配置。分配前，确认目标主机网络中每个需要监听的端口均未被占用。

对每个已迁移的入站配置，确认以下事项：

1. 旧 Worker 在下一次拉取配置后不再报告该入站配置。
2. 目标 Worker 报告 `apply_success=1`，并生成了新的配置哈希。
3. 目标主机正在监听预期端口。
4. 通过目标 Worker 发起的真实代理连接成功。

不要在同一台主机上运行 `listen_ip:port` 绑定范围重叠的两个 Worker。由于使用了 `network_mode: host`，即使数据库允许不同 Worker 使用相同端口，操作系统层面仍会发生端口冲突。

## 8. 完成认证切换

所有 Worker 都使用各自的唯一令牌并保持健康后，从 `$CONTROL_ENV` 中删除临时兼容配置，或将其设为 `false`：

```dotenv
WORKER_AUTH_LEGACY_ENABLED=false
```

重新部署控制端，并验证未认证的 Worker 请求返回 `401`，使用令牌认证的 Worker 仍保持在线。

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml up -d --build control-manager
curl --insecure --silent --output /dev/null --write-out '%{http_code}\n' \
  https://127.0.0.1:18443/api/config/aggregate
```

最后一条命令必须返回 `401`。

## 9. 回滚边界

在应用 V21/V22 前，可以通过重新部署镜像进行回滚。应用后，不要假设旧版 control-manager 镜像能够验证更新后的 Flyway 历史记录。应按照备份/副本恢复方案同时回滚应用和数据库；如果已经迁移流量，还必须先将入站配置的归属关系恢复到之前的拓扑。
