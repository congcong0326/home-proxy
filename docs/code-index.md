# Code Index

这份索引用于快速定位代码，不替代源码细节。建议先读 `AGENTS.md` 的协作规则，再用本文找到相关模块和入口，最后只打开本次任务涉及的文件。

## 快速入口

- `Makefile`：统一构建入口，封装后端 Maven、前端 npm、前端静态资源同步。
- `backend/pom.xml`：Maven 聚合工程，包含 `common`、`proxy-worker`、`control-manager`。
- `backend/common`：控制端和工作节点共享的 DTO、枚举、Geo/域名规则工具。
- `backend/control-manager`：Spring Boot 管理端，提供配置管理、聚合配置分发、认证、日志与看板 API。
- `backend/proxy-worker`：Netty 工作节点，拉取聚合配置并启动多协议代理服务。
- `frontend`：React + TypeScript 管理台，页面、API client、Redux auth 状态都在 `frontend/src`。
- `docs/development`：研发设计、架构说明和多步骤变更设计。
- `docs/testing`：测试策略、测试矩阵和集成测试计划。

## 后端 common

- `backend/common/src/main/java/org/congcong/common/dto`：跨模块传输模型。重点看 `AggregateConfigResponse`、`InboundConfigDTO`、`RouteDTO`、`RateLimitDTO`、`UserDTO`、`UserDtoWithCredential`、`AccessLog`、`AuthLog`。
- `backend/common/src/main/java/org/congcong/common/enums`：协议、路由、匹配、限流、DNS 等枚举。新增协议或策略时通常先从这里开始。
- `backend/common/src/main/java/org/congcong/common/util/geo`：域名规则、广告规则、GeoIP 解析和规则加载。`DomainRuleEngine` 是 worker 路由判断的核心入口。

## 后端 control-manager

启动入口：

- `ControlManagerApplication`：Spring Boot 主类，启用缓存。
- `src/main/resources/application.properties`：默认端口、MySQL、Flyway、JWT、ClickHouse、日志落库配置。
- `config/SelfSignedTlsConfiguration`：启动时生成临时自签名 HTTPS 密钥库；worker 保持 HTTPS 但不校验管理端证书。
- `src/main/resources/db/migration`：Flyway 脚本，覆盖管理员、核心配置、日志、聚合统计、WOL、DNS、入站路由绑定、邮件网关、计划任务等表。

主要控制器：

- `AdminController`：`/admin` 登录、当前用户、改密、登出、管理员维护。
- `AggregateConfigController`：`/api/config/aggregate`，向 worker 发布聚合配置，支持 ETag/304。
- `UserController`、`RouteController`、`InboundConfigController`、`RateLimitController`：代理核心配置 CRUD。
- `DnsRuleController`：DNS 规则接口占位，目前只注册 `/api/dns/rule` 根路径。
- `LogController`、`UserTrafficStatsController`：访问日志查询、明细、TopN、时间序列、用户流量统计。
- `WolController`、`DiskMonitorController`：WOL 配置/唤醒/监控和磁盘状态。
- `MailGatewayAdminController`、`MailTargetAdminController`、`MailSendLogController`、`InternalMailController`、`SchedulerController`：邮件网关、收件目标、发送日志、内部发送和计划任务。

服务与数据层：

- `AggregateConfigService`：聚合所有启用的入站、路由、限流、用户配置，并计算配置 hash。
- `AggregateConfigCacheService`：缓存聚合配置，供配置分发接口复用。
- `DataInitializer`：启动时确保默认管理员、匿名用户和兜底路由存在。
- `security`：`SecurityConfig`、`JwtAuthenticationFilter`、`JwtService` 负责 JWT 鉴权；聚合配置、日志上报、内部邮件等接口有显式放行规则。
- `entity` + `repository`：JPA 实体和仓储；聚合统计实体在 `entity/agg`，邮件实体在 `entity/mail`，计划任务在 `entity/scheduler`。
- `logstore` + `clickhouse`：访问日志查询与写入走 `AccessLogStoreFactory`；当前工厂固定返回 `ClickHouseAccessLogStore`，项目中仍保留 `MySqlAccessLogStore` 实现。

## 后端 proxy-worker

启动与配置拉取：

- `ProxyWorkerApplication`：初始化 `DomainRuleEngine`、启动日志上报、启动配置拉取服务，并在关闭钩子中释放代理服务。
- `config/ProxyWorkerConfig`：读取 `proxy-worker.properties`，拼出控制端地址、聚合配置 URL、日志上报 URL 和可选 TLS 证书路径。
- `service/AggregateConfigService`：每 30 秒拉取 `/api/config/aggregate`；配置变化后通知 listener。
- `http/HttpClientManager`：执行 HTTP 请求和 ETag 缓存。
- `ProxyWorkerApplication.ConfigChangeListener`：把 `common` DTO 转换为 worker 运行时配置，构建用户、IP、路由映射，然后调用 `server/ProxyContext.refresh`。
- `docs/testing/proxy-worker-testing.md`：proxy-worker 单测、集成测试与核心场景回归矩阵。

代理服务生命周期：

- `server/ProxyContext`：按入站配置 ID 管理正在运行的 Netty 服务；配置变化时关闭旧服务并启动新服务。
- `server/factory/ProxyServerFactory`：按 `ProtocolType` 创建 SOCKS5、HTTP CONNECT、Shadowsocks、透明代理、DNS 服务器。
- `server/ProxyServer`：Netty 服务启动/关闭模板，优先使用 epoll，透明代理依赖 `IP_TRANSPARENT`。
- `protocol/*`：各协议解析器把入站请求归一成 `ProxyTunnelRequest`。
- `router/RouterService`：按用户绑定的路由规则匹配 `GEO`、`DOMAIN`、`AD_BLOCK`，未命中时使用兜底路由。
- `outbound/OutboundConnectorFactory`：根据 `RoutePolicy` 和出站协议选择直连、阻断、DNS 重写、上游 HTTP/SOCKS5/Shadowsocks/DoT/DNS 连接器。
- `protocol/ProxyTunnelConnectorHandler`：建立出站连接，按协议写回成功或失败响应，并设置双向 relay。
- `telemetry/TelemetryPublisher`：新版四类日志缓冲后经 worker 管理 WSS 批量上报，受节点 `logReportingEnabled` 和部署总开关共同控制；旧 `audit/AccessLogUtil` 采集已固定停用。

## 前端 frontend

应用入口：

- `frontend/src/App.tsx`：路由定义。`/login`、`/change-password` 是独立页面；主要功能挂在 `/config/*` 下。
- `frontend/src/components/ProtectedRoute.tsx`：受保护路由包装。
- `frontend/src/pages/ProxyConfig.tsx`：管理台布局和左侧导航承载页。
- `frontend/src/store/authSlice.ts`：登录、获取当前用户、改密、登出等 Redux async thunk。
- `frontend/src/services/api.ts`：后端 API client，封装 `/admin` 和 `/api` 两类请求，统一附加 Bearer token。

页面到功能的对应关系：

- `Dashboard.tsx`、`TrafficOverview.tsx`、`AccessOverview.tsx`：看板和流量概览。
- `UserManagement.tsx`：用户管理。
- `RouteManagement.tsx` + `components/RouteForm.tsx`：路由策略管理。
- `InboundManagement.tsx`：入站监听配置。
- `RateLimitManagement.tsx`：限流配置。
- `LogAudit.tsx`、`AggregatedAnalysis.tsx`：保留的旧访问日志审计和聚合分析源码；“访问概览”入口已屏蔽，原地址跳转对应新版页面。
- `WolManagement.tsx`、`DiskMonitor.tsx`、`MailGateway.tsx`：WOL、磁盘监控、邮件网关。
- `types/*`：前端接口类型，通常需要和后端 DTO、请求对象同步更新。

## 关键调用链

配置发布链路：

1. 前端配置页调用 `frontend/src/services/api.ts`。
2. `control-manager` 的 controller 调用 service 和 repository 写入数据库。
3. `AggregateConfigService` 聚合启用配置并计算 hash。
4. `AggregateConfigController` 通过 `/api/config/aggregate` 返回配置和 ETag。
5. `proxy-worker` 定时拉取配置，变化时刷新 `ProxyContext` 并重启受影响的入站服务。

代理请求链路：

1. 入站 Netty server 接收连接。
2. 协议 handler 解析认证、目标地址和首次负载，生成 `ProxyTunnelRequest`。
3. `RouterService` 按用户绑定的路由和域名/Geo/广告规则选择路由。
4. `OutboundConnectorFactory` 创建出站连接器。
5. `ProxyTunnelConnectorHandler` 建立出站连接、写回协议响应、设置双向 relay。
6. 连接结束后 `telemetry/SessionObservation` 生成唯一汇总并交给 `TelemetryPublisher`；旧 `AccessLogUtil` 调用保留但不再采集。

日志与看板链路：

1. controller 主动连接 worker 的 `/management/ws`，worker 在 `CONFIG_POLL` 帧中携带配置状态和心跳指标，旧访问／认证日志字段固定为空；V1 日志经独立 `TELEMETRY_BATCH` 上报。
2. `control-manager` 的 telemetry ingest/writer 将新版四类日志写入 ClickHouse 的独立表；旧 `WorkerManagementConnectionService` → `LogService` 路径及 `/api/logs/access`、`/api/logs/auth` 端点保留给兼容 worker。
3. 新版看板／审计页面查询 `/api/observability/v1`；保留的旧源消费者通过 `LogController`、`UserTrafficStatsController` 读取历史数据；网关监控读取 WSS 心跳落库的 `worker_status`。

认证链路：

1. 前端登录页调用 `/admin/login`。
2. `AdminAuthService` 校验管理员并签发 JWT。
3. 前端把 token 存入 `localStorage`。
4. `api.ts` 后续请求带 `Authorization: Bearer ...`。
5. `JwtAuthenticationFilter` 校验 token 和黑名单。

## 常见改动定位

- 新增聚合配置字段：同步改 `common` DTO、control-manager 实体/迁移/service/hash、worker 运行时 config 转换、frontend `types` 和表单。
- 新增协议：先改 `ProtocolType`，再补 control-manager 表单和校验、worker `ProxyServerFactory`、协议 strategy/initializer、出站 connector。
- 新增路由条件或策略：改 `common` enum/DTO、control-manager 路由管理、worker `RouterService` 或 `OutboundConnectorFactory`。
- 新增管理台页面：补 `frontend/src/pages`、`frontend/src/types`、`frontend/src/services/api.ts`，再把路由挂到 `App.tsx` 和 `ProxyConfig.tsx` 导航。
- 新日志模型与网关观测（阶段 A、B1 已实施，B2 及后续待实施）：先读 [双轨演进设计](development/gateway-observability-log-model-design.md) 第 13 节；B1 已交付四类日志的新版审计页面，B2 继续确认关键数据，用户确认可用后进入 C，按入站流量、用户／全局流量、聚合分析、日志入口分批迁移查询；高级看板与风险分析按需独立实施，D 清理旧链路需另行确认。使用独立 DTO、采集／存储／API 和前端分类；流量重点区分实际目标重写至内网的子集与外网，不需额外 NAS 归属配置，并新增用户名密码认证来源审计。B 保留所有旧代码、旧表与旧查询，C 每批保留回退能力及旧采集／代码／表。
- 新增数据库字段：新增 Flyway 脚本，更新 entity、DTO、mapper/service、前端类型。

## 验证入口

- 全量构建：`make build`
- 后端测试：`make backend-test`
- 管理端单测显式开启：`mvn -f backend/pom.xml -pl control-manager test -DskipTests=false`
- worker 打包：`make worker-build`
- worker 单测：`mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false`
- 前端测试：`make frontend-test`
- 前端构建并同步静态资源到管理端：`make frontend-sync-static`

## 网关观测 V1（阶段 A）

- `common/telemetry`：独立事实 DTO、业务流量分类、批次和持久化 ACK。
- `proxy-worker/telemetry`：接收时上下文、唯一结束汇总、成功载荷计数、认证/DNS 基础钩子、有界内存缓冲（无 Worker 日志持久化）。
- `control-manager/telemetry`：独立 `telemetry.*_v1` 初始化、幂等 ingest/query、来源 GeoIP 与分析契约、`/api/observability/v1`。
- `frontend/src/types/observability.ts` 与 `api.ts`：独立类型/API，现有页面保持旧查询。
- [阶段 A 实施与保留数据 E2E](testing/gateway-observability-stage-a.md)：开关、协议、查询、兼容、回退与验收。

## 网关观测 B1：新版日志审计

- `frontend/src/pages/ObservabilityLogAudit.tsx`：网关观测 → 新版日志审计；独立四标签、后端筛选／分页、详情与连接关联、中文连接结果；按用户反馈移除采集状态卡片。
- `control-manager/telemetry/TelemetryQueryRequest`、`TelemetryController`、`TelemetryStore`：新增 `/{type}/page` 和有界稳定 ID 详情；连接在查询时批量关联认证尝试账号，用户名筛选包含失败尝试，不改原始用户身份；旧 V1 数组查询及 `/api/logs` 保留。
- [B1 实施与验证记录](testing/gateway-observability-b1.md)：API 契约、截图、只读 E2E、部署／回退与采集边界。流量概览已按后续用户要求提供 C2 增强预览，入站月度流量按 C1 切换新源，其余统计页面继续使用旧源。

## 入站月度流量（C1）

- `control-manager/telemetry/InboundTrafficService`、`InboundTrafficController`：`/api/observability/v1/traffic/inbound/{inboundId}/month`，按节点／入站／本地月份聚合唯一连接汇总，四分类与历史起点独立于旧日志。
- `frontend/src/pages/InboundManagement.tsx`、`types/inbound.ts`、`services/api.ts`：本月上／下行默认新源，悬停查看分类与历史范围，显式旧源回退；配置 CRUD 保持原行为。
- [统计口径、验证与回退](testing/gateway-observability-inbound-traffic.md)：结束月份归属、月份／时区、大数、空历史与查询失败；旧采集、旧接口和旧表保留。

## 流量概览增强预览（C2）

- `frontend/src/pages/TrafficOverview.tsx`、`TrafficOverview.css`、`types/overview.ts`：默认外网；四个 ECharts 面板、来源／结果筛选、当前并发与来源排行；`LegacyTrafficOverview.tsx` 保留原页面作为回退入口，Dashboard 嵌入同一新版组件。
- `control-manager/telemetry/OverviewController`、`OverviewQuery`、`OverviewService`、`ConcurrencyTimeline`：`/api/observability/v1/overview` 及 `/live`；从唯一 sessions／DNS／认证事实直接聚合，历史并发按相交连接区间扫描，不受审计分页限制。
- `common/telemetry/EstablishedConnectionsV1`、`proxy-worker/telemetry/EstablishedConnectionRegistry`、`service/WorkerMetricsCollector`：已建立业务连接的分类／来源快照，经现有心跳 `metrics_json` 上报；不增加配置或日志表。
- [实施、截图与验证](testing/gateway-observability-overview.md)：历史与当前口径、内外网筛选边界、空值与结果分组、真实连接 E2E、回退步骤。

## 新版连接阶段耗时

- `proxy-worker/telemetry/SessionObservation`、`TelemetryHooks`：单调时钟阶段计时、结束冻结、协议请求就绪和中继背压。
- `proxy-worker/telemetry/OutboundTimingHandler`、`TimingAddressResolverGroup`：实际 socket connect、系统解析与出站协议计时；自定义 DNS 在 direct／endpoint resolver 中计时。
- `scripts/e2e/gateway-observability-session-timing-e2e.py`（`make e2e-observability-session-timing`）：本地真实上游／失败／背压／SNI 流量，保留日志并禁用临时入站；同目录 `gateway-observability-session-timing-browser.cjs` 只读核对实际详情。
- [连接阶段耗时采集与验证](testing/gateway-observability-session-timing.md)：客户端握手、目标恢复、选路、DNS、TCP、REALITY TLS、上游代理握手和双向背压的采集边界；移除无执行来源的限流字段。

## 网关观测：访问分布预览

- `frontend/src/pages/ObservabilityDistribution.tsx`、`types/distribution.ts`：独立六维度 TopN，连接数／上行／下行／总流量，全量分母与其余项目；旧聚合分析链接已隐藏。
- `control-manager/telemetry/DistributionQuery`、`DistributionService`、`DistributionController`：`/api/observability/v1/distribution`，唯一结束连接聚合、UInt128 累加、93 天范围、缺失维度计数与节点历史起点；不改采集和旧 API。
- [业务取舍、口径、测试与回退](testing/gateway-observability-distribution.md)：暂不迁移国家维度，连接与 DNS／认证单位分开；真实 ClickHouse 只读验证、UI 样例及真实数据截图，已更新本地管理端容器。

## 仅上报新版日志与旧页面屏蔽

- `frontend/src/App.tsx`、`pages/ProxyConfig.tsx`：隐藏“访问概览”，旧审计／聚合地址跳转新版；保留旧页面源码、后端 API 与数据。
- `frontend/src/pages/WorkerManagement.tsx`：原“代理网关日志上报”沿用 `logReportingEnabled`，通过聚合配置动态控制 `TelemetryPublisher` 的 V1 四类日志采集／发送；与 `OBSERVABILITY_ENABLED` 部署总开关共同生效。
- `AccessLogUtil` 的旧采集固定关闭，`WorkerManagementWebSocketServer` 心跳帧不携带旧日志；旧代码／API／历史数据保留。
- [配置与生效边界](testing/gateway-observability-stage-a.md#仅上报新版日志)：关闭暂停新版待确认批次，重新开启继续发送；恢复旧采集需回退 Worker 版本。
