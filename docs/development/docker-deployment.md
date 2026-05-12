# Docker Deployment

本项目的 Docker 镜像只负责运行产物。先在本机完成 Maven/npm 构建，再把生成好的 JAR 复制进运行时镜像。

## 构建产物与镜像

```bash
make docker-build
```

`make package` 会构建前端、同步静态资源到 `control-manager`，再打包后端模块。不要直接用 Dockerfile 跑 Maven 或 npm。

常用目标：

```bash
make docker-build          # 打包并构建 control-manager 和 worker 镜像
make docker-build-control  # 只打包并构建 control-manager 镜像
make docker-build-worker   # 只打包并构建 worker 镜像
make docker-save           # 打包、构建并导出应用镜像到 home-proxy-images.tar
make docker-save-offline   # 导出应用镜像以及 MySQL/ClickHouse 镜像
```

## 启动控制面

```bash
make docker-up-control
```

默认服务：

- `control-manager`：宿主机 `18081` -> 容器 `8081`
- `mysql`：仅 Docker 网络内可见
- `clickhouse`：仅 Docker 网络内可见

开发默认密码和 JWT secret 已写在 compose 默认值里，生产部署必须用 `.env` 或宿主机环境变量覆盖：

```bash
CONTROL_HOST_PORT=18081
MYSQL_ROOT_PASSWORD=change_me
CLICKHOUSE_PASSWORD=change_me
ADMIN_AUTH_JWT_SECRET=change_me_to_a_long_random_secret
```

磁盘监控已改为宿主机主动推送。进入控制面“磁盘监控”页面后，系统会自动生成并明文展示上报 Token；将该值写入宿主机的 `scripts/disk-monitor/disk-monitor.env` 中的 `DISK_PUSH_TOKEN`，再通过 cron 定时调用 `push-smartctl.sh`。`DISK_MONITOR_PUSH_TOKEN` 仅作为兼容旧部署的可选初始 Token；如果没有设置，页面会随机生成并持久化。

如需清空控制面数据库和日志卷，重新进入首次创建管理员流程：

```bash
make CONFIRM=1 docker-reset-control
```

该命令会执行 `docker compose down -v`，会删除 MySQL 和 ClickHouse 数据卷。

## 启动 worker

```bash
make docker-up-worker
```

worker 使用 `network_mode: host`，因此控制面下发的入站端口会直接监听在宿主机网络栈，不需要在 compose 里重复配置 `ports`。

默认 worker 连接本机控制面：

```bash
CONTROL_MANAGER_URL=http://127.0.0.1:18081
```

如果控制面在其他机器：

```bash
CONTROL_MANAGER_URL=http://<control-manager-host-ip>:18081 make docker-up-worker
```

使用 host network 时，新增入站端口前需要确认宿主机没有同端口进程占用。Linux 原生 Docker 对该模式支持最好；Docker Desktop 的 host network 行为可能不同。

worker 配置优先级为：

1. 环境变量或 JVM system properties，例如 `CONTROL_MANAGER_URL`、`CONTROL_HOST`、`CONTROL_PORT`。
2. `PROXY_WORKER_CONFIG` 指向的外部 `proxy-worker.properties`。
3. JAR 内置的 `proxy-worker.properties`。

如需挂载外部配置文件：

```yaml
environment:
  PROXY_WORKER_CONFIG: /app/config/proxy-worker.properties
volumes:
  - ../../config/proxy-worker.properties:/app/config/proxy-worker.properties:ro
```

## GeoIP 数据库

私有容器化部署推荐在构建镜像前放置 MaxMind `GeoLite2-City.mmdb`：

```bash
mkdir -p data/geoip
cp /path/to/GeoLite2-City.mmdb data/geoip/GeoLite2-City.mmdb
make docker-build
```

`data/geoip/*.mmdb` 已被 Git 忽略。`control-manager` 和 `proxy-worker` 镜像都会把该目录复制到 `/app/data/geoip/`：worker 用它做透明代理 IP 路由判断，control-manager 用它补全访问日志中的国家/城市。

如需运行时挂载覆盖镜像内置数据库：

```yaml
volumes:
  - ../../data/geoip/GeoLite2-City.mmdb:/app/data/geoip/GeoLite2-City.mmdb:ro
```

也可以通过 `GEOIP_MMDB_PATH` 指向其他挂载路径，或通过 `GEOIP_DATA_DIR` 指向包含 `GeoLite2-City.mmdb` 的目录。未找到 mmdb 时，服务仍可启动，但 GeoIP 路由和日志城市解析会降级。

TLS 证书可挂载到容器内，并通过环境变量指定：

```bash
TLS_CERT_FILE=/etc/nas-proxy/certs/s-proxy-ca.crt
TLS_KEY_FILE=/etc/nas-proxy/certs/s-proxy-ca.key
```
