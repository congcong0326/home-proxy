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
- **日志和看板**：worker 上报访问日志，控制面用 ClickHouse 做日志审计、流量趋势、用户流量、目标 TopN 和聚合分析。
- **家庭运维功能**：WOL、磁盘 SMART 上报、邮件网关、计划任务、MySQL 备份和恢复。
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

- `control-manager`：控制面、管理页面和 API，默认绑定 `127.0.0.1:18081`；局域网访问时绑定到宿主机内网 IP
- `mysql`：控制面配置库，仅 Docker 内部网络可访问
- `clickhouse`：访问日志和统计库，仅 Docker 内部网络可访问
- `proxy-worker`：实际代理转发节点，通常和控制面分开部署，也可以部署在同一台机器

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
  mysql:
    image: mysql:8.4
    container_name: home-proxy-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-congcong}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-tpproxy}
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "MYSQL_PWD=$$MYSQL_ROOT_PASSWORD mysqladmin ping -h 127.0.0.1 -uroot --silent"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    networks:
      - home-proxy-db

  clickhouse:
    image: clickhouse/clickhouse-server:24.8
    container_name: home-proxy-clickhouse
    restart: unless-stopped
    environment:
      CLICKHOUSE_DB: ${CLICKHOUSE_DB:-default}
      CLICKHOUSE_USER: ${CLICKHOUSE_USER:-default}
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-congcong}
      CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT: 1
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - clickhouse-logs:/var/log/clickhouse-server
    healthcheck:
      test: ["CMD-SHELL", "clickhouse-client --host 127.0.0.1 --user \"$$CLICKHOUSE_USER\" --password \"$$CLICKHOUSE_PASSWORD\" --query 'SELECT 1'"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    networks:
      - home-proxy-db

  control-manager:
    image: congcong0326/home-proxy-control-manager:latest
    container_name: home-proxy-control-manager
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
      clickhouse:
        condition: service_healthy
    environment:
      SERVER_PORT: 8081
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE:-tpproxy}?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_ROOT_PASSWORD:-congcong}
      CLICKHOUSE_URL: jdbc:ch://clickhouse:8123/${CLICKHOUSE_DB:-default}
      CLICKHOUSE_USERNAME: ${CLICKHOUSE_USER:-default}
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-congcong}
      LOGS_PERSISTENCE: ${LOGS_PERSISTENCE:-clickhouse}
      ADMIN_AUTH_JWT_SECRET: ${ADMIN_AUTH_JWT_SECRET:-dev_only_change_me_home_proxy_jwt_secret}
      DISK_MONITOR_RETENTION_DAYS: ${DISK_MONITOR_RETENTION_DAYS:-7}
      GEOIP_MMDB_PATH: ${GEOIP_MMDB_PATH:-}
      GEOIP_DATA_DIR: ${GEOIP_DATA_DIR:-}
    ports:
      - "${CONTROL_BIND_ADDRESS:-127.0.0.1}:${CONTROL_HOST_PORT:-18081}:8081"
    networks:
      - home-proxy-db

volumes:
  mysql-data:
  clickhouse-data:
  clickhouse-logs:

networks:
  home-proxy-db:
    name: home-proxy-db
YAML
```

如果需要让同一局域网或 VPN 内的浏览器、worker 访问控制面，先创建 `.env` 并把 `CONTROL_BIND_ADDRESS` 设置为控制面宿主机的内网 IP：

```bash
cat > .env <<'EOF'
CONTROL_BIND_ADDRESS=192.168.1.10
CONTROL_HOST_PORT=18081
EOF
```

启动：

```bash
docker compose up -d
```

启动完成后访问。只在本机操作时访问：

```text
http://127.0.0.1:18081
```

局域网模式访问：

```text
http://<控制面内网IP>:18081
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
      CONTROL_MANAGER_URL: ${CONTROL_MANAGER_URL:-http://127.0.0.1:18081}
      PROXY_WORKER_CONFIG: ${PROXY_WORKER_CONFIG:-}
      TLS_CERT_FILE: ${TLS_CERT_FILE:-}
      TLS_KEY_FILE: ${TLS_KEY_FILE:-}
      TLS_KEY_PASSWORD: ${TLS_KEY_PASSWORD:-}
      GEOIP_MMDB_PATH: ${GEOIP_MMDB_PATH:-}
      GEOIP_DATA_DIR: ${GEOIP_DATA_DIR:-}
YAML
```

启动：

```bash
docker compose -f docker-compose.worker.yml up -d
```

如果 worker 部署在另一台机器，把控制面地址改成控制面服务器的内网 IP：

```bash
CONTROL_MANAGER_URL=http://<控制面内网IP>:18081 docker compose -f docker-compose.worker.yml up -d
```

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
docker logs -f home-proxy-mysql
docker logs -f home-proxy-clickhouse
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

这会删除 MySQL 和 ClickHouse 数据卷，配置、管理员账号和日志都会被清空。

### 5. 可选配置

可以在部署目录创建 `.env` 覆盖默认配置：

```bash
cat > .env <<'EOF'
CONTROL_BIND_ADDRESS=192.168.1.10
CONTROL_HOST_PORT=18081
MYSQL_ROOT_PASSWORD=congcong
CLICKHOUSE_PASSWORD=congcong
ADMIN_AUTH_JWT_SECRET=dev_only_change_me_home_proxy_jwt_secret
EOF
```

常用变量：

```text
CONTROL_BIND_ADDRESS           控制面绑定的宿主机地址，默认 127.0.0.1；局域网访问填写宿主机内网 IP
CONTROL_HOST_PORT              控制面宿主机端口，默认 18081
MYSQL_ROOT_PASSWORD            MySQL root 密码，默认 congcong
CLICKHOUSE_PASSWORD            ClickHouse 密码，默认 congcong
ADMIN_AUTH_JWT_SECRET          控制面 JWT 签名密钥
CONTROL_MANAGER_URL            worker 连接控制面的地址
GEOIP_MMDB_PATH                GeoIP mmdb 文件路径
GEOIP_DATA_DIR                 GeoIP 数据目录
TLS_CERT_FILE                  worker TLS 证书路径
TLS_KEY_FILE                   worker TLS 私钥路径
TLS_KEY_PASSWORD               worker TLS 私钥密码，可为空
```

即使绑定了内网 IP，生产环境也建议在宿主机防火墙或云安全组中只允许可信 LAN/VPN 网段访问 `CONTROL_HOST_PORT`。

如需使用或覆盖 GeoIP 数据库，可以挂载本地 `GeoLite2-City.mmdb`：

```yaml
volumes:
  - ./data/geoip/GeoLite2-City.mmdb:/app/data/geoip/GeoLite2-City.mmdb:ro
```

如需让 worker 使用自己的 TLS 证书，可以在 `proxy-worker` 服务中增加挂载，并设置证书路径：

```yaml
volumes:
  - /etc/nas-proxy/certs:/etc/nas-proxy/certs:ro
environment:
  TLS_CERT_FILE: /etc/nas-proxy/certs/s-proxy-ca.crt
  TLS_KEY_FILE: /etc/nas-proxy/certs/s-proxy-ca.key
```

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
make docker-save-offline   # 导出应用镜像以及 MySQL/ClickHouse 镜像
```

更完整的容器化说明见 [docs/development/docker-deployment.md](docs/development/docker-deployment.md)。
