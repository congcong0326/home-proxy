# Dockerization Design

## Summary

本项目采用“本机开发环境构建产物，Docker 只负责运行”的方式容器化。开发机继续安装并使用 JDK、Maven、Node.js、npm 和 Make，通过 `make package` 生成前端静态资源和后端 JAR，再用 Dockerfile 将 JAR 打入轻量运行时镜像。

运行部署拆成两组：

- `control-manager` 与其专属 MySQL、ClickHouse 一起启动，组成控制面栈。
- `proxy-worker` 单独启动，通常部署在不同机器，通过控制面宿主机 IP 和端口访问 `control-manager`。

数据库容器只服务 `control-manager`，不对宿主机暴露 MySQL/ClickHouse 端口，避免和开发机或服务器上已有数据库服务冲突。

## Goals

- 本机执行 `make package` 完成完整应用构建，Docker 构建阶段不再运行 Maven/npm。
- `control-manager`、`proxy-worker` 分别制作镜像，避免一个容器内运行多个主进程。
- MySQL 与 ClickHouse 使用官方镜像，由 `docker compose` 和 `control-manager` 一起编排。
- MySQL/ClickHouse 默认只在 Docker 内部网络可见，不占用宿主机 `3306`、`8123` 等端口。
- `proxy-worker` 支持通过运行时参数指定远程 `control-manager` 的 IP 和端口。

## Non-Goals

- 不把 MySQL、ClickHouse 打包进应用镜像。
- 不把 `control-manager` 与 `proxy-worker` 合并为一个镜像或一个容器。
- 不在镜像中内置生产密钥、数据库密码、JWT secret、证书私钥。
- 不要求 `proxy-worker` 与 `control-manager` 共机部署。

## Development Environment Requirements

开发机需要安装：

- JDK 17：后端编译和运行基线。
- Maven 3.9+：执行后端多模块构建。
- Node.js LTS 与 npm：构建 `frontend`。
- GNU Make：使用仓库根目录 `Makefile` 统一构建入口。
- Docker Engine 或 Docker Desktop：构建并运行镜像。
- Docker Compose v2：编排 `control-manager`、MySQL、ClickHouse，以及可选的本地 worker。
- Git：拉取代码和查看变更。

推荐验证命令：

```bash
java -version
mvn -v
node -v
npm -v
make --version
docker --version
docker compose version
git --version
```

本机标准构建命令：

```bash
make package
```

该命令会构建前端，将 `frontend/build` 同步到 `backend/control-manager/src/main/resources/static`，再打包后端模块。

## Image Strategy

应用镜像拆成两个：

- `home-proxy-control-manager`：包含 `backend/control-manager/target/control-manager-*.jar`。
- `home-proxy-worker`：包含 `backend/proxy-worker/target/proxy-worker-*.jar`。

两个镜像都使用 JRE 17 运行时基础镜像即可，不需要在镜像内安装 Maven、Node.js 或 npm。

建议文件：

```text
deploy/docker/Dockerfile.control-manager
deploy/docker/Dockerfile.proxy-worker
deploy/docker/docker-compose.control.yml
deploy/docker/docker-compose.worker.yml
.dockerignore
```

## Control Stack Design

`deploy/docker/docker-compose.control.yml` 负责启动控制面栈：

```text
home-proxy-control-manager
home-proxy-mysql
home-proxy-clickhouse
```

网络设计：

- `home-proxy-db`：内部网络，只连接 `control-manager`、MySQL、ClickHouse。
- 数据库服务不配置宿主机 `ports`，只通过 Compose service name 被控制面访问。
- `control-manager` 暴露 HTTP/API 端口给浏览器和远程 `proxy-worker` 访问。

数据库连接使用内部服务名：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/tpproxy?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
CLICKHOUSE_URL=jdbc:ch://clickhouse:8123/default
```

宿主机端口建议使用可配置变量，默认避开常见开发端口：

```text
CONTROL_HOST_PORT=18081
control-manager container port=8081
```

远程 worker 访问时使用：

```text
http://<control-manager-host-ip>:18081
```

如果只允许本机浏览器访问，可以将端口绑定到 `127.0.0.1`。如果远程 worker 需要访问，则必须绑定到可访问网卡，并通过防火墙限制来源 IP。

## Worker Deployment Design

`proxy-worker` 单独部署，默认不依赖 MySQL 或 ClickHouse，只依赖 `control-manager` 提供的配置聚合和日志 API。

远程部署时需要指定控制面地址：

```text
CONTROL_HOST=<control-manager-host-ip>
CONTROL_PORT=18081
```

或等价的完整 URL：

```text
CONTROL_MANAGER_URL=http://<control-manager-host-ip>:18081
```

`ProxyWorkerConfig` 需要支持 Docker 运行时动态指定控制面地址，配置优先级为：

1. 环境变量或 JVM system properties。
2. 挂载到容器内的外部 `proxy-worker.properties`。
3. JAR 内置默认 `proxy-worker.properties`。

这样 worker 镜像可以在不同机器复用，不需要因为控制面 IP 或端口变化重新打包。

worker Compose 使用 host network，不配置 `ports`。控制面下发新的入站端口后，worker 在宿主机网络栈直接监听对应端口。

## Runtime Configuration

`control-manager` 使用 Spring Boot 配置体系，适合通过环境变量覆盖：

```text
SERVER_PORT=8081
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/tpproxy?...
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
CLICKHOUSE_URL=jdbc:ch://clickhouse:8123/default
CLICKHOUSE_USERNAME=default
CLICKHOUSE_PASSWORD=...
LOGS_PERSISTENCE=clickhouse
ADMIN_AUTH_JWT_SECRET=...
```

密钥、数据库密码、证书私钥不写入镜像。开发环境可以使用 `.env`，生产环境应使用宿主机环境变量、Compose env file、CI/CD secret 或专门的密钥管理方案。

TLS 证书仍通过挂载提供；私有容器化部署的 GeoIP 数据推荐在构建镜像前放到仓库根目录 `data/geoip/GeoLite2-City.mmdb`，并由 `control-manager` 与 `proxy-worker` 镜像复制到 `/app/data/geoip/`：

```text
/etc/nas-proxy/certs
/app/data/geoip/GeoLite2-City.mmdb
```

`GeoIPUtil` 会优先读取 `geoip.mmdb.path`、`GEOIP_MMDB_PATH`、`geoip.data.dir`、`GEOIP_DATA_DIR`，然后读取容器默认路径 `/app/data/geoip/GeoLite2-City.mmdb`。运行时仍可挂载同路径覆盖镜像内置数据库。

## Port And Conflict Strategy

- MySQL 不映射宿主机端口，避免与本机或服务器已有 MySQL 冲突。
- ClickHouse 不映射宿主机端口，避免与已有 ClickHouse 冲突。
- `control-manager` 只映射一个 HTTP/API 端口，例如 `18081:8081`。
- `proxy-worker` 使用 host network，按控制面下发的入站配置直接在宿主机监听代理端口，不在 compose 中重复维护 `ports`。
- 如同一台机器运行多个部署实例，应使用不同 Compose project name、不同数据卷和不同 `CONTROL_HOST_PORT`。

## Startup Flow

控制面栈启动：

1. MySQL 启动并初始化数据目录。
2. ClickHouse 启动并初始化数据目录。
3. `control-manager` 等待数据库可用后启动。
4. Flyway 在 MySQL 上执行迁移。
5. `control-manager` 初始化 ClickHouse 日志表。
6. 浏览器和远程 worker 通过宿主机 IP 与 `CONTROL_HOST_PORT` 访问控制面。

worker 启动：

1. 读取控制面地址配置。
2. 拉取 `/api/config/aggregate` 获取用户、入站、路由等配置。
3. 按配置启动本机代理入站端口。
4. 将访问日志和认证日志发送回控制面 API。

## Security Notes

- 对外暴露 `control-manager` 时必须设置强 `ADMIN_AUTH_JWT_SECRET`。
- 远程 worker 访问控制面时，应使用防火墙限制来源 IP。
- 生产环境建议在 `control-manager` 前放置 HTTPS 反向代理，或后续为控制面 API 增加双向认证/worker token。
- 不提交 `.env`、数据库密码、JWT secret、证书私钥。
- 数据库端口默认不暴露到宿主机，降低误访问风险。

## Implementation Checklist

- 在 `deploy/docker/` 新增两个 Dockerfile，分别复制本机构建好的 control-manager JAR 和 proxy-worker JAR。
- 新增 `.dockerignore`，排除 `.git`、`target` 中无关文件、`frontend/node_modules`、本地缓存和密钥文件。
- 新增 `deploy/docker/docker-compose.control.yml`，编排 `control-manager`、MySQL、ClickHouse、内部网络和持久化卷。
- 新增 `deploy/docker/docker-compose.worker.yml`，支持通过环境变量指定远程控制面地址，并使用 host network 暴露 worker 动态监听端口。
- 修改 `ProxyWorkerConfig`，支持环境变量/JVM 参数/外部配置文件覆盖 `control.host`、`control.port`、TLS 证书路径等运行时配置。
- 补充 README 或部署文档，说明 `make package`、构建镜像、启动控制面栈、启动远程 worker 的命令顺序。
