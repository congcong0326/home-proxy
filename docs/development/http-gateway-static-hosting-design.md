# HTTP 网关、服务端证书与静态站点托管设计

## 1. 文档状态

- 状态：设计草案，范围已确认，待实施
- 设计基线日期：2026-07-25
- 数据库基线：当前工作区已有 Flyway `V20` 至 `V22`，本需求建议从 `V23` 开始顺延
- 涉及模块：`common`、`control-manager`、`proxy-worker`、`frontend`
- 核心范围：HTTP/1.1 七层反向代理、HTTP 服务端证书上传与下发、SOCKS5 over TLS 不校验证书出站、静态前端托管

本文记录当前讨论确认的 V1 方案。实现过程中如果需要扩大 TLS 验证、HTTP/2、WebSocket、动态回源缓存或多上游负载均衡范围，应先更新本文，不能通过扩展 JSON 字段隐式引入新语义。

## 2. 背景与使用场景

目标部署链路如下：

```text
Browser
  -- HTTPS -->
VPS proxy-worker
  - HTTP TLS 卸载
  - Host / Path 七层路由
  - 静态前端文件直接返回
  -- TLS，内部承载 SOCKS5 -->
内网代理服务
  -- TCP -->
目标 HTTP Server
```

典型站点配置：

```text
https://example.com/
  /api/*  -> SOCKS5 over TLS -> 内网 HTTP 服务
  /*      -> VPS worker 本地静态前端
```

当前 worker 已经具备以下基础能力：

- 按 worker 拉取最小聚合配置，并使用 Worker Token 认证。
- 将 SOCKS5、HTTP CONNECT、透明代理等入站归一成隧道请求。
- 通过 `OutboundConnectorFactory` 选择 DIRECT、SOCKS5、HTTP CONNECT、Shadowsocks、VLESS REALITY 等出站连接器。
- 使用 `ProxyContext` 管理入站 Netty Server 生命周期。
- 使用 last-known-good 配置在管理端不可用时恢复运行。

现有隧道连接成功后会安装裸 TCP 双向 Relay，不适用于 HTTP keep-alive 上的逐请求路由。因此 HTTP 网关需要独立的七层 Pipeline，但应复用出站 TCP 建连能力。

## 3. 已确认决策

### 3.1 HTTP 网关与现有入站

- 新增 `ProtocolType.HTTP_GATEWAY`。
- HTTP 网关使用独立管理页面、独立业务 API 和独立扩展表。
- HTTP 网关底层复用 `inbound_configs` 的 worker 归属、监听 IP、端口、启停和 Server 生命周期。
- 一个 HTTP 网关对应一个 `inbound_configs` 记录和一个 `http_gateways` 记录。
- 通用入站 API 不允许直接创建或编辑 `HTTP_GATEWAY`，必须通过 HTTP 网关 API 在一个事务中维护两部分数据。
- 现有入站列表可以展示 HTTP 网关监听器，但编辑动作跳转到 HTTP 网关页面。

### 3.2 HTTP 协议范围

- V1 只支持 HTTP/1.1。
- 支持精确域名、通配域名和 Path Prefix 路由。
- 支持 `PROXY`、`STATIC`、`REDIRECT`、`REJECT` 四种 Route 动作。
- 请求和响应都流式处理，不使用 `HttpObjectAggregator` 聚合完整请求或响应。
- V1 不支持 HTTP/2、HTTP/3、WebSocket、正则 Path、动态脚本和服务端模板。
- SSE 可以作为普通长响应流转发，但需要单独配置合适的空闲超时。

### 3.3 HTTP 服务端证书

- V1 只管理 HTTP 网关作为 TLS 服务端使用的证书。
- 管理端支持上传服务端证书链和私钥，并对私钥加密落库。
- HTTP Virtual Host 通过证书 ID 引用服务端证书。
- 一个 Gateway 可以通过 SNI 使用多个服务端证书。
- 证书使用稳定逻辑 ID 和递增版本，更新证书不要求修改 Virtual Host 引用。
- 聚合配置只下发证书引用和版本；worker 使用 Token 从独立 secret API 下载实际证书 bundle。
- worker 只允许下载当前配置实际引用的证书。

### 3.4 SOCKS5 over TLS

- V1 支持 SOCKS5 出站外层增加普通 TLS。
- V1 固定使用 `INSECURE_SKIP_VERIFY`，不校验 SOCKS TLS 服务端证书。
- V1 不管理 SOCKS 信任证书，不支持自定义 CA、证书固定、CRL、OCSP 或 mTLS。
- 管理页面必须明确标注“不验证证书，仅提供链路加密”。
- TLS 握手成功后才能开始 SOCKS5 greeting、认证和 CONNECT。

### 3.5 静态站点

- 管理端上传已经构建完成的 ZIP 包，不在 worker 或管理端执行 npm 构建。
- 管理端保存版本化 artifact，worker 按配置引用自动下载。
- worker 校验 SHA-256、解压到版本目录并原子切换当前版本。
- 支持 SPA fallback、ETag、Cache-Control、GET 和 HEAD。
- V1 不做按需回源、LRU 缓存、动态缓存刷新、全球节点调度或大文件 CDN。

## 4. 目标

- 在 worker 上提供类似 nginx HTTP server 的轻量七层反向代理。
- 在 VPS 终止浏览器 HTTPS，并通过现有出站代理链路访问目标 HTTP 服务。
- 支持 SOCKS5 连接外层 TLS 加密。
- 支持一个 443 监听器按 SNI 承载多个域名和服务端证书。
- 支持按 Host 和 Path 将请求转发到 Backend 或本地静态站点。
- 支持管理端上传、版本化和自动下发 HTTP 服务端证书。
- 支持管理端上传静态前端包、发布、回滚和 worker 本地托管。
- 配置、证书或静态包应用失败时继续使用最后一次成功版本。
- 保持多 worker 最小配置分发和 Worker Token 授权模型。

## 5. 非目标

- 不实现完整 nginx 指令兼容。
- 不实现 HTTP/2、HTTP/3、QUIC 或 WebSocket。
- 不实现多个 HTTP Backend 的负载均衡、主动健康检查或熔断。
- 不实现请求或响应内容改写、WAF、Lua、JavaScript 插件或模板渲染。
- 不实现 SOCKS TLS 严格证书验证、信任包、证书固定或客户端证书。
- 不把静态包存入 MySQL BLOB。
- 不在 worker 上执行前端源码构建。
- 不把 HTTP 网关塞入现有入站配置表单。

## 6. 总体架构

### 6.1 控制面

```text
Admin UI
  -> HTTP Gateway API
  -> HTTP Certificate API
  -> Static Site API
  -> Egress Route API
            |
            v
      control-manager
        - MySQL metadata
        - encrypted HTTP private keys
        - versioned static artifacts
        - worker dependency resolver
        - aggregate config + secret/artifact endpoints
```

### 6.2 Worker 数据面

```text
Client TCP
  -> optional SniHandler / SslHandler
  -> HttpServerCodec
  -> HTTP Route Matcher
       -> STATIC: local file streamer
       -> REDIRECT / REJECT: local response
       -> PROXY:
            HttpBackend
              -> EgressRoute
              -> TcpDialer
                   -> DIRECT
                   -> SOCKS5
                   -> SOCKS5 over TLS
                   -> other existing connectors
              -> optional upstream HTTP codec
              -> streaming relay at HTTP object level
```

### 6.3 配置应用流程

```text
worker 拉取 aggregate config
  -> 解析当前 worker 的 HTTP Gateway 配置
  -> 计算缺失证书版本和静态发布版本
  -> 下载并校验依赖
  -> 构建 SslContext、SNI 映射、RouteTable 和 Backend Pool
  -> 所有依赖准备成功
  -> 原子切换运行时快照
  -> 上报 appliedConfigHash
```

任一步失败都不得先关闭当前正常运行的 HTTP Gateway。

## 7. 领域模型

```text
InboundConfig
  workerId / listenIp / port / protocol / tlsEnabled / status
          |
          | 1:1
          v
HttpGateway
  defaultCertificate / limits / timeouts
          |
          +-- HttpVirtualHost
          |     domains / serverCertificate
          |          |
          |          +-- HttpRoute
          |                 action / pathPrefix / priority
          |
          +-- HttpBackend
                targetHost / targetPort / egressRoute

StaticSite
  -> StaticSiteRelease

HttpTlsCertificate
  -> HttpTlsCertificateVersion
```

HTTP Route 和现有 `RouteDTO` 必须保持不同命名和语义：

- `HttpRoute`：匹配 Host/Path 后选择 `PROXY`、`STATIC`、`REDIRECT` 或 `REJECT`。
- `EgressRoute`：现有 Route，决定 DIRECT、SOCKS5、SS、VLESS 等网络出站方式。

## 8. 数据模型

迁移版本在实施时以仓库最新版本顺延。按当前工作区建议：

```text
V23__create_http_gateway_tables.sql
V24__create_http_tls_certificate_tables.sql
V25__create_static_site_tables.sql
V26__extend_route_outbound_transport.sql
```

### 8.1 HTTP Gateway

```text
http_gateways
- id                               BIGINT PK
- inbound_id                       BIGINT NOT NULL UNIQUE
- default_certificate_id           BIGINT NULL
- max_initial_line_length           INT NOT NULL DEFAULT 8192
- max_header_size                   INT NOT NULL DEFAULT 32768
- max_request_body_bytes            BIGINT NOT NULL DEFAULT 2097152
- connect_timeout_ms                INT NOT NULL DEFAULT 5000
- response_header_timeout_ms        INT NOT NULL DEFAULT 15000
- idle_timeout_ms                   INT NOT NULL DEFAULT 60000
- created_at                        DATETIME(6) NOT NULL
- updated_at                        DATETIME(6) NOT NULL
```

约束：

- `inbound_id` 引用 `inbound_configs.id`。
- 对应 Inbound 的 `protocol` 必须为 `HTTP_GATEWAY`。
- 如果 Inbound `tls_enabled=true`，必须配置可用的 `default_certificate_id`。
- HTTP Gateway 不使用 `anonymous_user_id`、`fallback_route_id` 和 `inbound_route_bindings`，这些字段保持空值或空列表。

### 8.2 Virtual Host

```text
http_virtual_hosts
- id                               BIGINT PK
- gateway_id                       BIGINT NOT NULL
- name                             VARCHAR(128) NOT NULL
- domains                          JSON NOT NULL
- server_certificate_id            BIGINT NULL
- status                           TINYINT NOT NULL DEFAULT 1
- created_at                       DATETIME(6) NOT NULL
- updated_at                       DATETIME(6) NOT NULL
```

约束：

- `domains` 只允许精确域名、`*` 或 `*.example.com`。
- 域名统一转小写、去除末尾点并做 IDN ASCII 规范化。
- 一个 Gateway 内不允许重复精确域名或语义相同的通配域名。
- TLS Gateway 上 `server_certificate_id` 为空时使用 Gateway 默认服务端证书。
- 保存时校验证书 SAN 是否覆盖精确域名和通配域名；通配规则按 RFC 约束只匹配一层标签。
- `*` 只表示 HTTP Host 的 catch-all，不表示证书可以覆盖任意域名；TLS 握手仍返回 Gateway 默认服务端证书，客户端是否接受取决于请求域名是否被该证书 SAN 覆盖。

### 8.3 HTTP Backend

```text
http_backends
- id                               BIGINT PK
- name                             VARCHAR(128) NOT NULL
- target_host                      VARCHAR(255) NOT NULL
- target_port                      INT NOT NULL
- egress_route_id                  BIGINT NOT NULL
- max_connections                  INT NOT NULL DEFAULT 8
- idle_timeout_ms                  INT NOT NULL DEFAULT 60000
- status                           TINYINT NOT NULL DEFAULT 1
- created_at                       DATETIME(6) NOT NULL
- updated_at                       DATETIME(6) NOT NULL
```

约束：

- V1 一个 Backend 只包含一个 Endpoint。
- `target_host` 是目标 HTTP Server，而不是 SOCKS 代理地址。
- `egress_route_id` 引用现有 Route；Route 决定如何建立到 `target_host:target_port` 的 TCP 通道。
- Backend 只支持明文 HTTP/1.1 上游；上游 HTTPS 留到后续版本。

### 8.4 HTTP Route

```text
http_routes
- id                               BIGINT PK
- virtual_host_id                  BIGINT NOT NULL
- name                             VARCHAR(128) NOT NULL
- path_prefix                      VARCHAR(2048) NOT NULL
- priority                         INT NOT NULL DEFAULT 0
- action                           VARCHAR(32) NOT NULL
- backend_id                       BIGINT NULL
- static_site_id                   BIGINT NULL
- strip_prefix                     TINYINT NOT NULL DEFAULT 0
- preserve_host                    TINYINT NOT NULL DEFAULT 1
- upstream_host                    VARCHAR(255) NULL
- redirect_location                VARCHAR(2048) NULL
- redirect_status                  INT NULL
- reject_status                    INT NULL
- status                           TINYINT NOT NULL DEFAULT 1
- created_at                       DATETIME(6) NOT NULL
- updated_at                       DATETIME(6) NOT NULL
```

动作约束：

- `PROXY`：必须有 `backend_id`，不得有 `static_site_id`。
- `STATIC`：必须有 `static_site_id`，不得有 `backend_id`。
- `REDIRECT`：必须有 `redirect_location`，状态码只允许 301、302、307、308。
- `REJECT`：状态码限制为允许的本地响应码，默认 404。
- `path_prefix` 必须以 `/` 开头，不支持正则。

### 8.5 HTTP 服务端证书

```text
http_tls_certificates
- id                               BIGINT PK
- name                             VARCHAR(128) NOT NULL UNIQUE
- current_version                  INT NOT NULL
- status                           TINYINT NOT NULL DEFAULT 1
- created_at                       DATETIME(6) NOT NULL
- updated_at                       DATETIME(6) NOT NULL

http_tls_certificate_versions
- id                               BIGINT PK
- certificate_id                   BIGINT NOT NULL
- version                          INT NOT NULL
- certificate_chain_pem            MEDIUMTEXT NOT NULL
- encrypted_private_key            MEDIUMBLOB NOT NULL
- encryption_nonce                 VARBINARY(12) NOT NULL
- encryption_key_version           VARCHAR(32) NOT NULL
- fingerprint_sha256               CHAR(64) NOT NULL
- subject_dn                       VARCHAR(1024) NOT NULL
- san_entries                      JSON NOT NULL
- not_before                       DATETIME(6) NOT NULL
- not_after                        DATETIME(6) NOT NULL
- created_at                       DATETIME(6) NOT NULL
- UNIQUE(certificate_id, version)
```

私钥加密要求：

- 使用 AES-256-GCM。
- master key 从管理端受控文件或 secret 注入，不写入数据库或 Git。
- `encryption_key_version` 为后续 master key 轮换预留。
- 上传响应、列表接口、日志和异常信息不得返回私钥。

### 8.6 静态站点

```text
static_sites
- id                               BIGINT PK
- name                             VARCHAR(128) NOT NULL UNIQUE
- current_release_id               BIGINT NULL
- status                           TINYINT NOT NULL DEFAULT 1
- created_at                       DATETIME(6) NOT NULL
- updated_at                       DATETIME(6) NOT NULL

static_site_releases
- id                               BIGINT PK
- site_id                          BIGINT NOT NULL
- version                          INT NOT NULL
- original_file_name               VARCHAR(255) NOT NULL
- storage_key                      VARCHAR(512) NOT NULL
- sha256                           CHAR(64) NOT NULL
- archive_size_bytes               BIGINT NOT NULL
- extracted_size_bytes             BIGINT NOT NULL
- file_count                       INT NOT NULL
- status                           VARCHAR(32) NOT NULL
- created_at                       DATETIME(6) NOT NULL
- UNIQUE(site_id, version)
```

管理端 artifact 默认路径：

```text
data/artifacts/sites/<siteId>/<version>/bundle.zip
```

生产多实例管理端后可以替换为 MinIO/S3，但不改变数据库引用和 worker 下载协议。

### 8.7 Route 出站 TLS

不要新增 `SOCKS5_TLS` 组合协议。协议和传输层分开：

```text
RouteDTO
- outboundProxyType                SOCKS5
- outboundTransport                TCP | TLS
- outboundTlsConfig                OutboundTlsConfigDTO
```

V1 DTO：

```json
{
  "verificationMode": "INSECURE_SKIP_VERIFY",
  "serverName": "socks.internal.example",
  "handshakeTimeoutMillis": 5000
}
```

约束：

- `outboundTransport=TLS` 仅允许用于支持 TLS 外层的 TCP 出站连接器，V1 至少实现 SOCKS5。
- `verificationMode` V1 只允许 `INSECURE_SKIP_VERIFY`。
- `serverName` 可为空；配置时用于发送 SNI，但不执行 hostname 验证。
- 管理端和 worker 都必须拒绝未知验证模式，不能静默回退。

## 9. API 设计

### 9.1 HTTP Gateway 管理 API

```text
GET    /api/http-gateways
GET    /api/http-gateways/{id}
POST   /api/http-gateways
PUT    /api/http-gateways/{id}
DELETE /api/http-gateways/{id}
```

创建接口在一个事务中：

1. 校验 worker 存在且支持 `HTTP_GATEWAY_V1`。
2. 校验该 worker 上监听地址和端口没有冲突。
3. 创建 `inbound_configs`，协议固定为 `HTTP_GATEWAY`。
4. 创建 `http_gateways`。
5. 创建初始 Virtual Host、Route 和 Backend 引用。
6. 失效对应 worker 的聚合配置缓存。

Virtual Host、Route 和 Backend 可以提供子资源 API，也可以由 Gateway 详情接口批量保存。V1 推荐分别提供 CRUD，避免复杂表单更新时用一个超大请求覆盖整个 Gateway。

### 9.2 HTTP 证书管理 API

```text
GET    /api/http-certificates
GET    /api/http-certificates/{id}
POST   /api/http-certificates
POST   /api/http-certificates/{id}/versions
POST   /api/http-certificates/{id}/versions/{version}/activate
DELETE /api/http-certificates/{id}
```

上传支持：

- PEM certificate chain + PEM private key。
- PKCS#12/PFX + 临时解析密码。

管理端统一规范化为证书链 PEM 和可加密存储的私钥格式。上传时必须验证：

- 证书与私钥匹配。
- 证书当前有效或至少允许管理员明确上传尚未生效的证书。
- Extended Key Usage 包含 `serverAuth`，或没有限制服务端用途。
- SAN 可解析。
- 私钥类型和长度符合运行环境要求。

删除被 Gateway 或 Virtual Host 引用的证书返回 `409 CONFLICT`，并返回引用摘要，不返回敏感内容。

### 9.3 Static Site 管理 API

```text
GET    /api/static-sites
POST   /api/static-sites
POST   /api/static-sites/{id}/releases
POST   /api/static-sites/{id}/releases/{releaseId}/activate
POST   /api/static-sites/{id}/releases/{releaseId}/rollback
DELETE /api/static-sites/{id}
```

上传 release 时：

1. 只接受 ZIP。
2. 流式写入临时文件并计算 SHA-256。
3. 在受控临时目录安全解压并扫描。
4. 拒绝绝对路径、`..`、符号链接和超限文件。
5. 记录解压后总大小和文件数量。
6. 原子移动到正式 artifact 路径。

### 9.4 Worker 运行时下载 API

```text
GET /api/worker/http-certificates/{certificateId}/versions/{version}
GET /api/worker/static-sites/{siteId}/releases/{releaseId}
```

安全约束：

- 必须使用现有 Worker Token 认证。
- 服务端从 Token 获得 workerId，不接受请求参数声明 workerId。
- 下载证书前验证该版本是否被该 worker 的有效 HTTP Gateway 引用。
- 下载静态包前验证该 release 是否被该 worker 的有效 HTTP Route 引用。
- 下载事件记录 workerId、资源 ID、版本、时间和结果，不记录私钥内容。
- 对无权限资源统一返回 404，避免枚举其他 worker 的资源。

## 10. 聚合配置设计

`AggregateConfigResponse` 增加：

```text
- httpGateways
- httpCertificateRefs
- staticSiteReleaseRefs
```

建议运行时结构：

```json
{
  "version": "3.0",
  "workerId": "vps-01",
  "inbounds": [
    {
      "id": 100,
      "workerId": "vps-01",
      "protocol": "HTTP_GATEWAY",
      "listenIp": "0.0.0.0",
      "port": 443,
      "tlsEnabled": true
    }
  ],
  "httpGateways": [
    {
      "id": 10,
      "inboundId": 100,
      "defaultCertificateId": 20,
      "virtualHosts": [],
      "routes": [],
      "backends": []
    }
  ],
  "httpCertificateRefs": [
    {
      "certificateId": 20,
      "version": 3,
      "fingerprintSha256": "..."
    }
  ],
  "staticSiteReleaseRefs": [
    {
      "siteId": 30,
      "releaseId": 301,
      "version": 5,
      "sha256": "...",
      "archiveSizeBytes": 1234567
    }
  ]
}
```

聚合配置不得包含 HTTP 私钥或静态 ZIP 内容。

依赖闭包扩展为：

```text
worker
  -> HTTP_GATEWAY inbounds
      -> httpGateway
          -> virtualHosts
              -> serverCertificateId
          -> routes
              -> backendId
                  -> egressRouteId
              -> staticSiteId
                  -> currentReleaseId
```

`CanonicalConfigHasher` 必须包含 HTTP Gateway、证书引用版本和静态 release 引用。证书 PEM、私钥和 ZIP 不直接参与 JSON hash；它们通过版本和内容 SHA-256 参与。

## 11. Worker 运行时设计

### 11.1 Initializer 拆分

现有 `AbstractChannelInitializer` 会无条件添加 `RouterService`、`RequestAppendHandler` 和 `ProxyTunnelConnectorHandler`。HTTP Gateway 不能复用该完整 Pipeline。

建议拆分为：

```text
BaseChannelInitializer
  - connection tracking
  - common channel attributes
  - terminal exception handling

TunnelChannelInitializer
  - existing protocol handlers
  - RouterService
  - RequestAppendHandler
  - ProxyTunnelConnectorHandler

HttpGatewayChannelInitializer
  - optional SNI / TLS
  - HttpServerCodec
  - HttpGatewayFrontendHandler
  - HTTP request timeout / idle timeout
```

### 11.2 TCP Dialer 抽取

HTTP Gateway 不应伪造 `ProxyTunnelRequest`。从现有 Outbound Connector 抽取与入站无关的连接请求：

```java
public record TcpDialRequest(
        String targetHost,
        int targetPort,
        RouteConfig egressRoute
) {}

public interface TcpDialer {
    Future<Channel> connect(EventLoop eventLoop, TcpDialRequest request);
}
```

现有隧道协议根据 `ProxyTunnelRequest` 构建 `TcpDialRequest`；HTTP Backend 根据目标地址和 `egressRouteId` 构建相同请求。

未知 RoutePolicy、未知 outbound protocol 或不完整 TLS 配置必须失败，不能静默回退 DIRECT。

### 11.3 SOCKS5 over TLS Pipeline

连接步骤：

```text
TCP connect outboundProxyHost:outboundProxyPort
  -> install client SslHandler with insecure trust manager
  -> wait handshakeFuture success
  -> SOCKS5 greeting
  -> optional username/password authentication
  -> SOCKS5 CONNECT targetHost:targetPort
  -> return raw logical target channel
```

实现要求：

- 使用 Netty `InsecureTrustManagerFactory.INSTANCE`。
- `serverName` 非空时通过 `newHandler(alloc, serverName, port)` 发送 SNI。
- 不设置 hostname endpoint identification。
- TLS 握手超时单独计时和分类。
- SOCKS greeting 不得在 TLS 握手成功前发送。
- 原始目标为域名时，默认把域名发送给 SOCKS 服务远程解析，不在 VPS 本地提前解析。

### 11.4 HTTP Backend 连接池

图片、JS 和 CSS 会产生多个并发请求。每个请求都重新执行 TCP、TLS 和 SOCKS5 握手会导致明显延迟，因此连接池属于 V1。

连接池 key：

```text
targetHost + targetPort
+ egressRouteId
+ route runtime version
+ outbound TLS configuration hash
```

池中连接已经完成 SOCKS5 CONNECT，并处于可发送 HTTP/1.1 请求的状态。

不得归还连接池的场景：

- 上游返回 `Connection: close`。
- HTTP 解码失败。
- 请求、响应或连接超时。
- Channel inactive 或写入失败。
- 请求被取消且剩余响应无法安全排空。
- 协议 Upgrade；V1 直接拒绝 Upgrade，不进入池。

### 11.5 HTTP 请求处理

每个请求创建独立 `HttpRequestContext`，不能复用连接级 `ProxyContext` 表示多个 keep-alive 请求。

处理步骤：

```text
HttpRequest
  -> normalize Host and URI
  -> select Virtual Host
  -> select longest Path Prefix Route
  -> enforce request limits
  -> execute local action or acquire Backend channel
  -> rewrite headers and optional path prefix
  -> stream HttpContent
  -> stream upstream response
  -> request-level access log
```

客户端 keep-alive 上的请求必须串行处理。V1 不支持 HTTP pipelining；前一响应结束前暂停读取下一请求或返回明确错误。

## 12. HTTP 路由语义

### 12.1 Virtual Host 匹配

匹配顺序：

```text
精确域名
-> 最长通配后缀
-> 显式 catch-all `*`
-> 未匹配返回 421
```

TLS 请求先通过 SNI 选择证书，再通过 HTTP Host 选择 Virtual Host。默认拒绝 SNI 和 Host 指向不同 Virtual Host 的请求，避免意外域名前置；后续如有明确需求再增加兼容开关。

### 12.2 Path 匹配

匹配顺序：

```text
最长 pathPrefix
-> priority 数值较大者
-> id 较小者作为稳定兜底
```

Path 匹配只使用 URI path，不包含 query。

### 12.3 Path 改写

`stripPrefix=false`：

```text
/api/users -> /api/users
```

`stripPrefix=true` 且 prefix 为 `/api`：

```text
/api/users -> /users
/api       -> /
```

Query string 原样保留。

### 12.4 Header 处理

转发前必须移除：

- `Connection` 以及其声明的 hop-by-hop headers。
- `Proxy-Connection`。
- `Keep-Alive`。
- `Transfer-Encoding` 的连接级语义。
- 客户端伪造且不应信任的 forwarded headers。

统一设置：

```text
X-Forwarded-For
X-Forwarded-Proto
X-Forwarded-Host
X-Request-Id
```

Host 行为：

- `preserveHost=true`：保留原始 Host。
- `preserveHost=false`：使用 `upstreamHost`；为空时使用 Backend `targetHost:targetPort`。

## 13. HTTP 服务端证书管理

### 13.1 上传与版本

证书管理页面只面向 HTTP 服务端身份，不出现 SOCKS 信任包或出站证书概念。

首次上传创建逻辑证书和版本 1；后续替换创建新版本并更新 `current_version`。管理员可以重新激活仍被保留的旧版本完成回滚。Virtual Host 始终引用逻辑 certificate ID。

### 13.2 多证书 SNI

一个 Gateway 的运行时映射：

```text
www.example.com       -> certificate 10 version 3
api.example.com       -> certificate 11 version 1
*.internal.example    -> certificate 12 version 2
default               -> certificate 10 version 3
```

worker 为每个实际证书版本构建 `SslContext`，再构建不可变 SNI mapping。配置或证书版本变化时先构建新 mapping，成功后只对新连接切换；现有 TLS 连接继续使用原 Context 直到关闭。

V1 一个域名模式只使用一个活动证书，不实现同域名 RSA/ECDSA 双证书自动选择。

### 13.3 Worker 证书缓存

worker 默认路径：

```text
data/runtime/http-certificates/<certificateId>/<version>/
  fullchain.pem
  private-key.pem
  metadata.json
```

要求：

- 临时目录下载并验证后原子移动。
- 私钥和包含私钥的目录设置 owner-only 权限。
- 校验返回 fingerprint 和 aggregate ref 一致。
- last-known-good JSON 只保存证书引用，不保存 PEM 私钥。
- worker 离线重启时使用本地缓存的最后成功证书版本。
- 取消所有引用后延迟清理旧版本，至少保留当前运行版本和上一成功版本。

### 13.4 证书过期与告警

管理端至少提供：

- 已过期。
- 30 天内到期。
- 7 天内到期。
- SAN 不覆盖当前引用域名。
- worker 未成功下载或应用最新版本。

过期证书不自动删除。是否允许继续运行由管理员处理，避免自动操作导致服务立即不可用。

## 14. 静态站点托管

### 14.1 发布模型

静态站点和发布版本分离。Route 引用 `static_site_id`，运行时使用该站点的 `current_release_id`。

激活新 release 后只更新站点当前版本，不修改 HTTP Route。

### 14.2 Worker 本地目录

```text
data/runtime/static-sites/<siteId>/<releaseId>/
  index.html
  static/
  assets/
  release-metadata.json
```

worker 至少保留当前版本和上一个成功版本。磁盘清理不能删除正在被运行时快照引用的目录。

### 14.3 文件安全

上传和 worker 解压都必须防御：

- ZIP entry 绝对路径。
- `..` 路径穿越。
- 重复 URL 解码后的路径穿越。
- 符号链接和硬链接逃逸。
- 压缩炸弹。
- 单文件、总大小和文件数量超限。
- 文件名过长和不可接受控制字符。

worker 请求路径必须规范化后确认仍位于 release 根目录内。禁止目录列表。

### 14.4 HTTP 行为

- 只允许 GET 和 HEAD，其他方法返回 405。
- 根据文件扩展名设置白名单 MIME 类型，未知类型使用 `application/octet-stream`。
- ETag 使用 release hash 和文件 metadata 生成。
- 支持 `If-None-Match` 返回 304。
- `index.html` 默认 `Cache-Control: no-cache`。
- 带内容 hash 的 JS/CSS 默认 `public, max-age=31536000, immutable`。
- 普通图片和其他资源默认短缓存，可由站点配置覆盖。
- 文件通过 `ChunkedWriteHandler` 或等价方式流式发送，不整体加载到堆内存。
- V1 不要求 Range；后续如果托管音视频再增加。

### 14.5 SPA Fallback

启用 `spaFallback` 时，只对满足以下条件的缺失路径返回 `index.html`：

- 方法为 GET 或 HEAD。
- `Accept` 包含 `text/html`。
- 请求路径最后一段没有文件扩展名。

缺失的 `.js`、`.css`、图片和字体必须返回 404，不能返回 HTML。

## 15. 热更新与失败处理

### 15.1 Listener 与运行时快照分离

定义 `ListenerKey`：

```text
workerId + listenIp + port + protocol
```

- `ListenerKey` 变化时重启 Netty Server。
- Virtual Host、Route、Backend、证书版本、静态 release 或超时变化时更新运行时快照，不重启监听端口。

### 15.2 两阶段应用

```text
Prepare
  - validate config references
  - download missing dependencies
  - build SslContext and SNI mapping
  - build RouteTable
  - initialize Backend pools

Commit
  - atomic reference swap
  - mark appliedConfigHash
  - persist last-known-good references
```

Prepare 失败不进入 Commit。

### 15.3 失败分类

至少区分：

```text
HTTP_CERT_DOWNLOAD_FAILED
HTTP_CERT_DECRYPT_FAILED
HTTP_CERT_FINGERPRINT_MISMATCH
HTTP_CERT_INVALID
STATIC_RELEASE_DOWNLOAD_FAILED
STATIC_RELEASE_HASH_MISMATCH
STATIC_RELEASE_EXTRACT_FAILED
STATIC_RELEASE_DISK_FULL
HTTP_ROUTE_INVALID
HTTP_BACKEND_UNAVAILABLE
OUTBOUND_TLS_HANDSHAKE_FAILED
SOCKS_AUTH_FAILED
SOCKS_CONNECT_FAILED
LISTENER_BIND_FAILED
```

对客户端：

- 无匹配 Host：421。
- 无匹配 Route：404。
- 请求超限：413 或 431。
- Backend 建连或 SOCKS 失败：502。
- 上游响应头超时：504。
- 无可用连接且资源耗尽：503。

## 16. 安全设计

### 16.1 控制面

- HTTP 证书私钥只在管理员上传、加密存储、授权 worker 下载三个路径中出现。
- 管理员列表和详情 API 永不返回私钥。
- Worker secret 和 artifact API 使用 Worker Token，并按实际依赖授权。
- Authorization、证书私钥、PKCS#12 密码不得进入访问日志或异常摘要。
- master key 文件和 worker token 文件不得提交 Git。
- 静态 artifact 不允许被管理端当作可执行内容加载。

### 16.2 HTTP 数据面

- 限制 initial line、header、请求体和空闲时间。
- 拒绝冲突的 `Content-Length` 与 `Transfer-Encoding`。
- 限制每 IP 并发连接和每 Virtual Host/Route 请求速率。
- 不信任客户端传入的 `X-Forwarded-*`。
- 默认不记录 Authorization、Cookie 和完整 query string。
- 静态文件路径在 URL 解码和规范化后再次校验根目录边界。

### 16.3 SOCKS TLS 风险

`INSECURE_SKIP_VERIFY` 只能防止被动监听，不能防止主动中间人攻击、DNS 劫持或路由劫持。管理页面、Route 详情和 worker 日志必须显示该风险。

V1 接受该风险是明确的范围取舍，后续严格验证需要单独设计信任证书和出站 TLS Profile，不在本需求中预留半实现行为。

## 17. 日志与指标

### 17.1 请求级日志

HTTP keep-alive 连接可能包含多个请求，必须逐请求记录日志。可以扩展现有 `AccessLog` 增加 nullable HTTP 字段：

```text
logType                    TCP | HTTP
httpMethod
httpHost
httpPath                   不含敏感 query
httpStatus
virtualHostId
httpRouteId
backendId
staticSiteId
staticReleaseId
tlsVersion
tlsCipher
upstreamConnectDurationMs
upstreamTtfbMs
```

原 `routePolicyId` 可以继续记录 Backend 使用的 Egress Route ID。

### 17.2 Worker 指标

建议增加：

```text
httpActiveConnections
httpInflightRequests
httpRequestsTotal
httpResponses2xx/4xx/5xx
backendPoolActive/Idle/Pending
outboundTlsHandshakeFailures
socksHandshakeFailures
staticBytesServed
staticReleaseCount
certificateCacheCount
```

### 17.3 管理端状态

管理端需要同时展示：

- desired config hash。
- fetched config hash。
- applied config hash。
- 当前 HTTP Gateway 数量。
- 当前证书版本和 worker 应用版本。
- 当前 static release 和 worker 本地版本。
- 最近一次应用错误。

## 18. 前端设计

左侧导航新增：

```text
HTTP 网关
静态站点
HTTP 证书
```

现有页面继续保留：

```text
入站配置
路由策略
```

### 18.1 HTTP 网关页面

列表展示：

- 名称、worker、监听地址、端口、TLS、Virtual Host 数量、Route 数量、状态。

详情建议使用以下视图：

- 基础监听配置。
- Virtual Hosts。
- HTTP Routes。
- Backends。

创建 Gateway 时由前端调用专用 API，一次性创建底层 Inbound 和 Gateway，不暴露两个资源的创建顺序。

### 18.2 HTTP 证书页面

展示：

- 名称、当前版本、Subject、SAN、指纹、有效期、状态、引用数。
- 被哪些 Gateway/Virtual Host 和 worker 引用。
- 上传新版本、查看应用状态、删除。

证书下拉只显示启用、可用且 SAN 满足域名的证书。Gateway 默认允许管理员选择不完全覆盖的证书时应明确阻止保存，而不是仅警告。

### 18.3 静态站点页面

展示：

- 当前 release、包大小、解压大小、文件数量、SHA-256、引用 Route、worker 应用状态。
- 上传、激活、回滚、删除未引用版本。

### 18.4 Route 页面

现有 Egress Route 表单增加：

```text
出站协议：SOCKS5
传输安全：TCP | TLS
验证方式：跳过证书验证（V1 唯一选项）
Server Name：可选
TLS 握手超时
```

启用 TLS 时显示明确的风险提示。

## 19. 兼容性设计

### 19.1 Worker Capability

worker 注册或状态上报增加能力列表：

```text
HTTP_GATEWAY_V1
HTTP_SERVER_CERTIFICATE_V1
STATIC_SITE_V1
SOCKS_TLS_INSECURE_V1
```

管理端只允许把对应配置分配给具备能力的 worker。

### 19.2 聚合 DTO 兼容

在管理端开始下发 `version=3.0` 前，先发布一个兼容版本，使旧 worker 对 AggregateConfigResponse 未知字段使用 ignore-unknown 语义。否则即使 `httpGateways=[]`，旧 worker 也可能因新字段反序列化失败。

### 19.3 Factory 失败语义

`ProxyServerFactory` 遇到未知协议必须抛出明确异常并触发配置回滚，不能返回 null。

### 19.4 Generic Inbound API

- 现有 SOCKS、CONNECT、透明代理等创建和编辑行为不变。
- Generic Inbound API 收到 `HTTP_GATEWAY` 创建或编辑请求返回 400，并提示使用 HTTP Gateway API。
- 删除底层 HTTP Gateway Inbound 必须由 HttpGatewayService 处理级联和引用检查。

## 20. 实施范围

### 20.1 common

新增：

- `ProtocolType.HTTP_GATEWAY`。
- `OutboundTransportType`。
- `OutboundTlsVerificationMode.INSECURE_SKIP_VERIFY`。
- `OutboundTlsConfigDTO`。
- `HttpGatewayDTO`、`HttpVirtualHostDTO`、`HttpRouteDTO`、`HttpBackendDTO`。
- `HttpCertificateRefDTO`。
- `StaticSiteReleaseRefDTO`。

调整：

- `RouteDTO` 增加出站传输层字段。
- `AggregateConfigResponse` 增加 HTTP Gateway 和资源引用。
- `AccessLog` 增加请求级 HTTP 字段。

### 20.2 control-manager

新增：

- HTTP Gateway、Virtual Host、Route、Backend entity/repository/service/controller。
- HTTP certificate entity/version/repository/service/controller。
- 私钥加密组件和 master key 配置。
- Static Site、Release entity/repository/service/controller。
- Worker certificate/artifact download controller。
- artifact filesystem store。

调整：

- `WorkerConfigDependencyResolver` 扩展 HTTP 依赖闭包。
- `AggregateConfigService` 下发最小 HTTP 配置和引用。
- `CanonicalConfigHasher` 包含新资源引用。
- `WorkerTokenAuthenticationFilter` 保护新的 worker 下载接口。
- Route 校验支持 SOCKS TLS insecure 配置。

### 20.3 proxy-worker

新增：

- `HttpGatewayServer` 和 `HttpGatewayChannelInitializer`。
- SNI `SslContext` registry。
- `HttpRouteTable` 和请求级 Context。
- HTTP Frontend/Backend handler。
- Backend connection pool。
- HTTP certificate cache/client。
- Static release cache/client 和 static file handler。
- SOCKS TLS handshake stage。

重构：

- Initializer 分离公共、隧道和 HTTP Gateway Pipeline。
- 抽取通用 `TcpDialer`。
- `ProxyContext` 区分 Listener 变更和运行时快照变更。
- last-known-good 启动时同时验证证书和静态资源缓存。

### 20.4 frontend

新增：

- HTTP Gateway 页面和类型/API。
- HTTP 证书页面和上传流程。
- Static Site 页面和发布流程。

调整：

- 路由策略表单支持 SOCKS TLS insecure。
- 入站列表展示 HTTP Gateway 监听器并跳转专用页面。
- Worker 页面展示 capability 和资源应用状态。

## 21. 推荐实施阶段

### 阶段 0：兼容准备

- Aggregate DTO ignore unknown。
- worker capability 上报和管理端校验。
- Factory 未知协议 fail closed。

### 阶段 1：出站 Dialer 与 SOCKS TLS

- 抽取 `TcpDialer`。
- SOCKS5 增加 TLS 外层和 handshake gate。
- Route 管理增加 `TCP | TLS`。
- 补连接失败分类和单测。

### 阶段 2：HTTP Gateway 代理能力

- 数据表和管理 API。
- HTTP/1.1 Pipeline、Host/Path 路由和 Backend 转发。
- Backend 连接池和请求级日志。
- 先用本地测试证书完成单证书监听验证。

### 阶段 3：HTTP 证书管理

- 管理端上传、加密存储、版本管理和 worker 下载。
- worker 证书缓存、SNI 多证书和热切换。
- 到期与应用状态展示。

### 阶段 4：静态站点

- artifact store、上传扫描、worker 下载和本地缓存。
- STATIC Route、SPA fallback、缓存头和回滚。

### 阶段 5：前端与运维闭环

- HTTP Gateway、HTTP 证书、静态站点页面。
- desired/applied 状态、错误提示和 worker capability 展示。
- 完整端到端验收。

## 22. 测试策略

### 22.1 control-manager

- HTTP Gateway 创建时同时创建 Inbound，并在失败时事务回滚。
- 同一 worker 监听端口冲突校验。
- Generic Inbound API 拒绝直接管理 HTTP_GATEWAY。
- Virtual Host 域名规范化、冲突和证书 SAN 覆盖校验。
- HTTP Route 动作字段组合校验。
- 证书与私钥匹配、EKU、SAN、版本递增和引用删除保护。
- 私钥数据库内容不是明文 PEM。
- worker 只能下载自己引用的证书和静态 release。
- ZIP Slip、符号链接、压缩炸弹和大小限制测试。
- 聚合配置只包含依赖闭包，并且 hash 随证书版本和 release 变化。

### 22.2 proxy-worker 单测

- SNI 精确域名、通配域名和默认 Context 选择。
- HTTP Host 和 Path 匹配优先级。
- Header 清理、Forwarded Header、Host 和 Path 改写。
- 流式请求体计数和超限处理。
- SOCKS greeting 只在 TLS handshake 成功后发送。
- TLS 跳过验证可以连接自签 SOCKS 服务。
- SOCKS 域名目标使用远程解析地址类型。
- Backend 连接池归还、淘汰和配置版本隔离。
- 静态路径穿越、MIME、ETag、304、HEAD 和 SPA fallback。
- 证书和 static release 下载失败时保持旧快照。
- ByteBuf 引用计数和异常路径无泄漏。

### 22.3 集成测试

建立测试拓扑：

```text
HTTPS client
  -> HTTP Gateway worker
  -> TLS-wrapped SOCKS5 test server
  -> HTTP upstream test server
```

验证：

- `/api` 经 SOCKS TLS 到达目标 HTTP Server。
- `/` 从 worker 静态目录返回。
- 多域名 SNI 返回不同服务端证书。
- 多个连续或并发 API 请求复用 Backend 连接池；本地静态资源不建立 Backend 连接。
- 配置更新不重启 443 Listener。
- 上传无效新证书时旧证书继续服务。
- 发布损坏静态包时旧 release 继续服务。
- 管理端停止后 worker 重启能使用 last-known-good 引用和本地资源缓存。

### 22.4 安全测试

- HTTP request smuggling 基础用例。
- 超大请求行、Header 和 Body。
- Slowloris 和 idle timeout。
- Host/SNI 不一致。
- 静态文件双重编码路径穿越。
- 未授权 worker 枚举证书和 artifact。
- 日志不包含私钥、Worker Token、Authorization 或 Cookie。

## 23. 验收标准

满足以下条件视为 V1 完成：

1. 管理员可以创建属于指定 worker 的 HTTP Gateway，并配置多个 Virtual Host。
2. 管理员可以上传 HTTP 服务端证书，证书私钥加密落库且不出现在普通 API。
3. worker 可以按 SNI 使用多个证书完成 HTTPS 握手。
4. `/api` 请求可以通过 SOCKS5 over TLS 到达目标明文 HTTP Server。
5. SOCKS TLS 可以连接自签服务端，且界面明确显示跳过验证风险。
6. 静态前端 ZIP 可以上传、发布、由 worker 下载并直接托管。
7. SPA 页面、JS、CSS 和图片返回正确 MIME、ETag 和缓存头。
8. HTTP 请求使用请求级日志，能区分静态命中和 Backend 转发。
9. 配置、证书或静态 release 更新失败时旧版本继续可用。
10. 旧 worker 不会因为聚合配置新增字段而反序列化失败，也不能被分配不支持的功能。

## 24. 风险与后续演进

### 24.1 已接受风险

- SOCKS TLS 不验证服务端证书，存在中间人风险。
- V1 单 Backend，没有主动健康检查和自动故障转移。
- HTTP/1.1 上游连接池不能多路复用，并发能力依赖池大小。
- 管理端持有 HTTP 服务端私钥，管理端 master key 和数据库备份需要纳入运维保护。

### 24.2 后续候选能力

- SOCKS TLS `STRICT + CUSTOM_CA`、证书固定和 mTLS。
- 上游 HTTPS，并在 SOCKS tunnel 内增加目标 TLS。
- 多 Backend、权重、健康检查和仅幂等请求重试。
- WebSocket、HTTP/2 和 HTTP/3。
- 预压缩 Brotli/Gzip 静态资源。
- Range、音视频和大文件支持。
- MinIO/S3 artifact store。
- ACME 自动签发和服务端证书自动轮换。
- 静态资源按需回源缓存和显式 purge；该方向属于真正 CDN 能力，应单独立项。
