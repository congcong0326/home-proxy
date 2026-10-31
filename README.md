# NasProxy - 给家庭网络用的代理网关

## 为什么做 NasProxy

最开始做这个项目，是因为我想给家里的 NAS 和各种内网服务做一个更顺手的反向代理工具。刚开始自然会想到 Nginx，但实际用下来发现，有些服务的认证协议比较特殊，普通 HTTP 反代并不能很好处理，往往需要在 Netty 这类网络框架里针对协议做更细的适配。

后来我也想过用 Xray 这类代理工具来替代 Nginx。它可以对外提供加密的 SOCKS over TLS、Shadowsocks 等入口，安全性和可用性都不错，但我的核心需求不只是“把流量代理出去”，而是希望能把某个特定域名转发到家里某台机器的某个 IP 和端口上。比如我可以给自己的内网服务随意分配一个域名，而不一定真的购买这个域名。这个需求在现成工具里并不完全贴合，所以最后就用 Netty 自己写了一套。

第一版用起来已经能满足“开着代理工具，全局代理访问内网服务”的场景。但我后来又想进一步：在家里使用时，最好不要每台设备、每个软件都手动配置代理，也能自动获得同样的访问效果。继续研究之后，发现需要把透明代理和 DNS Server 结合起来。

在这个模式下，DNS 会把我想接管的域名解析到一个 fake IP，让客户端程序的流量先进入代理网关。代理网关再从 TLS SNI 或 HTTP Host 中还原出原始域名，并根据配置把请求转发到真正部署在内网里的服务。

一个具体例子是 Codex。我在 NAS 上搭了一个 AI 反代服务，然后把 Codex 的 `base_url` 配成：

```toml
[model_providers.cliproxyapi]
name = "cliproxyapi"
base_url = "http://my-ai.com/v1"
wire_api = "responses"
```

`my-ai.com` 这个域名并没有购买，公网 DNS 里也不存在。但在家里的 DNS Server 中，它会被解析到 fake IP，于是 Codex 的请求会进入 NasProxy。NasProxy 再根据 `my-ai.com` 这个 Host，把流量交给 NAS 上真正的 AI 反代服务。这样在家里使用时，不需要额外打开代理工具，也能像访问一个真实域名一样访问内网服务。

## NasProxy 现在是什么

<img width="1479" height="781" alt="家庭nas网络拓扑 drawio (1)" src="https://github.com/user-attachments/assets/ba2423d6-ee2f-41a9-af76-bea40ac01e24" />


NasProxy 现在更像一个给家庭网络用的代理网关和控制台：管理端负责配置、看板和日志，worker 负责真正跑在网关机器上的转发。它既可以处理 HTTP/SOCKS/Shadowsocks 这类显式代理流量，也可以配合 DNS Server 和透明代理接管局域网里的访问请求，再按域名、地区、规则集或用户配置选择直连、阻断、目标重写或上游代理。

除了代理本身，我也把一些家庭网络运维能力放了进来，比如访问日志、流量统计、磁盘监控、WOL、邮件通知和数据库备份。你可以把它部署在 NAS、软路由、旁路由、云主机，或者任何一台能跑 Docker 的 Linux 机器上。

如果你也有这些需求，它会比较合适：

- 家里有公网 IP，但 443、8443 这类常用端口不好用，想用自己的端口和域名规则把服务接出去。
- 内网服务比较多，希望用一个页面管理用户、入站端口、路由顺序和出站策略。
- 不同流量想走不同出口：国内直连、指定域名目标重写、DNS 重写，或者走 Shadowsocks / VLESS + REALITY / DoT 等上游。
- 代理跑起来以后，还想知道谁在访问、流量去了哪里、哪些目标最常用，出错时也能从日志里查原因。
- 家庭运维也想顺手放一起，比如远程唤醒设备、看磁盘 SMART 状态、发邮件通知、备份和恢复配置库。

目前已经有的能力：

- **控制面和 worker 分开部署**：管理端保存配置、提供 Web 页面和统计分析；worker 定时拉取配置，并按最新配置启动或刷新入站服务。
- **多协议入站**：支持 SOCKS5、HTTP CONNECT、Shadowsocks、透明代理、DNS Server、DoT；部分流式入站可以按需打开 TLS。
- **策略路由**：可以按用户、入站、域名、GeoIP 和规则集匹配，策略包括直连、阻断、出站代理、目标重写和 DNS 重写。
- **多种出站**：支持上游 SOCKS5、HTTP CONNECT、Shadowsocks、DNS/DoT，以及 VLESS + REALITY Vision，适合接已有的 Xray/REALITY 节点。
- **规则集同步**：可以维护 AI、流媒体、SaaS、开发、广告、Geo 等分类规则，从 Git Raw 或 HTTP 文件同步后发布给 worker。
- **日志和看板**：worker 上报会话、DNS 与认证失败日志，控制面存入内嵌 SQLite 遥测库，做日志审计、流量概览、并发、用户流量、目标 TopN 和分布分析。
- **家庭运维功能**：WOL、磁盘 SMART 上报、邮件网关、计划任务、SQLite 数据库备份和恢复。
- **每日巡检邮件**：新增 `ops.inspection`，按定时任务汇总过去 24 小时磁盘、网络质量、主网关外网流量和成功认证位置变化；支持邮件预览。配置与统计边界见 [每日巡检邮件](docs/development/mail-inspection.md)。
- **Docker 部署**：镜像已经推到 Docker Hub，可以直接用 Docker Compose 拉起来。

---

## License
MIT

## Docker 快速上手

推荐直接使用已发布到 Docker Hub 的镜像，不需要在本机安装 JDK、Maven、Node.js 或 npm。

前置要求：

- Docker Engine
- Docker Compose v2，可通过 `docker compose version` 检查

镜像：

```text
docker pull congcong0326/home-proxy-control-manager:latest
docker pull congcong0326/home-proxy-worker:latest
```

运行时会启动这些服务：

- `control-manager`：控制面、管理页面和 API，默认通过 HTTPS 绑定 `0.0.0.0:18443`；请通过宿主机防火墙或云安全组限制访问来源。配置元数据与日志都保存在内嵌 SQLite（容器内 `/data/home-proxy.db` 与 `/data/telemetry.db`，对应数据卷 `control-data`），无需任何独立数据库容器
- `proxy-worker`：实际代理转发节点，通常和控制面分开部署，也可以部署在同一台机器

新版日志采集、接收和统计查询默认开启。管理端使用 Spring Boot 配置 `observability.ingest-enabled`、`observability.query-enabled`，可分别通过环境变量 `OBSERVABILITY_INGEST_ENABLED=false`、`OBSERVABILITY_QUERY_ENABLED=false` 关闭；Worker 可通过 `OBSERVABILITY_ENABLED=false` 关闭新版采集。修改环境变量后需要重建对应容器。新版统计从采集开启后累计，旧日志不会自动补入。

### 1. 启动控制面

在服务器上创建一个部署目录：

```bash
mkdir -p home-proxy
cd home-proxy
```

创建 `docker-compose.yml`：

```bash
cat > docker-compose.yml <<'YAML'
name: home-proxy-control

services:
  control-manager:
    image: congcong0326/home-proxy-control-manager:latest
    container_name: home-proxy-control-manager
    restart: unless-stopped
    mem_limit: 1536m
    environment:
      JAVA_OPTS: "-Xmx896m -XX:+UseG1GC -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"
    ports:
      - "0.0.0.0:18443:8443"
    volumes:
      - control-data:/data

volumes:
  control-data:
YAML
```

控制面只有这几项需要按部署环境修改，直接编辑 `docker-compose.yml` 即可，不需要 `.env`：

- `ports`：宿主机有多个网络时，可写成 `"192.168.1.10:18443:8443"`，只监听内网地址。
- `volumes`：`control-data` 是命名卷；如需把数据放在指定磁盘，改成宿主机目录，例如 `/mnt/ssd/home-proxy:/data`。
- `mem_limit` 与 `JAVA_OPTS`：调整内存上限时同步调整 `-Xmx`，堆之外的 Metaspace、线程栈、直接内存与 G1 自身约需 400–600MB。

其余配置（容器端口、备份、遥测、GeoIP、JWT 签名密钥等）都已固化为镜像内默认值。控制面每次启动会生成自签名 HTTPS 证书，浏览器会显示证书警告。

启动：

```bash
docker compose up -d
```

启动完成后访问。只在本机操作时访问：

```text
https://127.0.0.1:18443
```

局域网模式访问：

```text
https://<控制面内网IP>:18443
```

第一次进入页面时按提示创建管理员账号。

### 2. 启动 worker

如果 worker 和控制面在同一台机器，继续创建 `docker-compose.worker.yml`：

```bash
cat > docker-compose.worker.yml <<'YAML'
name: home-proxy-worker

services:
  proxy-worker:
    image: congcong0326/home-proxy-worker:latest
    container_name: home-proxy-worker
    restart: unless-stopped
    network_mode: host
    environment:
      WORKER_ID: default                     # 每台 worker 唯一，与管理台节点一致
      JAVA_OPTS: "-Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m"
    volumes:
      - proxy-worker-data:/data/proxy-worker
    logging:
      options:
        max-size: "20m"
        max-file: "3"

volumes:
  proxy-worker-data:
YAML
```

启动：

```bash
docker compose -f docker-compose.worker.yml up -d
```

worker 启动后会在持久卷中生成 `/data/proxy-worker/management.token`，并监听 `wss://<worker地址>:18444/management/ws`。将该 Token 和 WSS 地址填入管理台的“新建节点”表单即可；controller 会主动连接 worker，因此 controller 不需要公网地址。重建容器会保留 Token，`docker compose down -v` 会删除它。

新建／编辑代理节点时，可在“代理网关日志上报”下方设置“日志级别”，支持 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`OFF`，默认 `INFO`。该设置控制 worker 的控制台和文件运行日志，与访问／认证日志上报开关独立。worker 启动时默认使用 `INFO`，恢复本地成功配置或收到管理端配置后应用配置等级；保存后通常在下一次 WSS 配置轮询（默认 2 秒）生效，相同等级不会重复修改，仅调整日志设置不会刷新代理监听或中断连接。离线节点重连后应用新配置。

数据库升级由 Flyway 自动执行 `V37__add_worker_log_level.sql`，为 `proxy_worker` 增加 `log_level`，已有节点默认 `INFO`。升级时先更新 worker，再更新控制面，避免旧 worker 无法解析新增配置字段。

worker 使用 `network_mode: host`，控制面下发的代理入站端口会直接监听在宿主机上。新增端口前请确认宿主机防火墙已放行，并且没有其他进程占用同一端口。

### 3. 透明代理场景（可选）

如果你希望把 NasProxy 放在软路由、小主机主路由或旁路由上，让 LAN 设备不用手动配置代理也能按规则分流，需要再配置透明代理。

这类部署多做三件事：

- 在控制面创建 `TP_PROXY` 入站，端口通常使用 `10808`，并发布给 worker。
- worker 继续使用 `network_mode: host`，让透明代理入站直接监听宿主机网络。
- 在宿主机上运行 `backend/tproxy_netty.sh`，用 `iptables TPROXY`、`ip rule` 和本地路由表把 LAN TCP 流量交给 worker。

透明代理不是简单的端口转发，它依赖 Linux 的 TPROXY 和 Netty `IP_TRANSPARENT`。脚本里的 `LAN_CIDR`、`LAN_IF`、`WAN_IF`、`TPORT` 必须按你的网络实际情况修改；如果这台机器直接拿公网 IP，也请先处理 SSH、控制面和公网 SOCKS 的安全边界。

完整说明见 [docs/development/transparent-proxy-deployment.md](docs/development/transparent-proxy-deployment.md)。

### 4. 常用运维命令

查看容器状态：

```bash
docker compose ps
docker compose -f docker-compose.worker.yml ps
```

查看日志：

```bash
docker logs -f home-proxy-control-manager
docker logs -f home-proxy-worker
```

更新镜像：

```bash
docker compose pull
docker compose up -d

docker compose -f docker-compose.worker.yml pull
docker compose -f docker-compose.worker.yml up -d
```

重启：

```bash
docker compose restart control-manager
docker compose -f docker-compose.worker.yml restart proxy-worker
```

清空控制面数据并重新初始化：

```bash
docker compose down -v
docker compose up -d
```

这会删除数据卷 `control-data`（SQLite 配置库、遥测日志库及其备份），配置、管理员账号和日志都会被清空。

### 5. 可选配置

控制面的固化默认值仍可在 compose 的 `environment` 中临时覆盖（Spring Boot 宽松绑定），一般不需要：

```text
HOME_PROXY_BACKUP_RETENTION    控制端定时备份保留份数，默认 7
HOME_PROXY_BACKUP_CRON         控制端定时备份 cron（Spring 6 段格式），默认每天 03:30
```

worker 只需要 `WORKER_ID` 和按机器内存设置的 `JAVA_OPTS`（堆与直接内存；OOM 退出与堆转储参数、时区 `Asia/Shanghai` 已固化在镜像内）。其余可选变量：

```text
WORKER_MANAGEMENT_HOST         WSS 管理端监听地址，默认 0.0.0.0（host 网络下 compose ports 不生效）
WORKER_MANAGEMENT_PORT         WSS 管理端口，默认 18444
WORKER_MANAGEMENT_TOKEN        固定管理 Token（hpw_ 前缀），设置后不读写卷内 Token 文件；默认首次启动生成并持久化
GEOIP_MMDB_PATH                GeoIP mmdb 文件路径，默认使用镜像内置库
GEOIP_DATA_DIR                 GeoIP 数据目录
TLS_CERT_FILE / TLS_KEY_FILE   外部入站 TLS 证书与私钥，默认不配置（使用自签证书）
```

即使绑定了内网 IP，生产环境也建议在宿主机防火墙或云安全组中只允许可信 LAN/VPN 网段访问控制面端口。

如需使用或覆盖 GeoIP 数据库，可以挂载本地 `GeoLite2-City.mmdb`：

```yaml
volumes:
  - ./data/geoip/GeoLite2-City.mmdb:/app/data/geoip/GeoLite2-City.mmdb:ro
```

TLS 入站默认使用自签证书：worker 首次需要时在数据卷生成 `/data/proxy-worker/tls/tls.{crt,key}`，之后重启复用，客户端需跳过证书校验。确需使用外部证书时再挂载并设置路径；一旦设置，文件不可读会直接报错，不会回退自签：

```yaml
volumes:
  - ./certs:/data/tls/inbound:ro
environment:
  TLS_CERT_FILE: /data/tls/inbound/tls.crt
  TLS_KEY_FILE: /data/tls/inbound/tls.key
```

### 数据库备份与恢复

配置元数据保存在 SQLite（`/data/home-proxy.db`，WAL 模式）。**不要直接复制正在运行的 `.db`/`-wal` 文件**，请使用以下方式：

- 管理页面“系统运维 → 数据备份”：导出当前数据库的一致性快照（`.db`）。
- 自动备份：控制端按 `HOME_PROXY_BACKUP_CRON`（默认每天 03:30）用 `VACUUM INTO` 写入 `/data/backups/home-proxy-<时间>.db`，保留最近 `HOME_PROXY_BACKUP_RETENTION`（默认 7）份。将该目录同步到数据卷之外才算异地备份。
- 恢复（页面）：上传 `.db` 并输入确认短语，控制端校验（SQLite 头、`integrity_check`、必需表）后暂存，**重启控制端时**替换当前数据库；被替换的库保存为 `/data/backups/pre-restore-<时间>.db`。
- 恢复（手动）：停止 `control-manager` → 用备份文件替换 `/data/home-proxy.db`，并删除 `/data/home-proxy.db-wal`、`-shm` → 启动。
- 日志库 `/data/telemetry.db` 不在上述备份范围内：它可以随时删除，控制端启动时重建空表（历史日志随之丢失）。镜像内带 `sqlite3`，可只读查看：`docker exec home-proxy-control-manager sqlite3 -readonly /data/telemetry.db 'SELECT COUNT(*) FROM proxy_session_log_v1'`。

### 6. 从源码构建镜像

如果需要自己修改代码并构建镜像，请使用仓库根目录的 `Makefile`：

```bash
git clone <this-repo-url>
cd home-proxy
make docker-build
```

常用目标：

```bash
make docker-build          # 打包并构建 control-manager 和 worker 镜像
make docker-build-control  # 只构建 control-manager 镜像
make docker-build-worker   # 只构建 worker 镜像
make docker-save           # 导出应用镜像到 home-proxy-images.tar
make docker-save-offline   # 同 docker-save（控制面已不依赖第三方镜像）
```

更完整的容器化说明见 [docs/development/docker-deployment.md](docs/development/docker-deployment.md)。

### 新版日志落库与认证位置

管理端接收新版四类日志后先进入有界内存队列并返回 ACK；ACK 表示内存接收，管理端异常退出可能丢失尚未落库的数据。每类日志独立累计 1000 条或最早等待 120 秒后在一个事务内批量写入 SQLite 遥测库 `/data/telemetry.db`，页面只显示已经落库的日志。同一条日志（Worker、实例、记录 ID 相同）重发时只保留首次写入。正常关闭会尽力 flush，写入失败保留内存队列重试。

日志默认永久保留；可用 `OBSERVABILITY_SESSION_RETENTION_DAYS`、`OBSERVABILITY_DNS_RETENTION_DAYS`、`OBSERVABILITY_AUTH_RETENTION_DAYS` 分别设置保留天数，控制端每小时分批删除过期记录并回收空间。

可通过 `OBSERVABILITY_FLUSH_BATCH_SIZE`、`OBSERVABILITY_FLUSH_MAX_DELAY_MS` 调整攒批；管理端总容量默认 `OBSERVABILITY_BUFFER_CAPACITY=20000`、字节预算 `OBSERVABILITY_BUFFER_MAX_BYTES=67108864`。容量满整批拒绝，Worker 保留重试。`/api/observability/v1/status` 返回 `acknowledgement=MANAGER_MEMORY_QUEUE` 与 `managerBuffer` 状态。

认证来源位置需要 `data/geoip/GeoLite2-City.mmdb` 随管理端镜像打包或挂载。缺失时每分钟尝试重新加载；恢复后查询历史“位置数据库不可用”记录会重新解析位置。数据库文件不得提交。
