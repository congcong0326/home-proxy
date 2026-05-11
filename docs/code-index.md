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
- `audit/AccessLogUtil` + `audit/impl/AsyncHttpLogPublisher`：把访问日志异步上报到管理端。

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
- `LogAudit.tsx`、`AggregatedAnalysis.tsx`：访问日志审计和聚合分析。
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
6. 连接结束后 `AccessLogUtil` 上报访问日志。

日志与看板链路：

1. worker 上报 `/api/logs/access`。
2. `control-manager` 的 `LogService` 通过 `AccessLogStoreFactory` 写入当前日志 store；现状是 ClickHouse。
3. 看板页面通过 `LogController`、`UserTrafficStatsController` 查询明细、TopN、时间序列和聚合统计。

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
- 新增数据库字段：新增 Flyway 脚本，更新 entity、DTO、mapper/service、前端类型。

## 验证入口

- 全量构建：`make build`
- 后端测试：`make backend-test`
- 管理端单测显式开启：`mvn -f backend/pom.xml -pl control-manager test -DskipTests=false`
- worker 打包：`make worker-build`
- worker 单测：`mvn -f backend/pom.xml -pl proxy-worker -am test -DskipTests=false`
- 前端测试：`make frontend-test`
- 前端构建并同步静态资源到管理端：`make frontend-sync-static`
