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
make docker-save-offline   # 同 docker-save（控制面已不依赖第三方镜像）
```

## 启动控制面

```bash
make docker-up-control
```

控制面只有一个容器，配置直接写在 `deploy/docker/docker-compose.control.yml` 里，不使用 `.env`。需要按部署环境修改的只有：

- `ports`：默认 `0.0.0.0:18443:8443`；可改成 `<内网IP>:18443:8443` 只监听内网。请在宿主机防火墙或云安全组中只允许可信 LAN/VPN 网段访问该端口。
- `volumes`：配置库 `/data/home-proxy.db`、日志库 `/data/telemetry.db` 与备份都在 `/data`；默认命名卷 `control-data`，生产可改为宿主机目录。
- `mem_limit` 与 `JAVA_OPTS`：默认 `1536m` 与 `-Xmx896m -XX:+UseG1GC -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError`。调整内存上限时同步调整 `-Xmx`，给 Metaspace、Code Cache、线程栈、直接内存与 G1 自身留出约 400–600MB。

其余配置固化在镜像内：容器端口 `8443`、数据目录 `/data`、JWT 签名密钥、备份与遥测参数、内置 GeoIP 库，以及 worker WSS 所需的 `-Djdk.internal.httpclient.disableHostnameVerification=true`（写在 ENTRYPOINT，覆盖 `JAVA_OPTS` 不会丢失）。`control-manager` 每次启动都会自行生成临时自签名证书，因此不需要准备、挂载或备份管理面 P12、CRT 和口令文件。

磁盘监控已改为宿主机主动推送。进入控制面“磁盘监控”页面后，系统会自动生成并明文展示上报 Token；将该值写入宿主机的 `scripts/disk-monitor/disk-monitor.env` 中的 `DISK_PUSH_TOKEN`，再通过 cron 定时调用 `push-smartctl.sh`。上报 Token 由页面随机生成并持久化。

如需清空控制面数据库和日志卷，重新进入首次创建管理员流程：

```bash
make CONFIRM=1 docker-reset-control
```

该命令会执行 `docker compose down -v`，会删除 `control-data`（SQLite 配置库、遥测日志库及备份）。

## 启动 worker

```bash
make docker-up-worker
```

worker 使用 `network_mode: host`，因此控制面下发的入站端口会直接监听在宿主机网络栈，不需要在 compose 里重复配置 `ports`。默认还会在宿主机的 `18444` 端口启动 WSS 管理端；请在 worker 的防火墙中放行该端口，使 controller 能访问它。

每台 worker 必须有独立的 ID：

```bash
WORKER_ID=shanghai-01
WORKER_MANAGEMENT_PORT=18444
```

启动 worker 后，它会在持久卷的 `/data/proxy-worker/management.token` 首次生成随机 Token；容器重建和镜像升级都会复用该文件。查看并复制 Token：

```bash
docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml exec -T proxy-worker \
  cat /data/proxy-worker/management.token
```

然后在管理台创建节点，填写以下两项：

- Worker WSS 地址：`wss://<worker 公网地址>:18444/management/ws`
- Worker 管理 Token：上一步显示的值

controller 会主动建立 WSS 连接，worker 在该连接中轮询配置、上报心跳指标和批量日志；controller 不需要公网入站地址。

本地临时节点可在 `deploy/docker/worker.env.local` 配置节点 ID 和管理端口，然后执行 `make docker-up-worker`。该文件不会提交；要使用其他环境文件时执行
`make docker-up-worker WORKER_ENV_FILE=/opt/home-proxy/workers/shanghai-01.env`。

使用 host network 时，新增入站端口前需要确认宿主机没有同端口进程占用。Linux 原生 Docker 对该模式支持最好；Docker Desktop 的 host network 行为可能不同。

worker WSS 使用启动时生成的自签名证书，worker 使用管理 Token 校验 controller 的连接。Token 不会写入启动日志；不要执行 `docker compose down -v`，该命令会删除 `proxy-worker-data` 卷及其中的 Token。

worker 配置优先级为：

1. 环境变量或 JVM system properties，例如 `WORKER_ID`、`WORKER_MANAGEMENT_PORT`、`WORKER_MANAGEMENT_TOKEN_FILE`。
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
