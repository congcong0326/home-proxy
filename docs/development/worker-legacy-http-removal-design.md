# Worker 遗留 HTTP 调用与旧日志链路清理设计

> 状态：代码清理已完成（Worker 侧与控制端侧）；控制端部分发布前须先满足 §3 发布前置条件。本文是《[日志字段与类型精简设计](telemetry-field-slimming-design.md)》的前置任务，两份文档分别发布，互不阻塞，但建议先做本文。

## 1. 背景与目标

Worker 与控制端早已改为"控制端主动连接 Worker 的 `/management/ws`（WSS）"的推送模型：配置下发、心跳指标、新版 V1 日志（`TELEMETRY_BATCH`）都走这条通道。但 Worker 里还保留着三类早期的 HTTP 调用代码，它们要么已经无法被执行到，要么被开关固定关闭：

| 遗留链路 | 现状 |
|---|---|
| 轮询拉取聚合配置 `GET /api/config/aggregate` | `ProxyWorkerApplication` 只调用 `startManaged()`；`AggregateConfigService.start()` 没有任何调用方 |
| 轮询心跳 `POST /api/worker/poll` | `WorkerControlService` 的 `HttpPollTransport`，主流程不再启动 |
| HTTP 上报旧日志 `POST /api/logs/access`、`/api/logs/auth` | `AsyncHttpLogPublisher` 没有任何引用；`AccessLogUtil` 的采集被常量 `LEGACY_LOG_REPORTING_ENABLED = false` 固定关闭 |

目标：**Worker 与控制端两侧同时清理**这条遗留链路——删除 Worker 侧的死代码、对应配置项和测试，也删除控制端上给它们服务的 HTTP 接口、鉴权规则和旧日志写入路径，让两者之间只剩 WSS 一条链路。Worker 侧清理不改变任何运行时行为；控制端侧清理后，**还在用 HTTP 轮询的旧版本 Worker 将无法再拉取配置**（见 §3 的发布前置条件），不影响新版日志（V1）采集上报。

## 2. 范围

### 2.1 本文范围（Worker 侧，必做）

**A. 旧访问／认证日志采集与发布**

| 文件 | 处理 |
|---|---|
| `proxy-worker/.../audit/AccessLogUtil.java` | 删除 |
| `proxy-worker/.../audit/LogPublisher.java` | 删除 |
| `proxy-worker/.../audit/impl/AsyncHttpLogPublisher.java` | 删除（无引用） |
| `proxy-worker/.../audit/impl/WebSocketLogPublisher.java` | 删除；`audit` 包清空后一并删除目录 |
| `proxy-worker/.../server/RelayHandler.java` | 删除 `AccessLogUtil.logFailure` 两处调用（第 69、118 行附近）及 import |
| `proxy-worker/.../protocol/ProxyTunnelConnectorHandler.java` | 删除 `AccessLogUtil.logFailure` / `logSuccess` 调用（第 88、111 行附近）及 import。注意只删旧日志调用，不要动同一位置的 `TelemetryHooks` 调用 |
| `proxy-worker/.../ProxyWorkerApplication.java` | 删除 `AccessLogUtil.start()` / `stop()` |
| `proxy-worker/.../service/WorkerManagementWebSocketServer.java` | `CONFIG_POLL` 帧不再向 `accessLogs` / `authLogs` 填值（现在已固定为空，保持字段为 `null` 即可）；删除对 `AccessLogUtil` 的取日志调用（若有） |
| `proxy-worker/src/test/.../audit/WebSocketLogPublisherTest.java` | 删除 |

**B. HTTP 拉取配置与 HTTP 轮询心跳**

| 文件 | 处理 |
|---|---|
| `proxy-worker/.../http/HttpClientManager.java` | 删除 |
| `proxy-worker/.../http/ControlPlaneHttpClientFactory.java` | 删除（先确认 `WorkerManagementWebSocketServer` 没有引用，当前核对结果是没有） |
| `proxy-worker/src/test/.../http/ControlPlaneHttpClientFactoryTest.java` | 删除 |
| `proxy-worker/.../service/WorkerControlService.java` | 删除 `HttpPollTransport`、`pollLoopOnce`、`forAggregateConfigService` 及轮询调度线程。**其中的嵌套类型 `ConfigApplicationStatus` 被 `AggregateConfigService` 和 WSS 服务使用，必须先抽成独立类 `service/ConfigApplicationStatus.java`（保持字段和 `empty()` 不变），再删除外层类** |
| `proxy-worker/.../service/AggregateConfigService.java` | 删除 `httpClientManager` 字段、`start()`、`fetchConfigNow()`、`clearCache()` 中的 HTTP 缓存分支、`getCurrentConfig()` / `hasConfig()` 里对 `httpClientManager` 的判断；保留 `startManaged()`、`acceptManagedConfig()`、`getApplicationStatus()`、`getFetchedConfigHash()`、`getManagedPollingConfigHash()`、监听器 |
| `proxy-worker/.../service/LastKnownGoodConfigStore.java` | 保持不变（WSS 下发的配置也依赖它） |
| `WorkerMetricsCollector`、`WolTaskExecutor`、`WorkerTaskExecutor` | 保持不变，WSS 心跳仍在使用 |

**C. Worker 配置项（`ProxyWorkerConfig`）**

删除以下仅服务于 HTTP 链路的字段和 getter：

- `controlBaseUrl` 及 `getAggregateConfigUrl()`、`getAccessLogUrl()`、`getAuthLogUrl()`、`getWorkerPollUrl()`、`getControlBaseUrl()`
- `workerControlPollIntervalMs`（`worker.control.pollIntervalMs`）
- `workerTokenFile`（`worker.tokenFile`，HTTP 鉴权用的 Worker Token）
- `tlsAllowSelfSigned`：仅 HTTP 客户端信任自签证书时使用，删除前请再确认 `WorkerManagementWebSocketServer` 没有读取它

处理原则：
1. **仍然读取并忽略**旧的 `control.manager.url`、`CONTROL_HOST`、`CONTROL_PORT`、`control.host`、`control.port`、`worker.tokenFile`、`worker.control.pollIntervalMs`、`WORKER_MANAGEMENT_ENABLED` 等属性/环境变量，不要因为多余配置而启动失败：现有 `docker-compose` 和线上 `.env` 里可能还留着它们。
2. `ProxyWorkerConfigTest` 中断言 `getAggregateConfigUrl()` / `getAccessLogUrl()` 的用例随之删除或改写；启动日志"控制端地址"一行删除。
3. `worker.management.enabled` 当前已是"设置为 false 也会被忽略并告警"，本次一并简化：不再读取该字段，删除告警分支，部署文件里的 `WORKER_MANAGEMENT_ENABLED` 变量改为注释说明"已废弃"（见 §4）。

### 2.2 控制端遗留接口与旧日志写入路径（必做）

下面这些接口只为 HTTP 模型的旧 Worker 服务。线上代码已不再使用，一并清理。

> **安全背景（这是优先清理它们的原因）**：`SecurityConfig` 对 `POST /api/logs/access` 与 `/api/logs/auth` 配置的是 `permitAll()`（注释写明"Worker 日志不再携带 Worker Token"），而 `LogController` 会直接把请求体写入日志库。也就是说**任何能访问控制端端口的人都能无鉴权注入伪造的访问／认证日志**。这两个接口现网实际无人使用，只剩攻击面。

**A. 要删除的 HTTP 接口**

| 接口 | 代码位置 | 处理 |
|---|---|---|
| `POST /api/logs/access`、`POST /api/logs/auth` | `controller/LogController`（`ingestAccessLogs`、`ingestAuthLogs`、`resolveWorkerId`） | 删除这两个方法及辅助方法；`GET /api/logs/**` 查询接口保留（需管理员鉴权，旧历史数据还在读取） |
| `GET /api/config/aggregate` | `controller/AggregateConfigController` | 整个类删除。`AggregateConfigCacheService` 保留，WSS 下发配置仍然使用；管理端用的 `/api/config/hash` 属于其他 Controller，保持不变 |
| `POST /api/worker/poll` | `controller/WorkerController.poll` | 删除该方法；同一个类里的 `GET /api/worker/status`（管理员查询）保留 |

**B. 要删除的鉴权规则**

| 位置 | 处理 |
|---|---|
| `security/SecurityConfig`（第 34、35、37 行） | 删除 `/api/config/aggregate`、`/api/worker/poll`、`/api/logs/access`、`/api/logs/auth` 四条放行／角色规则。删除后这些路径落入"其他请求需认证"，未携带管理员 JWT 的请求应返回 401／403，而不是 200 |
| `security/JwtAuthenticationFilter`（第 160～163 行附近） | 删除对这四个路径的跳过判断 |
| `security/WorkerTokenAuthenticationFilter` | 它只处理上述四个路径（`shouldNotFilter` 之外的请求），路径删除后无事可做，整类删除；同时删除配置项 `worker.auth.legacy-enabled`（含 `application*.properties` 与文档） |
| `security/WorkerPrincipal` | 其余引用点只有已删除的两个 Controller，确认后一并删除；以编译结果为准 |

**C. 要删除的旧日志写入路径**

| 位置 | 处理 |
|---|---|
| `service/WorkerManagementConnectionService`（第 275～280 行） | 删除对 `CONFIG_POLL` 帧里 `accessLogs` / `authLogs` 的处理（`logService.saveAccessLogs` / `saveAuthLogs`）。**帧里仍可能带这些字段（旧 Worker），解析必须容忍并直接忽略**，不报错 |
| `service/LogService` | 删除 `saveAccessLogs`（两个重载）、`saveAuthLogs`、`validateBatch` 及只被它们使用的依赖；保留查询方法 |
| `clickhouse/ClickHouseAccessLogWriter`、`clickhouse/ClickHouseAuthLogWriter` 及其定时刷盘、缓冲、相关配置项 | 写入路径整体删除；`AccessLogTableInitializer` 和 `ClickHouseAccessLogStore` 等**读取**路径与旧表保留，历史数据仍可查。删除前先全局搜索确认没有别处依赖这些 Writer（例如旧版用户流量统计的聚合是否在写入时顺带产生，若是，其结果不再更新，属预期） |
| `common/.../dto/WorkerManagementMessage` | `accessLogs`、`authLogs` 字段保留并标 `@Deprecated`，只用于容忍旧版本发来的帧 |
| `common` 里的 `AccessLog`、`AuthLog` | 保留（`GET /api/logs/**` 和上面的兼容字段仍使用） |

**D. 轮询服务端逻辑**

`service/WorkerControlService.poll(...)`（含"兼容入口" `poll(WorkerPollRequest)`）只被已删除的 `WorkerController.poll` 调用，应删除；`recordManagementPoll(...)`、任务创建与下发、`getLatestStatus()` 等被 WSS 路径使用的方法保留。**注意**：`poll()` 里有"取出待下发任务并标记状态"的逻辑，实施前先确认 WSS 路径（`WorkerManagementConnectionService` 下发 `taskPayload` 的部分）没有复用它；如有复用，先把共用部分抽成独立方法再删入口。`WorkerPollResponse` 若无其他引用也一并删除，`WorkerPollRequest` 仍被 `toPollRequest(heartbeat)` 使用，保留。

**E. 两种 Token 的区别（实施时不要混淆）；Worker Token 暂不处理**

| | 管理 Token（Worker 管理 Token） | Worker Token（`hpw_` 开头） |
|---|---|---|
| 方向 | 控制端 → Worker（WSS 握手） | Worker → 控制端（HTTP 请求） |
| 配置位置 | Worker：`worker.management.tokenFile`（`ManagementTokenStore`）；控制端：`proxy_worker.management_token`，在管理页创建 Worker 时填写 | 控制端创建 Worker 时生成，只保存哈希（`proxy_worker.token_hash`）；Worker 侧读取 `worker.tokenFile` |
| 谁校验 | Worker 的 `WorkerManagementWebSocketServer.UpgradeAuthorizationHandler` 校验握手 `Authorization: Bearer ...` | 控制端 `WorkerTokenAuthenticationFilter` / `WorkerTokenAuthenticationService` |
| 当前是否在用 | **在用，本次不改动**，所有配置、日志、心跳都依赖它 | 只用于上面被删除的四个 HTTP 接口，接口删除后无处校验 |

本次**不动**管理 Token 的任何代码和配置，也不动 Worker 管理页。`hpw_` Worker Token 在接口删除后属于无人校验的残留（控制端创建 Worker 时仍会生成并返回它，涉及数据库列 `token_hash`、`WorkerTokenResponse` 接口契约、前端 `types/worker.ts`、`services/api.ts` 与 Worker 管理页），是否清理请单独立项，不在本次范围内。本次只删除 `WorkerTokenAuthenticationFilter` 与 Worker 侧读取 `worker.tokenFile` 的代码。

## 3. 兼容性与发布前置条件

| 场景 | 结论 |
|---|---|
| 新 Worker + 新控制端 | 正常。两者只通过 WSS 通信 |
| **仍使用 HTTP 轮询的旧版 Worker + 新控制端** | **不再支持**：它无法拉取配置、无法上报心跳。这是本次清理的预期代价，所以必须满足下面的发布前置条件 |
| 已是 WSS 模型但尚未升级到本版本的 Worker + 新控制端 | 兼容。这类 Worker 的 `CONFIG_POLL` 帧里仍可能带 `accessLogs` / `authLogs`（目前固定为空），控制端容忍并忽略；新版 V1 日志照常上报 |
| 新 Worker + 旧控制端 | 兼容。新 Worker 本来就只走 WSS，`accessLogs` / `authLogs` 改为 `null`，旧控制端按空处理 |
| `WorkerManagementMessage` 的 `accessLogs` / `authLogs` 字段 | 保留在 `common`，仅标 `@Deprecated` |
| 已有 `proxy-worker.properties` / 环境变量 | 多余配置只忽略，不报错 |
| `last-known-good-config.json` | 格式不变，升级后可直接使用 |

**JSON 解析策略**：这次涉及的帧都要用宽松解析——遇到不认识的字段直接忽略，不能因为多一个字段就拒收整条消息（旧版本发来的 `accessLogs`、将来新增或删除的字段都属于这种情况）。具体做法见《[日志类型与字段精简设计](telemetry-field-slimming-design.md)》§3.1，建议两份文档的实施合并考虑这一项，谁先做谁落地。

**发布前置条件（控制端清理部分）**：在管理端 Worker 列表中确认每个启用的 Worker 都显示 WSS 已连接，并且心跳是通过 WSS 上报的（控制端日志里 `recordManagementPoll` 有该 Worker 的记录，或 Worker 详情页心跳时间持续更新）。如果仍有 Worker 没有配置 WSS 管理地址和管理 Token，必须先把它迁到 WSS，再发布控制端清理。生产环境的操作顺序参考 [生产多 Worker 升级操作手册](production-multi-worker-upgrade-runbook.md)。

**发布顺序**：
1. 先发布 Worker 侧清理（§2.1）。它不改变行为，可以逐个灰度。
2. 确认所有 Worker 都已是 WSS 模式后，再发布控制端清理（§2.2）。

## 4. 部署与文档同步

- `deploy/docker/docker-compose.worker.yml`、根 `README.md`（约第 222 行附近）：去掉 `WORKER_MANAGEMENT_ENABLED`，或保留并注明"已废弃，值被忽略"。
- `backend/proxy-worker/src/main/resources/proxy-worker.properties`：删除 `worker.management.enabled`、`control.*`、`worker.tokenFile`、`worker.control.pollIntervalMs` 等条目。
- `docs/development/docker-deployment.md`：同步删除 `WORKER_MANAGEMENT_ENABLED` 说明。
- `docs/code-index.md`：删除 `AggregateConfigController`（第 34 行）、`service/AggregateConfigService`"每 30 秒拉取 `/api/config/aggregate`"（第 56 行）、聚合配置分发链路里的 HTTP 步骤（第 103 行）、"`/api/logs/access`、`/api/logs/auth` 端点保留给兼容 worker"（第 118 行）等描述，改为"配置与日志均走 WSS"；`AccessLogUtil` 相关描述删除。
- `docs/development/multi-worker-config-distribution-design.md`、`docs/testing/gateway-observability-stage-a.md`、`docs/testing/gateway-observability-*.md` 中涉及 HTTP 拉取配置、旧日志上报的描述同步修订。
- `docs/development/worker-polling-control-channel-design.md`：文首增加"HTTP 轮询已被 WSS 取代并已从代码中移除"的说明。

## 5. 实施步骤（建议分两个 PR）

**PR 1：Worker 侧（§2.1）**
1. 抽出 `ConfigApplicationStatus` 独立类，替换所有引用（`AggregateConfigService`、`WorkerManagementWebSocketServer`、`WorkerMetricsCollector` 及测试）。
2. 删除 `WorkerControlService` 与 HTTP 轮询相关代码、`HttpClientManager`、`ControlPlaneHttpClientFactory` 及其测试，并精简 `AggregateConfigService`。
3. 删除旧日志链路：`audit` 包、`RelayHandler`、`ProxyTunnelConnectorHandler`、`ProxyWorkerApplication` 里的调用。
4. 精简 `ProxyWorkerConfig` 和 `ProxyWorkerConfigTest`，改部署文件与 `proxy-worker.properties`。

**PR 2：控制端（§2.2）**，满足 §3 发布前置条件后再合并发布
1. 先让 `WorkerManagementConnectionService` 的解析对未知字段宽松，再删除对 `accessLogs` / `authLogs` 的处理。
2. 删除 `LogController` 两个 POST、`AggregateConfigController`、`WorkerController.poll`、`WorkerControlService.poll`。
3. 删除 `SecurityConfig`、`JwtAuthenticationFilter` 中的四条规则，删除 `WorkerTokenAuthenticationFilter` 及 `worker.auth.legacy-enabled`。
4. 删除 `LogService` 写入方法和 `ClickHouseAccessLogWriter` / `ClickHouseAuthLogWriter`。
5. 补测试、更新文档。

## 6. 验证

- `make worker-build` 与 `mvn -pl backend/proxy-worker test` 通过。
- 删除后全局搜索无残留：`AccessLogUtil`、`LogPublisher`、`HttpClientManager`、`ControlPlaneHttpClientFactory`、`HttpPollTransport`、`getAggregateConfigUrl`、`getWorkerPollUrl`。
- 本地起一个 Worker + 控制端：
  1. 控制端能连上 Worker 的 `/management/ws`，管理页显示 Worker 在线并有心跳指标；
  2. 在管理页修改并发布配置，Worker 的应用状态变为已生效；
  3. 经 Worker 访问一次目标，新版日志审计页能看到连接日志（V1 链路未受影响）；
  4. 保留旧的 `CONTROL_HOST`、`WORKER_MANAGEMENT_ENABLED=false` 环境变量启动，Worker 正常启动，不报错。
- Worker 日志中不再出现对控制端 `/api/config/aggregate`、`/api/worker/poll`、`/api/logs/*` 的 HTTP 请求。
- 控制端清理后：
  - 全局搜索无残留：`AggregateConfigController`、`WorkerTokenAuthenticationFilter`、`saveAccessLogs`、`saveAuthLogs`、`ClickHouseAccessLogWriter`、`ClickHouseAuthLogWriter`、`worker.auth.legacy-enabled`。
  - 不带 JWT 请求 `POST /api/logs/access`、`POST /api/logs/auth`、`GET /api/config/aggregate`、`POST /api/worker/poll`，均返回 401 或 403（不能是 200／202）；带管理员 JWT 请求这几个路径应为 404 或 405。
  - 带管理员 JWT 访问 `GET /api/logs/access`、`GET /api/worker/status` 仍然正常，历史访问日志页（如仍可访问）能查询历史数据。
  - 向 WSS 发送一个带 `accessLogs`、`authLogs` 和任意额外未知字段的 `CONFIG_POLL` 帧，控制端不报错，心跳正常记录，配置下发正常。
  - 更新或重写 `JwtAuthenticationFilterTest`、`WorkerStatusSecurityTest`、`WorkerControlServiceTest`（后者里的 `poll()` 用例改为覆盖 `recordManagementPoll` 与任务下发）。

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 漏改引用导致编译失败，或误删 WSS 仍在使用的类／方法（`ConfigApplicationStatus`、`WorkerMetricsCollector`、`recordManagementPoll`、任务下发逻辑） | 以编译和测试为准；§2 已列明必须保留的部分 |
| 还有未迁移到 WSS 的 Worker，控制端清理后失联 | §3 发布前置条件，PR 2 在确认所有 Worker 已是 WSS 后再发布 |
| 旧 Worker 的帧里带已移除的字段，被严格解析拒收 | §3 宽松解析要求，并有专门的验证用例 |
| 旧历史数据读取受影响 | 只删写入路径，读取路径和旧表保留 |

**回滚**：Worker 侧回退镜像即可，无数据迁移。控制端侧回退应用版本即可恢复所有接口，没有数据库迁移，旧日志表保留；回滚期间新增的 V1 日志不受影响。
