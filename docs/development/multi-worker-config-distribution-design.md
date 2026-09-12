# 多 Proxy Worker 配置分发与认证设计

## 1. 文档状态

- 状态：可实施
- 设计基线日期：2026-07-25
- 数据库基线：MySQL Flyway 最新版本为 `V19`，本需求从 `V20` 开始
- 部署基线：`deploy/docker/docker-compose.control.yml`、`deploy/docker/docker-compose.worker.yml`
- 目标版本：按第 21 节的提交批次实施
- 涉及模块：`common`、`control-manager`、`proxy-worker`、`frontend`
- 核心范围：一个 `control-manager` 管理多个 `proxy-worker`，按 worker 下发不同的入站及其依赖配置

本文中的字段名、配置项、容器路径和发布顺序均为实施约束。实现过程中如需改变 API 或数据模型，必须先更新本文并说明兼容方案，不能由各模块自行解释。

### 1.1 与现有设计的关系

本文是 `worker-polling-control-channel-design.md` 的后续演进设计。原文中的单 worker 实现、主动 poll 方向和基础指标采集继续保留；涉及“固定 `workerId=default`”“worker 接口匿名白名单”“任务不绑定 worker”的内容，以本文为准。

## 2. 背景

当前系统按单 worker 模式运行：

- 所有启用的入站、用户、路由、限流和规则集被聚合成一份全局配置。
- 所有 worker 调用同一个 `/api/config/aggregate` 接口并获得相同内容。
- 配置缓存只有一个固定 key。
- worker 心跳虽然已经包含 `workerId`，但任务队列没有目标 worker，状态查询也主要面向 `default` worker。
- 配置拉取、日志上报和 worker poll 接口当前不校验 worker 身份。

现有 worker 的运行时装载逻辑已经以入站配置为根：`InboundRouteBinding` 记录 `userIds` 和 `routeIds`，worker 根据这些引用为每个入站构建用户认证索引和路由序列。因此，本设计延续现有模型，不给用户、路由和规则集直接增加 `worker_id`，而是由入站决定哪些全局资源需要部署到指定 worker。

## 3. 已确认决策

### 3.1 节点与配置关系

- 一个 `control-manager` 管理多个 `proxy-worker`。
- 一个 worker 可以拥有多个入站。
- 一个入站只能归属于一个 worker。
- `worker_id` 只作为入站配置的归属字段。
- 用户、路由和规则集继续作为全局可复用资源。
- worker 实际获得的用户、路由和规则集由其入站引用关系计算得到。

### 3.2 安全模型

- 管理端只通过 HTTPS 向 worker 提供接口。
- `control-manager` 每次启动时生成临时自签名证书，不依赖宿主机证书、P12 或口令文件。
- worker 保持 HTTPS 传输加密，但刻意不校验证书链或主机名，以接受管理端重启后变化的证书。
- worker 代理入站所使用的证书仍是独立凭据，可按需挂载到对应 worker。
- 每个 worker 使用独立 Worker Token 进行身份认证。
- 服务端根据 Token 确定 worker 身份，不信任请求参数或请求体中的 `workerId`。
- 此模式不抵抗网络路径上的中间人攻击，只能部署在可信 LAN/VPN；如需身份校验，必须恢复固定证书校验方案。

### 3.3 配置资源共享语义

- 同一用户、路由或规则集可以被多个 worker 的入站引用。
- 修改共享资源会影响所有引用它的 worker。
- 未被任何入站引用的资源不会下发给任何 worker。
- 删除资源前必须检查入站或路由引用。
- 管理端需要展示资源被哪些 worker 使用，并在编辑共享资源时提示影响范围。

## 4. 目标

- 支持管理多个 worker，并分别维护其入站配置。
- 根据 worker 的入站引用关系生成最小配置集合。
- 保证 worker A 无法获取 worker B 的入站配置。
- 使用 HTTPS 保护配置、Token、日志和心跳数据的传输。
- 使用每个 worker 唯一的长期 Token 实现节点身份认证、授权、创建和删除。
- 支持按 worker 计算配置 hash、缓存和同步状态。
- 支持节点定向任务、节点状态列表和日志归属。
- 管理端不可用时，worker 可以使用最后一次成功配置继续运行。

## 5. 非目标

- 不实现不同组织或管理员之间的多租户隔离。
- 不为用户、路由和规则集建立 worker 私有副本模型。
- 不引入配置空间或配置组。
- 不实现 control-manager 主动连接 worker。
- 不自研 TLS 协议或请求签名算法。
- 不要求第一阶段将 JSON 引用立即拆成关系表。

## 6. 总体架构

```text
                         HTTPS + Worker Token
proxy-worker A  ------------------------------------+
                                                      |
proxy-worker B  ------------------------------------+ |
                                                    | |
                                                    v v
                                             control-manager
                                                    |
                         +--------------------------+--------------------+
                         |                          |                    |
                    worker registry          inbound configs      global resources
                         |                    (worker_id)          users/routes/rule sets
                         |                          |                    ^
                         |                          +------ references --+
                         |
                         +---- status / tasks / logs
```

配置分发采用“入站根配置闭包”：

```text
worker
  -> inbounds
      -> inboundRouteBindings.userIds
      -> inboundRouteBindings.routeIds
          -> route.rules[RULE_SET].value
              -> ruleSet.ruleKey
      -> fallbackRouteId
      -> anonymousUserId
```

## 7. 数据模型

### 7.1 Worker 主表

新增 `proxy_worker`：

```text
proxy_worker
- worker_id          VARCHAR(128) PK
- display_name       VARCHAR(128) NOT NULL
- enabled            TINYINT(1) NOT NULL DEFAULT 1
- description        VARCHAR(255) NULL
- token_hash         BINARY(32) NULL UNIQUE
- created_at         DATETIME(6) NOT NULL
- updated_at         DATETIME(6) NOT NULL
```

约束：

- `worker_id` 创建后不可修改，用作稳定节点标识。
- `worker_id` 只允许小写字母、数字、点、下划线和连字符，正则为 `^[a-z0-9][a-z0-9._-]{0,127}$`。
- `display_name` 可以修改，仅用于界面展示。
- 禁用 worker 后拒绝其配置、日志、poll 请求，但保留历史数据。
- `token_hash IS NULL` 表示该 worker 当前没有可用 Token。
- 删除 worker 前必须确认没有归属该 worker 的入站配置。

### 7.2 Worker Token

每个 worker 最多维护一个长期 Token，不新增 `worker_credential` 表，不设计过期时间、状态字段或多 Token 并存。

Token 格式固定为：

```text
hpw_<base64urlRandomSecret>
```

- 随机部分使用 32 字节安全随机数并采用无填充 Base64URL 编码。
- 数据库不保存完整 Token。
- 服务端保存 `SHA-256(fullToken)` 的 32 字节结果，并通过 `proxy_worker.token_hash` 唯一索引定位 worker。Token 具有 256 bit 随机熵，不再引入额外 pepper secret。
- Token 只在创建时展示一次。
- 创建 worker 时自动生成 Token；Token 删除后允许重新创建一个新 Token。
- worker 已存在 Token 时再次创建返回 `409 CONFLICT`，不提供覆盖或并存轮换。
- 删除 Token 时将 `token_hash` 置空，旧 Token 立即失效。

### 7.3 入站配置

在 `inbound_configs` 增加：

```text
worker_id          VARCHAR(128) NOT NULL
fallback_route_id  BIGINT NULL
anonymous_user_id  BIGINT NULL
```

其中：

- `worker_id` 表示入站运行在哪个 worker。
- `fallback_route_id` 替代通过固定名称查找“兜底直连路由规则”。
- `anonymous_user_id` 替代通过固定名称查找“Anonymous user”。
- `fallback_route_id` 和 `anonymous_user_id` 允许为空，由协议和业务规则决定是否必需。

原全局监听唯一约束：

```text
UNIQUE(listen_ip, port)
```

调整为：

```text
UNIQUE(worker_id, listen_ip, port)
INDEX(worker_id, status)
```

不同 worker 运行在不同主机上，因此允许使用相同监听地址和端口。

### 7.4 Worker 状态

保留现有 `worker_status`，扩展配置应用状态：

```text
worker_status
- worker_id
- hostname
- started_at
- last_seen_at
- fetched_config_hash
- applied_config_hash
- apply_success
- apply_error
- applied_at
- uptime_seconds
- heap_used_bytes
- heap_max_bytes
- running_inbound_count
- active_connection_count
```

语义：

- `fetched_config_hash`：worker 最近成功下载的配置 hash。
- `applied_config_hash`：worker 最近成功应用并启动的配置 hash。
- `apply_success`：最近一次配置应用是否成功。
- `apply_error`：最近一次失败摘要，不保存敏感配置内容。

为支持首个版本回滚，数据库物理列暂时保留现有名称 `last_config_hash`，Java 字段和 API 语义按 `fetchedConfigHash` 使用；稳定一个发布周期后再执行物理列重命名。

### 7.5 Worker 任务

在 `worker_tasks` 增加：

```text
worker_id VARCHAR(128) NOT NULL
```

所有任务必须指定目标 worker。poll 查询只能返回认证 worker 自己的任务。

第一阶段固定沿用现有“领取即消费”行为；任务可靠性状态机作为独立后续改造。

### 7.6 日志归属

访问日志和认证日志增加：

```text
worker_id VARCHAR(128)
```

`worker_id` 由 `control-manager` 根据 Worker Token 对应的认证主体写入，忽略 worker 上传对象中的同名字段，避免身份伪造。

ClickHouse 查询、列表 DTO、看板和聚合接口需要支持按 `worker_id` 筛选。

## 8. 配置闭包计算

### 8.1 输入

配置聚合服务只接收认证后的 `workerId`：

```java
AggregateConfigResponse getAggregateConfig(String workerId)
```

不允许通过匿名请求或任意查询参数选择 worker。

### 8.2 计算步骤

```text
1. 查询 workerId 对应且启用的入站配置。
2. 遍历 inboundRouteBindings，收集 userIds 和 routeIds。
3. 加入每个入站显式配置的 anonymousUserId 和 fallbackRouteId。
4. 批量查询被引用且启用的用户。
5. 批量查询被引用且启用的路由。
6. 遍历路由规则，收集 conditionType=RULE_SET 的 ruleSetKey。
7. 批量查询已启用且已发布的规则集及 payload。
8. 按稳定顺序组织列表并计算 configHash。
9. 返回该 worker 的聚合配置。
```

新增统一依赖解析服务：

```text
WorkerConfigDependencyResolver
- resolveUserIds(inbounds)
- resolveRouteIds(inbounds)
- resolveRuleSetKeys(routes)
- resolveAffectedWorkerIds(resourceType, resourceId)
- validateInboundReferences(inbound)
```

该服务同时用于配置聚合、保存校验、删除检查、影响范围展示和测试，避免多处重复解析 JSON。

### 8.3 引用异常处理

管理端保存入站时必须校验：

- `userIds` 全部存在。
- `routeIds` 全部存在。
- `fallbackRouteId` 存在且路由启用状态符合要求。
- `anonymousUserId` 存在且用户启用状态符合要求。
- 路由引用的 `ruleSetKey` 存在。
- 启用中的路由只能引用已启用且已发布的规则集。

不能继续采用 worker 端遇到缺失 ID 后静默跳过的方式。聚合阶段如果检测到不完整引用，应拒绝生成新配置、记录错误并保持 worker 的上一份成功配置。

### 8.4 限流配置

限流当前不属于本次改造的核心功能，且 worker 端尚未完整装载使用。为保持 DTO 兼容，本次保留 `rateLimits` 字段，并按以下规则裁剪：

- `GLOBAL` 类型对所有 worker 下发。
- `USERS` 类型仅在其 `userIds` 与当前 worker 用户集合有交集时下发。
- 下发前将 `userIds` 裁剪为当前 worker 实际拥有的用户 ID。

后续正式启用限流功能时，需要重新确认 `GLOBAL` 是全系统全局还是每个 worker 独立生效。

## 9. 配置 Hash 与缓存

### 9.1 缓存 key

聚合配置缓存按 worker 隔离：

```java
@Cacheable(value = "aggregateConfig", key = "#workerId")
```

Worker A 和 Worker B 分别维护独立 ETag。修改 A 的入站后，B 的配置 hash 不应变化。

### 9.2 缓存失效

第一阶段继续使用正确性优先策略：

- 修改入站、用户、路由或规则集时执行 `allEntries=true`。
- 各 worker 下一次请求时重新计算自己的配置闭包。
- 只有实际内容变化的 worker 获得新 hash，其他 worker 仍可继续使用原 ETag。

后续如果 worker 数量增长，再通过 `WorkerConfigDependencyResolver` 计算受影响 worker，定向清除缓存。

### 9.3 Hash 稳定性

计算 hash 前必须保证：

- 入站、用户、路由、限流和规则集分别按稳定字段排序。
- JSON map 和集合采用确定性序列化。
- `generatedAt` 等运行时字段不参与 hash。
- 同一配置内容在不同请求中产生相同 hash。

新增 `CanonicalConfigHasher`，使用 Jackson 的稳定属性/Map key 排序序列化一个不含 `generatedAt`、`configHash` 的快照后计算 SHA-256。只对无语义顺序的集合排序；`routeIds`、路由规则和其他代表匹配优先级的列表必须保留业务顺序。

## 10. Worker API

### 10.1 配置拉取

保留现有路径，改为强制 Worker Token：

```http
GET /api/config/aggregate
Authorization: Bearer hpw_<base64urlRandomSecret>
If-None-Match: "<configHash>"
```

服务端从认证主体获得 `workerId`，不接受 `workerId` 查询参数。

响应增加：

```json
{
  "workerId": "shanghai-01",
  "version": "2.0",
  "generatedAt": "2026-07-25T12:00:00",
  "configHash": "...",
  "inbounds": [],
  "users": [],
  "routes": [],
  "ruleSets": [],
  "rateLimits": []
}
```

### 10.2 Worker Poll

```http
POST /api/worker/poll
Authorization: Bearer hpw_<base64urlRandomSecret>
```

- 请求体中的 `workerId` 字段逐步废弃。
- 兼容期内如请求体仍携带 `workerId`，必须与认证主体一致，否则返回 `403`。
- 只更新认证 worker 的状态。
- 只领取认证 worker 的任务。
- 只允许上报属于认证 worker 的任务结果。

### 10.3 日志上报

```http
POST /api/logs/access
POST /api/logs/auth
Authorization: Bearer hpw_<base64urlRandomSecret>
```

管理端对日志批次增加大小限制、单条字段长度限制和请求频率限制，避免合法 Token 被滥用后拖垮日志存储。

## 11. Worker Token 认证

### 11.1 安全过滤器

新增独立的 `WorkerTokenAuthenticationFilter`，只处理 worker 接口：

```text
/api/config/aggregate
/api/worker/poll
/api/logs/access
/api/logs/auth
```

认证成功后创建 `WorkerPrincipal`：

```text
WorkerPrincipal
- workerId
- displayName
```

管理员 JWT 与 Worker Token 使用不同的认证主体和授权规则，不能互相替代。

授权矩阵固定为：

| 路径 | 方法 | 认证主体 |
| --- | --- | --- |
| `/api/config/aggregate` | GET | Worker Token |
| `/api/worker/poll` | POST | Worker Token |
| `/api/logs/access` | POST | Worker Token |
| `/api/logs/auth` | POST | Worker Token |
| `/api/workers/**` | 全部 | 管理员 JWT |
| `/api/inbounds/**` 等配置管理接口 | 全部 | 管理员 JWT |
| `/api/logs/**` 查询接口 | GET | 管理员 JWT |

`/api/config/hash` 不再匿名开放；若保留该接口，则只允许管理员 JWT。`WorkerTokenAuthenticationFilter` 先识别 `hpw_` Token 并只作用于 runtime 路径，`JwtAuthenticationFilter` 跳过 runtime 路径。管理员 JWT 调用 runtime 路径以及 Worker Token 调用管理路径都必须被拒绝。

### 11.2 Token 创建与删除

管理端支持：

- 创建 worker 时生成 Token。
- Token 只展示一次。
- 删除 worker 当前 Token，使其立即失效。
- worker 没有 Token 时重新创建一个新 Token。
- 禁用 worker 时拒绝其 Token，但不自动删除 Token；重新启用后原 Token 继续有效。
- 只展示当前是否存在 Token，不展示 Token 明文。

明确不实现：Token 过期时间、Token 状态机、历史 Token 列表、新旧 Token 并存和无缝轮换。需要更换 Token 时，先删除旧 Token，再创建新 Token，并更新 worker 的 secret 文件。

### 11.3 Token 存储

worker Docker 配置固定使用只读文件：

```text
/run/secrets/worker-token
```

配置示例：

```properties
worker.id=shanghai-01
worker.tokenFile=/run/secrets/worker-token
```

要求：

- 文件权限为 `0400` 或 `0600`。
- 不写入镜像。
- 不提交 Git。
- 不通过日志打印。
- 优先使用 Docker Secret 或只读 volume mount。

管理端计算固定摘要：

```text
tokenHash = SHA-256(fullToken)
```

认证时对请求 Token 计算 32 字节摘要，并通过 `proxy_worker.token_hash` 唯一索引查询 worker，不扫描全部节点；未命中返回 `401`，命中但 worker 已禁用返回 `403`。第一阶段不缓存 Token 到 worker 的认证结果，确保删除 Token 后下一次请求立即失效。由于 Token 使用 32 字节安全随机数，数据库摘要泄露后也无法通过可行的穷举恢复原 Token。

## 12. HTTPS、证书与容器集成

> **实现更新（2026-09-12）**：当前实现采用第 3.2 节的简化 TLS 模式：`control-manager`
> 每次启动生成临时自签名证书，worker 不校验证书链或主机名。下文 12.2–12.8 所记载的
> P12、公开 CRT、固定证书校验和轮换流程仅保留为旧方案记录，不能用于当前部署；请以
> `docs/development/docker-deployment.md` 和现行 Compose 文件为准。

### 12.1 两类 TLS 边界

本项目存在两类独立的 TLS，配置名和证书不得混用：

| TLS 边界 | 服务端 | 客户端 | 证书用途 | 配置前缀 |
| --- | --- | --- | --- | --- |
| 管理面 HTTPS | `control-manager` | `proxy-worker`、浏览器 | 保护配置、Token、心跳和日志传输 | `server.ssl.*`、`control.manager.*` |
| 代理入站 TLS | `proxy-worker` | 代理使用方 | 为启用 TLS 的 SOCKS/HTTP 入站提供服务端证书 | `tls.certFile`、`tls.keyFile` |

管理面证书私钥只属于 `control-manager`。代理入站需要证书时，必须为对应 worker 单独准备证书和私钥，不能复用管理面私钥。

### 12.2 当前证书基线

本地 `data/tls/` 已存在以下文件，目录已被 `.gitignore` 排除：

```text
data/tls/control-manager.p12
data/tls/control-manager.crt
data/tls/control-manager-keystore.password
```

已验证的证书信息：

```text
算法：RSA 3072 + SHA-256
有效期：2026-07-25 09:53:54 UTC 至 2046-07-25 09:53:54 UTC
Subject/CN：proxy-manager.internal
SAN：DNS:proxy-manager.internal、DNS:localhost、IP:127.0.0.1
SHA-256：89:0E:0A:79:91:73:65:B4:89:E8:92:34:A5:EE:EA:8F:30:30:91:95:6D:E7:0B:4F:8F:9F:2B:E1:7E:2A:D9:9F
```

`control-manager.p12` 中的叶子证书与 `control-manager.crt` 指纹一致。远程 worker 和管理员浏览器必须使用 `https://proxy-manager.internal:<port>` 访问管理端，并通过内部 DNS 或各客户端 hosts 文件把该名称解析到管理端 IP。管理员终端需要把 `control-manager.crt` 加入受信任证书存储。当前证书不包含局域网 IP；如果坚持使用 `https://<LAN-IP>`，必须先重新签发包含该 IP SAN 的证书。

实施或部署前执行以下检查，命令不得输出口令或私钥：

```bash
test -r data/tls/control-manager.p12
test -r data/tls/control-manager.crt
test -r data/tls/control-manager-keystore.password

openssl pkcs12 \
  -in data/tls/control-manager.p12 \
  -passin file:data/tls/control-manager-keystore.password \
  -clcerts -nokeys 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates -ext subjectAltName -fingerprint -sha256

openssl x509 -in data/tls/control-manager.crt \
  -noout -dates -ext subjectAltName -fingerprint -sha256
```

### 12.3 容器文件归属

容器必须采用文件级只读挂载，不能把整个 `data/tls/` 同时挂到所有容器：

| 宿主机文件 | 容器 | 容器路径 | 权限 | 说明 |
| --- | --- | --- | --- | --- |
| `data/tls/control-manager.p12` | `control-manager` | `/data/tls/control-manager.p12` | 只读 | 含管理端私钥 |
| `data/tls/control-manager-keystore.password` | `control-manager` | `/run/secrets/server.ssl.key-store-password` | 只读 | 通过 Spring config tree 读取 |
| `data/tls/control-manager.crt` | 每个 `proxy-worker` | `/data/tls/control-manager.crt` | 只读 | 仅用于信任管理端 |
| `data/tls/workers/<workerId>/inbound.crt` | 对应 `proxy-worker` | `/data/tls/inbound/tls.crt` | 只读、可选 | 代理入站证书链 |
| `data/tls/workers/<workerId>/inbound.key` | 对应 `proxy-worker` | `/data/tls/inbound/tls.key` | 只读、可选 | 代理入站私钥，只进入该 worker |

镜像构建过程不得 `COPY data/tls/`。证书只在容器运行时挂载，镜像导出、镜像仓库和构建缓存中均不能包含私钥。

### 12.4 control-manager Compose 落地

`deploy/docker/docker-compose.control.yml` 的 `control-manager` 服务按以下方式修改：

```yaml
services:
  control-manager:
    environment:
      SERVER_PORT: 8443
      SERVER_SSL_ENABLED: "true"
      SERVER_SSL_KEY_STORE: file:/data/tls/control-manager.p12
      SERVER_SSL_KEY_STORE_TYPE: PKCS12
      SPRING_CONFIG_IMPORT: configtree:/run/secrets/
    volumes:
      - ../../data/tls/control-manager.p12:/data/tls/control-manager.p12:ro
    secrets:
      - source: control_manager_keystore_password
        target: server.ssl.key-store-password
        mode: 0400
    ports:
      - "${CONTROL_BIND_ADDRESS:-127.0.0.1}:${CONTROL_HTTPS_HOST_PORT:-18443}:8443"

secrets:
  control_manager_keystore_password:
    file: ../../data/tls/control-manager-keystore.password
```

约束：

- 不把口令写入 Compose 默认值、镜像、Git 或 Java 命令行。
- `SPRING_CONFIG_IMPORT` 通过 secret 文件名生成 `server.ssl.key-store-password` 属性。
- 删除原 `8081` 的生产端口映射；需要 HTTP 本地开发时使用单独的开发 override 文件。
- `control-manager` 在 P12、口令缺失或不匹配时必须启动失败，不能退回 HTTP。
- 宿主机上的 P12 和口令保持 `0600`，公开 CRT 可为 `0644`。

控制面部署环境文件不保存 secret 明文，只保存路径和普通配置：

```dotenv
CONTROL_BIND_ADDRESS=192.168.1.10
CONTROL_HTTPS_HOST_PORT=18443
```

### 12.5 proxy-worker Compose 落地

每台 worker 使用独立的 Compose 环境和 Token 文件。`deploy/docker/docker-compose.worker.yml` 的基础配置按以下方式修改：

```yaml
services:
  proxy-worker:
    environment:
      CONTROL_MANAGER_URL: https://proxy-manager.internal:${CONTROL_HTTPS_HOST_PORT:-18443}
      CONTROL_MANAGER_TRUSTED_CERT_FILES: /data/tls/control-manager.crt
      WORKER_ID: ${WORKER_ID:?WORKER_ID is required}
      WORKER_TOKEN_FILE: /run/secrets/worker-token
      LAST_KNOWN_GOOD_CONFIG: /data/proxy-worker/last-known-good-config.json
    volumes:
      - ../../data/tls/control-manager.crt:/data/tls/control-manager.crt:ro
      - proxy-worker-data:/data/proxy-worker
    secrets:
      - source: worker_token
        target: worker-token
        mode: 0400
    extra_hosts:
      - "proxy-manager.internal:${CONTROL_MANAGER_HOST_IP:?CONTROL_MANAGER_HOST_IP is required}"

secrets:
  worker_token:
    file: ${WORKER_TOKEN_HOST_FILE:?WORKER_TOKEN_HOST_FILE is required}

volumes:
  proxy-worker-data:
```

生产环境已有内部 DNS 时删除 `extra_hosts`。Token 文件放在 `data/secrets/` 或宿主机专用 secrets 目录，权限为 `0600`，并在实现提交中把 `/data/secrets/` 加入 `.gitignore`。

每台 worker 使用独立环境文件，例如：

```dotenv
WORKER_ID=shanghai-01
CONTROL_MANAGER_HOST_IP=192.168.1.10
CONTROL_HTTPS_HOST_PORT=18443
WORKER_TOKEN_HOST_FILE=/opt/home-proxy/secrets/shanghai-01.token
```

环境文件本身不包含 Token，只包含 Token 文件路径；同样不得提交 Git。

如果该 worker 存在 `tls_enabled=true` 的代理入站，再为该 worker 增加专属挂载和配置：

```yaml
services:
  proxy-worker:
    environment:
      TLS_CERT_FILE: /data/tls/inbound/tls.crt
      TLS_KEY_FILE: /data/tls/inbound/tls.key
      TLS_KEY_PASSWORD_FILE: /run/secrets/inbound-key-password
      TLS_ALLOW_SELF_SIGNED: "false"
    volumes:
      - ../../data/tls/workers/${WORKER_ID}:/data/tls/inbound:ro
    secrets:
      - source: inbound_key_password
        target: inbound-key-password
        mode: 0400

secrets:
  inbound_key_password:
    file: ${WORKER_TLS_KEY_PASSWORD_HOST_FILE:?WORKER_TLS_KEY_PASSWORD_HOST_FILE is required}
```

无加密私钥时不配置 `TLS_KEY_PASSWORD_FILE`，同时删除 `inbound_key_password` secret。第一阶段继续保持“一台 worker 共用一组代理入站证书”的现有模型；每个入站独立证书不在本次范围内。

### 12.6 worker 配置与 HTTP 客户端

`ProxyWorkerConfig` 新增并统一读取以下配置：

```properties
control.manager.url=https://proxy-manager.internal:18443
control.manager.trustedCertFiles=/data/tls/control-manager.crt
worker.id=shanghai-01
worker.tokenFile=/run/secrets/worker-token
worker.lastKnownGoodConfig=/data/proxy-worker/last-known-good-config.json
tls.keyPasswordFile=/run/secrets/inbound-key-password
tls.allowSelfSigned=false
```

对应环境变量为：

```text
CONTROL_MANAGER_URL
CONTROL_MANAGER_TRUSTED_CERT_FILES
WORKER_ID
WORKER_TOKEN_FILE
LAST_KNOWN_GOOD_CONFIG
TLS_KEY_PASSWORD_FILE
TLS_ALLOW_SELF_SIGNED
```

新增 `ControlPlaneHttpClientFactory`，负责：

1. 读取一个或多个 PEM/DER X.509 证书并装入只包含这些证书的内存 `KeyStore`。
2. 创建专用 `SSLContext` 和 `HttpClient`，保留 JDK 默认 HTTPS 主机名校验。
3. 从 `worker.tokenFile` 读取 Token，去除末尾换行，只保存在内存中且不记录日志。
4. 为配置拉取、poll、访问日志和认证日志统一添加 `Authorization: Bearer <token>`。
5. 对 `http://` 管理端地址、缺失证书、空 Token 和不可读文件直接启动失败。

现有 `HttpClientManager`、`WorkerControlService.HttpPollTransport` 和 `AsyncHttpLogPublisher` 必须改为复用该工厂，禁止各自创建裸 `HttpClient`。出站代理连接器不使用此客户端，不受该改造影响。

### 12.7 启动校验与失败策略

生产模式遵循 fail closed：

- `control-manager` 无法加载 P12 或口令时容器启动失败。
- worker 无法读取固定证书或 Token 时容器启动失败。
- TLS 握手失败、证书未知、SAN 不匹配时只重试 HTTPS，不降级 HTTP。
- worker 代理入站启用 TLS 但证书/私钥加载失败时，该次配置应用失败并保留上一份成功配置。
- 仅本地开发显式设置 `TLS_ALLOW_SELF_SIGNED=true` 时，代理入站可以使用 Netty 临时自签证书；管理面永远不允许 trust-all。

容器部署后执行：

```bash
docker compose --env-file /opt/home-proxy/control.env \
  -f deploy/docker/docker-compose.control.yml config
docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml config

curl --fail --silent --show-error \
  --cacert data/tls/control-manager.crt \
  https://proxy-manager.internal:18443/admin/setup-status

docker compose --env-file /opt/home-proxy/control.env \
  -f deploy/docker/docker-compose.control.yml exec control-manager \
  sh -c 'test -r /data/tls/control-manager.p12 && test -r /run/secrets/server.ssl.key-store-password'

docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml exec proxy-worker \
  sh -c 'test -r /data/tls/control-manager.crt && test -r /run/secrets/worker-token'
```

不得通过 `docker compose config` 输出 Token 或口令；两者只能通过文件 secret 引用。

### 12.8 证书轮换

`control.manager.trustedCertFiles` 支持逗号分隔或列表形式的多个证书路径。轮换顺序固定为：

```text
1. 生成新 P12、公开 CRT 和新口令，校验 SAN 与有效期。
2. 把新 CRT 只读挂载到所有 worker，并配置为同时信任新旧证书。
3. 滚动重启 worker，确认全部恢复配置拉取、poll 和日志上报。
4. 切换 control-manager 的 P12 与口令并重启管理端。
5. 确认所有 worker 的 last_seen_at、fetched_config_hash 正常推进。
6. 从 worker 移除旧 CRT，再滚动重启。
7. 离线归档或销毁旧私钥，并记录证书指纹和变更时间。
```

私钥疑似泄露时，不等待常规变更窗口，立即执行以上证书流程；只有同时怀疑 Worker Token 泄露时，才逐个删除并重新创建 Token。

## 13. Worker 配置应用与本地兜底

### 13.1 应用流程

```text
下载配置
-> 校验响应 workerId 和数据完整性
-> 生成不可变的候选运行时快照
-> 计算新增、修改、删除和未变化入站
-> 预校验候选配置、证书、端口与依赖
-> 按变更集刷新 ProxyContext
-> 全部变更成功；失败时按旧快照回滚已变更入站
-> 更新 appliedConfigHash
-> 原子写入最后成功配置文件
```

如果应用失败：

- 未变化入站保持运行；已经停止或切换的入站按上一份成功快照重启。
- 不更新 `appliedConfigHash`。
- 上报 `applySuccess=false` 和精简错误信息。
- 不将失败配置写入最后成功配置文件。

现有 `ProxyContext.refresh` 改为返回明确的 `ConfigApplyResult`，至少包含 `success`、`failedInboundIds` 和脱敏后的 `errorSummary`。同一监听端口的修改无法在旧服务存活时预启动，因此允许短暂停止旧入站；新入站启动失败时必须立即用旧快照恢复，不能只记录日志后继续。

配置应用串行执行，并通过单线程执行器或锁保证同一时间只有一个刷新任务。下载到更新配置时只替换待应用候选值，不能并发修改 `ProxyContext`。

### 13.2 最后成功配置

保存路径固定为：

```text
/data/proxy-worker/last-known-good-config.json
```

要求：

- 仅在配置成功应用后更新。
- 使用临时文件加原子替换，避免进程崩溃留下半文件。
- 文件权限为 `0600`。
- 文件中包含用户凭据和出站密码，必须按敏感文件保护。

启动行为：

```text
管理端可用且新配置有效：应用新配置
管理端不可用但存在最后成功配置：使用最后成功配置启动
管理端不可用且无本地配置：不启动入站，持续退避重试
```

### 13.3 节点下线

删除 Token 无法远程删除离线 worker 已保存的配置。正常节点下线流程：

```text
禁用或删除该 worker 的全部入站
-> 等待 worker 应用空配置并关闭代理服务
-> 删除 Worker Token
-> 停止 worker
-> 删除管理端节点记录
```

## 14. 管理端 API

新增：

```text
GET    /api/workers
POST   /api/workers
GET    /api/workers/{workerId}
PUT    /api/workers/{workerId}
DELETE /api/workers/{workerId}
POST   /api/workers/{workerId}/disable
POST   /api/workers/{workerId}/token
DELETE /api/workers/{workerId}/token
GET    /api/workers/{workerId}/status
GET    /api/workers/{workerId}/effective-config
```

接口约束：

- `/api/workers/**` 只接受管理员 JWT，`/api/worker/poll` 等 runtime API 只接受 Worker Token。
- `POST /api/workers` 请求包含 `workerId`、`displayName`、`description`，响应包含 worker 信息和只出现一次的 `token`。
- `POST /api/workers/{workerId}/token` 只允许在当前 Token 已删除时调用，响应只出现一次新 Token；已有 Token 时返回 `409 CONFLICT`。
- `DELETE /api/workers/{workerId}/token` 幂等删除当前 Token；删除后 worker 的后续 runtime 请求返回 `401`。
- 禁用 worker 使用幂等操作；已禁用节点再次禁用返回当前状态，不返回错误。
- 删除仍有关联入站、任务或状态记录的 worker 返回 `409 CONFLICT` 和引用数量。
- `effective-config` 使用与真实下发相同的依赖解析器和 hash 逻辑，但用户凭据、上游密码等敏感字段默认脱敏；只有真实 worker 配置接口返回运行所需凭据。
- worker 不存在返回 `404`；worker 禁用、Token 与请求体 worker ID 不一致返回 `403`；Token 缺失、格式错误或已删除返回 `401`。

入站管理 API 保留现有 CRUD 路径并增加 worker 条件；创建请求必须携带 `workerId`，列表接口支持 `workerId` 过滤。

`effective-config` 用于管理员预览该 worker 实际会收到的配置闭包，不返回不必要的明文凭据。

## 15. 前端设计

### 15.1 Worker 管理

新增“代理节点”页面：

- 节点列表、启用状态、在线状态。
- 主机名、运行时长、入站数量、连接数。
- 期望配置 hash 和已应用配置 hash。
- 最近配置应用错误。
- 创建节点并一次性展示 Token。
- Token 存在时提供“删除”操作；Token 不存在时提供“创建”操作。

### 15.2 入站管理

- 入站列表增加 worker 列和 worker 筛选。
- 创建入站时必须选择 worker。
- 编辑入站时允许迁移到其他 worker，但要明确提示源节点会关闭、目标节点会启动该入站。
- 入站表单增加兜底路由和匿名用户选择。

### 15.3 全局资源

用户、路由和规则集页面继续展示全局资源，同时增加：

- “使用节点”列。
- “使用入站数量”列。
- 编辑共享资源时的影响范围提示。
- 删除前的引用详情。
- “复制”操作，用于创建独立版本后再绑定。

### 15.4 日志和看板

- 日志查询增加 worker 筛选。
- 流量和连接指标支持按 worker 查看。
- 原单节点网关监控页面改为节点列表和节点详情。

## 16. 数据迁移

### 16.1 MySQL Flyway

当前最新迁移为 `V19__create_worker_control_tables.sql`。本需求固定新增：

```text
V20__create_proxy_worker_registry.sql
V21__bind_inbounds_tasks_and_status_to_worker.sql
```

`V20` 执行：

1. 创建 `proxy_worker`。
2. 把现有 `worker_status` 中非空的不同 ID 导入 `proxy_worker`，`display_name` 暂用同值、`enabled=1`，避免后续增加外键失败；新建接口才强制执行 worker ID 正则，历史异常 ID 由管理员迁移后删除。
3. 插入内置节点 `worker_id='default'`，使用 `INSERT ... ON DUPLICATE KEY UPDATE worker_id=worker_id` 保证幂等。
4. `proxy_worker` 直接包含可空的 `token_hash BINARY(32)` 唯一列，不创建独立 Token/凭据表。
5. `inbound_configs` 增加可空 `worker_id`、`fallback_route_id`、`anonymous_user_id`。
6. 将全部现有入站的 `worker_id` 回填为 `default`，再改成 `NOT NULL DEFAULT 'default'`；保留默认值一个发布周期，保证应用回滚后旧代码仍可新增入站。
7. 删除 `uk_inbound_configs_port`，新增 `uk_inbound_configs_worker_port(worker_id, listen_ip, port)` 和 `idx_inbound_configs_worker_status(worker_id, status)`。
8. 为三个归属/引用字段建立外键，删除策略均为 `RESTRICT`。

`fallback_route_id` 和 `anonymous_user_id` 在 SQL 迁移中保持可空。原因是全新数据库执行 Flyway 时，默认路由和匿名用户尚未由 `DataInitializer` 创建。`DataInitializer` 在创建默认资源后执行一次幂等的旧数据回填：

```text
fallback_route_id IS NULL -> 名称为“兜底直连路由规则”的 route.id
anonymous_user_id IS NULL -> username 为“Anonymous user”的 user.id
```

新建和更新入站时不再依赖名称查找，而是要求请求显式携带 ID；只有迁移回填逻辑允许使用固定名称。

`V21` 执行：

1. `worker_tasks` 增加可空 `worker_id`，回填 `default` 后改为 `NOT NULL DEFAULT 'default'`；保留默认值一个发布周期。
2. 新增 `idx_worker_tasks_worker_consumed_created(worker_id, consumed_at, created_at)`。
3. 首个发布周期保留物理列 `worker_status.last_config_hash`，新实体把它映射为 `fetchedConfigHash`；不要立即重命名，避免应用回滚后旧实体访问失败。
4. `worker_status` 增加 `applied_config_hash`、`apply_success`、`apply_error`、`applied_at`。稳定一个发布周期后再用独立迁移把 `last_config_hash` 重命名为 `fetched_config_hash`。
5. `worker_tasks.worker_id`、`worker_status.worker_id` 均外键到 `proxy_worker(worker_id)`，删除策略为 `RESTRICT`。

迁移测试必须同时覆盖空库升级和包含既有入站/任务/状态数据的 `V19` 数据库升级。

### 16.2 ClickHouse 演进

ClickHouse 表由 `AccessLogTableInitializer` 管理，不走 Flyway。初始化器增加：

```sql
ALTER TABLE default.access_log
ADD COLUMN IF NOT EXISTS worker_id LowCardinality(String) AFTER request_id;
```

同时修改 `ClickHouseAccessLogWriter`、`ClickHouseAccessLogStore` 的插入列、明细查询、列表查询、筛选条件和聚合查询。旧日志的 `worker_id` 默认为空字符串；上线后新日志必须由认证主体覆盖为真实 worker ID。

认证日志接口当前处于注释状态。本次一并恢复 `/api/logs/auth`，由 `AccessLogTableInitializer` 同时创建 `default.auth_log`：

```sql
CREATE TABLE IF NOT EXISTS default.auth_log
(
    ts          DateTime64(3, 'UTC'),
    worker_id   LowCardinality(String),
    proxy_name  LowCardinality(String),
    inbound_id  UInt64,
    user_id     UInt64,
    username    LowCardinality(String),
    client_ip   String,
    client_port UInt16,
    success     UInt8,
    fail_reason Nullable(String),
    protocol    LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (ts, worker_id, inbound_id, user_id);
```

新增 `ClickHouseAuthLogWriter` 完成批量写入。`LogController` 恢复接口后，必须先用 `WorkerPrincipal.workerId` 覆盖批次内每条日志的 `workerId`，再交给 writer；不接受客户端声明的归属。

### 16.3 兼容开关与升级顺序

升级期间提供短暂兼容开关：

```properties
worker.auth.legacy-enabled=true
```

推荐发布顺序：

```text
1. 备份 MySQL、ClickHouse 和 data/tls，记录当前镜像 tag。
2. 部署支持新表结构、Worker Token 和 legacy-enabled=true 的 control-manager。
3. 创建 default worker Token，并以文件 secret 形式部署到现有 worker。
4. 按第 12 节挂载管理端 P12、口令和 worker 信任证书，切换管理端到 HTTPS。
5. 滚动升级 worker，确认配置拉取、poll、日志和最后成功配置均正常。
6. 创建其他 worker，把入站逐个迁移到目标 worker 并观察 applied_config_hash。
7. 设置 worker.auth.legacy-enabled=false，重启 control-manager。
8. 验证匿名请求、管理员 JWT 请求访问 worker API 均返回 401/403。
```

兼容模式只允许把匿名 worker 识别为 `default`，不能允许通过请求体选择其他 worker。兼容模式只用于一次升级，默认值必须为 `false`。

## 17. 实施范围

### 17.1 common

- 修改 `dto/AggregateConfigResponse.java`：增加 `workerId`，版本升级为 `2.0`。
- 修改 `dto/InboundConfigDTO.java`：增加 `workerId`、`fallbackRouteId`、`anonymousUserId`。
- 修改 `dto/WorkerPollRequest.java`：增加 fetched/applied/apply result 字段，保留 `workerId` 仅用于兼容校验。
- 修改 `dto/AccessLog.java`、`dto/AuthLog.java`：增加 `workerId`，但服务端必须覆盖该值。
- 增加 worker 列表、Token 一次性展示和 effective config 所需 DTO；含 Token 明文的创建响应不能被列表接口复用。

### 17.2 control-manager

- 新增 Flyway `V20`、`V21` 和 worker entity、repository、service、controller；Token 字段直接归属 worker。
- 在 `security/` 新增 `WorkerTokenAuthenticationFilter`、`WorkerPrincipal`、`WorkerTokenAuthenticationService`。
- 调整 `SecurityConfig` 和 `JwtAuthenticationFilter`：worker runtime API 只接受 `ROLE_WORKER`，管理 API 只接受管理员 JWT；移除 worker API 匿名白名单。
- 修改 `InboundConfig`、创建/更新请求、DTO、repository、service 和 controller，增加 worker 归属、显式兜底引用与 worker 筛选。
- 新增 `WorkerConfigDependencyResolver`，聚合、校验、删除冲突和影响范围查询全部复用该服务。
- 修改 `AggregateConfigService`、`AggregateConfigCacheService`、`AggregateConfigController`，按认证 worker 计算配置、缓存和 ETag。
- 修改 `WorkerControlService`、`WorkerTaskRepository`、状态 DTO/entity，使状态、任务领取和结果回写绑定认证 worker。
- 修改 `LogController`、`LogService`、ClickHouse initializer/writer/store，服务端覆盖日志 worker ID 并支持筛选。
- 修改 `DataInitializer`，在默认用户和路由创建后完成旧入站引用回填。

### 17.3 proxy-worker

- 修改 `ProxyWorkerConfig`，增加管理端固定证书、Token 文件、LKG 路径和代理入站证书口令文件配置。
- 新增 `ControlPlaneHttpClientFactory`，配置拉取、poll、访问日志和认证日志统一使用同一 HTTPS/Token 客户端。
- 修改 `HttpClientManager`、`WorkerControlService`、`AsyncHttpLogPublisher`，移除内部裸 `HttpClient` 创建。
- 修改 `AggregateConfigService` 和 `ProxyWorkerApplication.ConfigChangeListener`，校验响应 worker ID 并串行应用配置。
- 修改 `ProxyContext.refresh` 返回 `ConfigApplyResult`，支持按旧快照回滚失败变更。
- 新增 `LastKnownGoodConfigStore`，使用临时文件、`fsync` 和同目录原子替换保存最后成功配置。
- 修改心跳指标，分别上报 fetched/applied hash、应用结果和错误摘要。
- 修改 `TlsContextManager`，生产模式下外部证书加载失败不再静默回退自签证书。

### 17.4 frontend

- 在 `services/api.ts` 和 `types/` 增加 worker、Token 创建/删除、effective config、引用范围和 worker 筛选 API 类型。
- 新增 `WorkerManagement.tsx`，并接入 `App.tsx`、`ProxyConfig.tsx` 导航。
- 修改 `InboundManagement.tsx`，支持 worker 归属、筛选、兜底路由和匿名用户选择。
- 修改用户、路由、规则集页面，展示引用 worker 和编辑影响范围。
- 修改 `Dashboard.tsx`、`LogAudit.tsx` 及聚合分析页面，支持 worker 筛选。
- Token 明文只在创建成功弹窗展示一次，关闭后前端状态中不得继续保留。

### 17.5 deploy 与文档

- 修改两个 Compose 文件，落实第 12 节的证书、secret、HTTPS 端口、worker 数据卷和环境变量。
- Dockerfile 不复制 `data/tls/`；如需入口脚本，只能负责权限/文件存在性校验，不能打印 secret。
- 修改 `.gitignore` 增加 `/data/secrets/`。
- 同步更新 `README.md`、`docs/development/docker-deployment.md` 和 `backend/proxy-worker/src/main/resources/proxy-worker.properties` 示例。
- 增加部署前检查脚本 `scripts/deploy/check-multi-worker-prerequisites.sh`，只检查文件、证书元数据、Compose 展开和 DNS，不输出 secret 内容。

## 18. 测试策略

### 18.1 配置隔离

- Worker A 只能获得归属于 A 的入站。
- Worker B 不能通过请求参数获得 A 的配置。
- A 和 B 引用不同路由时，各自只获得所需路由。
- A 和 B 引用同一路由时，二者都获得该路由。
- 未被任何入站引用的用户、路由和规则集不下发。
- 路由只引用部分规则集时，只下发对应规则集。
- 修改 A 独占资源后，B 的 configHash 不变。
- 修改共享资源后，所有引用 worker 的 configHash 都变化。

### 18.2 引用校验

- 入站引用不存在的用户或路由时保存失败。
- 路由引用不存在、未启用或未发布的规则集时校验失败。
- 删除被引用资源时返回冲突和引用详情。
- fallback route 和 anonymous user 能被纳入配置闭包。

### 18.3 Token 认证与授权

- 无 Token 返回 `401`。
- 错误或已删除 Token 返回 `401`。
- 禁用 worker 的 Token 被拒绝。
- Worker A Token 无法写入 Worker B 状态或领取 B 的任务。
- 日志中的 workerId 始终由服务端认证主体覆盖。
- 每个 worker 最多只有一个 Token；已有 Token 时重复创建返回 `409`。
- 删除 Token 后旧 Token 立即失效；重新创建后只有新 Token 可用。

### 18.4 TLS

- worker 信任指定自签证书时可以建立连接。
- 使用未知证书时连接失败。
- SAN 与访问地址不匹配时连接失败。
- HTTP 不允许自动降级。
- 证书或 Token 不出现在日志中。
- 配置拉取、poll、访问日志和认证日志四条链路使用相同的证书校验和 Token 注入逻辑。
- `control-manager` 容器存在 P12 和口令，但 worker 容器中不存在 P12 和管理端私钥。
- worker 容器只读存在 `control-manager.crt`，且证书指纹与部署基线一致。
- P12 口令错误、证书文件缺失、Token 文件为空时对应容器启动失败。
- 代理入站证书缺失时，生产模式配置应用失败；仅开发开关允许临时自签回退。

### 18.5 配置应用与容错

- 配置成功应用后更新 appliedConfigHash 并保存本地文件。
- 入站启动失败时保留上一份运行配置。
- 失败配置不覆盖最后成功配置文件。
- 管理端不可用时 worker 能从本地成功启动。
- 本地文件损坏且管理端不可用时不启动入站并持续重试。

### 18.6 迁移

- 现有入站全部绑定到 `default` worker。
- 同一 worker 内不能创建重复监听地址和端口。
- 不同 worker 可以使用相同监听地址和端口。
- 兼容模式关闭后旧匿名请求被拒绝。
- 从空库执行到 `V21` 后默认 worker 存在，应用可正常启动。
- 从带既有入站、任务、状态的 `V19` 数据库升级后数据无丢失。
- 回滚旧版本应用时，`worker_id DEFAULT 'default'` 不阻断旧代码新增入站和任务。

## 19. 风险与控制

### 19.1 全局资源误修改

共享路由或规则集修改可能同时影响多个 worker。

控制措施：展示引用 worker、编辑前提示影响范围、提供复制功能、保留配置审计信息。

### 19.2 JSON 引用查询成本

用户和路由引用保存在入站 JSON 中，规则集引用保存在路由 JSON 中，反向查询不方便。

控制措施：第一阶段集中到依赖解析服务并使用全缓存失效；规模增长后再迁移为关系表。

### 19.3 长期自签证书私钥泄露

固定证书一旦私钥泄露，攻击者可以伪装管理端。

控制措施：私钥只存在管理端、持久化文件最小权限、离线备份、worker 支持同时信任多张证书以便紧急轮换。

### 19.4 Token 泄露

泄露 Token 可以读取对应 worker 配置并伪造日志或心跳。

控制措施：每 worker 独立 Token、摘要存储、一次性展示、文件挂载、快速删除和认证限流。

### 19.5 删除 Token 不能清除离线节点数据

Token 删除只影响未来请求，无法删除已经保存到远程主机的配置。

控制措施：使用标准下线流程，先下发空配置并等待应用成功，再删除 Token。

## 20. 验收标准

完成以下条件后，可认为多 worker 配置分发能力交付：

- 管理端可以创建、禁用和查看多个 worker。
- 每个 worker 使用独立 Token 通过 HTTPS 拉取配置。
- worker 严格校验固定自签证书和主机名。
- `control-manager.p12` 与口令只存在于 `control-manager` 容器，worker 容器只挂载公开的 `control-manager.crt`。
- 当前证书部署使用 `proxy-manager.internal`，证书指纹与第 12.2 节基线一致。
- 每个 worker 只获得自己的入站及其引用闭包。
- 配置 hash、ETag、状态和任务按 worker 隔离。
- 配置应用失败不会破坏上一份可用配置。
- 管理端不可用时 worker 可以使用最后成功配置启动。
- 日志和监控能够区分 worker。
- 旧匿名 worker 接口在迁移完成后被关闭。

## 21. 研发提交计划

每个批次应保持可构建、可测试，按顺序合并。不要把数据库、认证、worker TLS 和前端全部压在一个提交中。

### 21.1 批次一：数据模型与公共契约

内容：

- 提交 `V20`、`V21`、entity/repository 变更。
- 修改 common DTO 和前后端基础类型。
- 完成 `DataInitializer` 的默认引用回填。
- 暂不改变现有匿名 worker 行为。

退出条件：空库和 V19 升级测试通过，旧单 worker 配置接口行为不变，`make backend-test` 通过。

### 21.2 批次二：Worker 注册与认证

内容：

- 实现 worker CRUD、单 Token 创建/删除、摘要校验和一次性明文响应。
- 实现 `WorkerTokenAuthenticationFilter`、`WorkerPrincipal` 和接口授权边界。
- `legacy-enabled=true` 时仅兼容 `default` worker。
- poll、任务结果和状态先完成 worker 隔离。

退出条件：认证/授权集成测试通过；A Token 不能读取或写入 B；管理员 JWT 不能调用 runtime API；Token 明文不落库、不进日志；Token 删除后立即失效。

### 21.3 批次三：按 worker 聚合配置

内容：

- 完成入站 worker 归属、引用校验和唯一约束。
- 实现 `WorkerConfigDependencyResolver`。
- 聚合服务、cache key、ETag 和 effective config 按 worker 隔离。
- 补齐共享资源影响范围与删除冲突。

退出条件：第 18.1、18.2 节测试全部通过；A 独占资源变化不改变 B 的 hash；聚合异常返回错误且不向 worker 下发部分配置，worker 继续使用已应用配置。

### 21.4 批次四：Worker HTTPS、Token 与配置回滚

内容：

- 实现 `ControlPlaneHttpClientFactory` 和四条 worker 通信链路统一接入。
- 实现固定证书校验、Token 文件读取和 HTTP 禁止策略。
- 实现 `ConfigApplyResult`、旧快照回滚和 `LastKnownGoodConfigStore`。
- 扩展 worker 心跳应用状态。

退出条件：第 18.3 至 18.5 节后端测试通过；管理端断开后 worker 可从 LKG 启动；未知证书、错误 SAN、空 Token 均失败关闭。

### 21.5 批次五：日志、管理台与可观测性

内容：

- ClickHouse 增加 access/auth 日志 worker 归属、写入和筛选。
- 完成 worker 管理、入站归属、引用影响范围、日志和看板筛选页面。
- 增加 Token 是否存在和配置应用错误展示。

退出条件：后端日志查询测试、前端 React Testing Library 测试和 `make frontend-build` 通过；Token 只展示一次。

### 21.6 批次六：容器集成与安全收口

内容：

- 修改 Compose、配置示例、部署文档和检查脚本。
- 按第 12 节把 `/data/tls/` 文件挂载到对应容器。
- 完成 HTTPS 切换和 worker 滚动升级。
- 验证全部 worker 正常后关闭 `legacy-enabled`。

退出条件：第 22 节全部命令通过；匿名 runtime API 被拒绝；容器文件检查确认私钥未进入 worker。

## 22. 验证命令

研发环境按以下顺序执行：

```bash
mvn -f backend/pom.xml -pl common,control-manager -am test -DskipTests=false
mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false
npm --prefix frontend test -- --watch=false
npm --prefix frontend run build
make package

docker compose --env-file /opt/home-proxy/control.env \
  -f deploy/docker/docker-compose.control.yml config --quiet
docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml config --quiet
scripts/deploy/check-multi-worker-prerequisites.sh \
  /opt/home-proxy/control.env \
  /opt/home-proxy/workers/shanghai-01.env
```

容器集成环境继续执行：

```bash
docker compose --env-file /opt/home-proxy/control.env \
  -f deploy/docker/docker-compose.control.yml up -d --build
docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml up -d --build

curl --fail --silent --show-error \
  --cacert data/tls/control-manager.crt \
  https://proxy-manager.internal:18443/admin/setup-status

test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --cacert data/tls/control-manager.crt \
  https://proxy-manager.internal:18443/api/config/aggregate)" = "401"

docker compose --env-file /opt/home-proxy/workers/shanghai-01.env \
  -f deploy/docker/docker-compose.worker.yml exec proxy-worker \
  sh -c 'test ! -e /data/tls/control-manager.p12 && test -r /data/tls/control-manager.crt'
```

最后通过管理 API 或界面创建 A、B 两个测试 worker，分别绑定一个相同端口的入站，验证二者配置闭包、ETag、任务、状态和日志互不串线。测试完成后按标准下线流程删除测试节点。

## 23. 发布、观察与回滚

### 23.1 发布前检查

- 备份 MySQL、ClickHouse 和 `data/tls/`，备份文件放在受控目录，不进入 Git。
- 记录旧 control/worker 镜像 tag、Compose 文件和环境配置。
- 确认 `proxy-manager.internal` 在每台 worker 上解析到正确 IP。
- 确认证书剩余有效期大于 30 天，P12 与 CRT 指纹一致。
- 确认每个 worker 的 Token 文件、数据卷和 worker ID 唯一。

### 23.2 观察窗口

每升级一个 worker，至少观察两个配置拉取周期和三个 poll 周期，检查：

```text
last_seen_at 持续推进
fetchedConfigHash 与目标配置一致
appliedConfigHash 与 fetchedConfigHash 一致
applySuccess=true
访问日志出现正确 workerId
旧入站已关闭，目标入站已监听
```

未满足以上条件时停止后续 worker 迁移，保留兼容模式并先处理当前节点。

### 23.3 回滚条件

出现以下任一情况立即停止发布：

- 两个连续拉取周期内 worker 无法通过 TLS/Token 连接管理端。
- 配置闭包包含其他 worker 的入站或敏感资源。
- 配置应用失败后旧入站未恢复。
- 日志、任务或状态出现 worker 串线。
- P12、私钥或 Token 出现在错误容器、日志或镜像层。

### 23.4 回滚步骤

1. 保持 `worker.auth.legacy-enabled=true`，停止新增 worker 和入站迁移。
2. 尚未迁移入站时，可直接恢复旧镜像、旧 Compose 和原 HTTP 端口；`worker_id DEFAULT 'default'` 保证旧代码仍可写入。
3. 已迁移到非 `default` worker 后，不得直接回滚到全局聚合旧版本，否则所有 worker 会收到同一份配置。先停止非默认 worker，并把入站迁回 `default`，或恢复发布前数据库备份。
4. TLS 切换失败时恢复旧 Compose 端口和旧镜像，但保留证书文件备份用于排查；不要删除或覆盖原 P12。
5. 数据迁移本身保持前向兼容，不执行手工 `DROP COLUMN` 回滚。只有确认需要恢复发布前业务数据时才恢复完整数据库备份。
6. 回滚后验证单 worker 配置拉取、入站监听、日志写入和管理台访问，再解除变更冻结。
