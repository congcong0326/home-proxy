# 日志类型与字段精简设计（V1 日志）

> **2026-10-30 回退第 2.2 节**：认证成功／挑战／取消记录恢复上报与入库，总览、认证地理分布、认证审计恢复成功／失败分组，巡检邮件恢复“认证成功位置与变化”。已验证用户写入公共字段 `userId`／`username`，不恢复 `verifiedUsername` 列。本文其余精简（诊断事件、字段删减）不变。
>
> 状态：已实施（三个 PR 合并交付；ClickHouse 清理脚本 `scripts/clickhouse/telemetry-slimming-cleanup.sql` 需在所有 Worker 升级后手工执行）。与《[Worker 遗留 HTTP 调用与旧日志链路清理设计](worker-legacy-http-removal-design.md)》互相独立，可分开发布。
> 相关背景：[代理网关日志模型与双轨演进设计](gateway-observability-log-model-design.md)。

## 1. 背景与目标

日志的核心用途只有两个：**访问报错时看原因**、**统计流量与趋势**。现有 V1 日志（连接、DNS、诊断事件、认证）中有大量只服务于内部实现、恒定不变或与其他字段重复的内容，既增加了采集、存储和页面的复杂度，也让使用者看不懂。本次做四件事：

1. 删除整个**诊断事件**日志类型。
2. **认证审计只上报失败**记录，成功的不再上报。
3. 删除**连接日志**及公共字段中无价值的字段。
4. 连接详情页精简展示，阶段耗时折叠。

硬性要求：**升级后新旧 Worker 混跑也不能报错、不能停止采集**（见 §3，这是本方案最关键的部分）。

## 2. 需求清单

### 2.1 删除诊断事件日志类型

诊断事件（`ProxyEventLogV1`，表 `telemetry.proxy_event_log_v1`）只记录 `CONNECT_RESULT`、`FIRST_ERROR`、`ERROR_SUMMARY`、`TARGET_RECOVERY_RESULT` 四种事件，内容全部已包含在连接日志里。除审计页的分页查询外，没有任何统计、看板或邮件使用它。

| 位置 | 处理 |
|---|---|
| `common/.../telemetry/ProxyEventLogV1.java` | 删除类 |
| `common/.../telemetry/TelemetryBatchV1` | `events` 字段**不能直接删**，见 §3.1；改为已废弃的忽略字段，`recordCount()` 不再计入 |
| `proxy-worker/.../telemetry/SessionObservation` | 删除 `event()` 方法、`eventsEnabled` 构造参数、`sequence`、`connectResultEmitted`、`connectResult()`，以及 `connected()`、`connectFailed()`、`error()`、`finishIfReady()` 中对它们的调用；`error()` 仍需记录首个错误码和失败阶段 |
| `proxy-worker/.../telemetry/TelemetryHooks` | 删除第 170 行附近的 `state.event("TARGET_RECOVERY_RESULT", ...)`，并更新 `new SessionObservation(...)` 调用 |
| `proxy-worker/.../telemetry/TelemetryBuffer` | 删除 `ProxyEventLogV1` 相关分支和 `events` 列表 |
| `proxy-worker/.../telemetry/TelemetryPublisher` | 删除 `eventsEnabled` 字段、`eventsEnabled()` 方法和环境变量 `OBSERVABILITY_EVENTS_ENABLED`（保留读取并忽略不需要，环境变量没人读就不会影响启动） |
| `control-manager/.../telemetry/TelemetrySchema` | 删除 `EVENTS` 枚举值 |
| `control-manager/.../telemetry/TelemetryStore` | 删除 `groups.put(EVENTS, ...)`（第 121 行）、`INVALID_EVENT_ID` 校验（第 180 行）、`eventType` 查询过滤（第 406～419 行）。**注意第 99～101 行的 `retention` 数组按 `schema.ordinal()` 取值，顺序是 `[会话, DNS, 事件, 认证]`，删除 `EVENTS` 后 `AUTH` 的下标从 3 变成 2，必须同步改成三个元素，否则认证表会拿到错误的保留天数或下标越界** |
| `control-manager/.../telemetry/TelemetryProperties` | 删除 `eventRetentionDays`；对应配置项若存在也一并删除 |
| `control-manager/.../telemetry/TelemetryQueryRequest` | 删除 `eventType` |
| `deploy/docker/docker-compose.worker.yml` | 删除 `OBSERVABILITY_EVENTS_ENABLED` |
| 前端 `ObservabilityLogAudit.tsx`、`types/observability.ts`、`services/api.ts` | 删除"诊断事件"标签；`TelemetryType` 去掉 `events`；删除 `eventTypes`、`eventOutcomes`、`outcomeLabels.events`、`inboundProtocols.events`、`otherFields.events`、事件类型筛选框、列表"事件／阶段"列；关联日志 `relatedType` 默认值（第 301 行附近）从 `events` 改成 `dns` |
| 测试 | 删除 `SessionObservationTest` 中事件断言（保留对连接汇总字段的断言）、`TelemetryPublisherTest`、`TelemetryBufferClickHouseIT` 里的事件用例，前端 `ObservabilityLogAudit.test.tsx` 中事件相关用例 |

### 2.2 认证审计只上报失败

**上报范围**：只发送 `authOutcome ∈ {FAILURE, DENIED, MALFORMED, ERROR}` 的记录。

`SUCCESS`（认证通过）、`CHALLENGE`（HTTP 代理首次不带凭据收到 407 的正常握手）、`CANCELLED`（客户端取消）不再上报。后两者不属于失败，且 `CHALLENGE` 是浏览器每次连接都会发生的正常流程，噪声很大。

**Worker（`TelemetryHooks.authentication`）**

- 只改"是否发布日志"，**不能改变其副作用**：`SUCCESS` 时仍要给连接汇总设置 `userId`、`username`、`identitySource = ACCOUNT_AUTH`；失败类仍要调用 `state.error("AUTH", reason)`。
- 实现上建议先处理副作用，再判断是否属于失败类，只有失败类才构造并 `publish` 一条 `CredentialAuthLogV1`，避免无谓创建对象。
- `CredentialAuthLogV1` 删除 `verifiedUsername`（只在成功时才有值）。其余字段（`authAttemptId`、`authMethod`、`authEventKind`、`attemptedUsername`、`authOutcome`、`reasonCode`、`authDurationUs`）保留。

**控制端：依赖"成功认证"的功能需要同步调整**

| 位置 | 处理 |
|---|---|
| `service/mail/inspection/AuthLocationInspectionService`、`InspectionReportService`（第 64 行）、`docs/development/mail-inspection.md` | 删除巡检邮件中的"认证成功位置与变化"一节，包含对应的邮件预览与测试。如需保留一个认证相关小节，可选新增"认证失败概览"（窗口内失败次数、来源 IP／国家 Top5、被尝试账号 Top5），非必须，可后续再做 |
| `telemetry/CredentialDistributionService`、`DistributionQuery`、`DistributionController` | 认证地理分布只统计失败：删除 `authOutcome`（ALL／SUCCESS／FAILURE）参数，去掉 `success`、`other` 列，只保留失败次数；SQL 条件固定为 `auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR')`，这样**历史遗留的 `SUCCESS` 行不会混入统计** |
| `telemetry/OverviewService`（`AUTH_STATUS`）、`OverviewQuery.authResult`、`OverviewController` | 总览的认证面板只统计失败（同样固定 `auth_outcome` 失败条件），删除 `authResult` 过滤参数及 `CHALLENGE`、`CANCELLED`、`UNKNOWN` 分类 |
| `telemetry/TelemetryStore`（第 396、412、413 行）、`TelemetryQueryRequest.authOutcomeGroup` | 认证审计列表的结果分组去掉 `SUCCESS`，只保留失败类 |
| 前端 `TrafficOverview.tsx`（第 215、232 行） | 认证卡片由"认证尝试 成功 N／失败 N"改为"认证失败 N 次"，删除认证结果下拉；趋势图标题改为"认证失败趋势" |
| 前端 `ObservabilityDistribution.tsx`（第 83、120、150、162、193、211、230 行附近） | 删除"认证结果"筛选、"成功"列及提示文字里的"成功／其他"，认证次数文案改为"认证失败次数" |
| 前端 `ObservabilityLogAudit.tsx` | 认证审计标签的结果筛选选项只留失败类 |
| `verified_username` 列的引用 | `CredentialDistributionService:54`、`OverviewService:237`、`TelemetryStore:392,410` 的 SQL 都引用了 `verified_username`，字段删除后**新建表里没有这一列，SQL 会直接报错**。必须同步删掉这些条件里的 `verified_username`（用户名过滤只比较 `attempted_username` 与 `username`） |

### 2.3 连接日志与公共字段精简

**A. 公共字段（`TelemetryRecordV1`，同时影响连接、DNS、认证三类日志）**

| 删除字段 | 原因 |
|---|---|
| `clientIpSource` | Worker 里只会写 `TRANSPORT_PEER` 一个值 |
| `transportPeerIp`、`transportPeerPort` | 与 `clientIp`、`clientPort` 取自同一个 `channel.remoteAddress()`，完全相同 |
| `configVersion` | 只是整份配置的哈希，页面没有任何价值 |
| `ruleSetVersion` | Worker 从未上报，永远为空 |

配套改动：

- `TelemetryHooks`（第 30、36、224～227 行）、`DnsObservation`（第 30、35 行）、`SessionObservation` 中对应的 `set...` 一并删除。
- `RouteSelector`（第 37 行）生成 `matchedRuleId` 时用到 `inboundConfig.getConfigVersion()`：格式由 `配置哈希:路由ID:规则序号` 改为 **`路由ID:规则序号`**。`InboundConfig.configVersion` 本身是非日志用途的配置字段，保留不动。历史行里的旧格式只是文本，无需迁移。
- 前端 `commonFields`、`detailLabels`、`fieldHelp` 删除对应条目，并删除 `ruleSetVersion` 为空时显示"未上报规则集版本"的特殊处理。

**B. 连接日志 `SessionLogV1`**

直接删除：

| 字段 | 原因 |
|---|---|
| `errorMessage` | Worker 从不赋值（防泄露凭据），永远为空 |
| `closeInitiator` | 初始化为 `UNKNOWN` 后从未修改 |
| `closeReason` | 有错误时等于 `errorCode`，否则固定 `CHANNEL_CLOSED`，与错误码重复 |
| `outboundMode` | 与 `routeAction` 取值完全一致 |
| `channelReadyBasis`、`targetConfirmationLevel` | 仅由路由动作推导出的两个固定值 |
| `proxyHandshakeOutcome` | 只有 `FAILED`／`CANCELLED`，与失败阶段加错误码重复 |
| `trafficClassificationVersion` | 常量 1 |
| `trafficCategoryBasis`、`targetAddressClass` | 流量分类的中间结果 |
| `targetNetworkScope`、`clientNetworkScope`、`outboundPeerNetworkScope` | 同上，控制端无任何查询使用 |

建议删除（调优才用，对排查报错和统计流量无帮助）：

| 字段 | 原因 |
|---|---|
| `routeSelectionDurationUs` | 微秒级，几乎为 0 |
| `uploadBackpressureDurationUs`、`downloadBackpressureDurationUs` | 调 Netty 性能才需要 |
| `firstUploadAt`、`firstDownloadAt` | 调试时序用 |
| `routeSelection` | 只有"默认／规则命中"两个值，看命中规则是否为空即可判断 |

**保留** `lastActivityAt`（排查长连接空闲断开时偶尔有用）、`recordId`（后端通用主键，仅在详情页隐藏）。

配套改动：

- `common/.../telemetry/TrafficClassifierV1`：不再把中间结果写入记录。`classify()` 内部用局部变量计算目标／来源地址范围，只设置 `trafficCategory`；移除 `VERSION`、`addressClass` 写入和 `...BASIS` 赋值。`NetworkScope` 枚举保留（认证来源分析 `sourceNetworkScope` 仍使用）。
- `SessionObservation`：删除 `ROUTE_SELECTION`、`UPLOAD_BACKPRESSURE`、`DOWNLOAD_BACKPRESSURE` 三个阶段及相关 `case`、`relayStarted()` 对背压字段的初始化；`close()` 不再设置 `proxyHandshakeOutcome` 和 `closeReason`。注意**不要**删除 `begin()`／`end()` 机制本身，其他阶段耗时仍然需要。
- `OutboundTimingHandler`、`TelemetryHooks` 中对已删除阶段、字段的 `begin/end/set` 调用一并清理（编译器会提示）。
- `SessionLogV1` 上现有的 `@JsonIgnoreProperties({"rateLimitPolicyId", "rateLimitWaitUs"})` 是上一次删字段时的兼容做法，本次统一改为 §3.1 的 `ignoreUnknown = true`。
- 前端 `ObservabilityLogAudit.tsx` 的 `unavailableSessionDuration()` 用 `outboundMode` 判断"此出站不使用上游代理"，改用 `routeAction`（取值与原 `outboundMode` 相同）。
- `scripts/e2e/gateway-observability-*.py`、`*.cjs` 中引用已删字段、事件表（`proxy_event_log_v1`、`eventType`）、认证成功记录的断言要同步修改。

**C. `CredentialGeoAnalysis` 的来源分析（`analysis_json`）**

`CredentialAuthAnalysisV1` 中 `riskRuleVersion`（固定 `"contract-v1"`）、`baselineStatus`（固定 `"BASELINE_INSUFFICIENT"`）、`comparisonBasis` 都是常量，基线对比依赖成功认证记录而成功记录已不再上报；`riskTags` 仅由地理查询状态派生。以上四个字段删除（删 `riskTags` 前先读 `CredentialGeoAnalysis.java` 第 80～90 行确认没有别的来源），前端"来源分析"卡片相应去掉"基线状态""风险规则版本""风险标签"，保留国家、地区、城市、位置查询状态、GeoIP 版本、来源网络范围。

### 2.4 连接详情页展示精简（前端）

不删数据，只调整展示：

- 去掉 §2.3 里已删字段。
- "时间与耗时"分组默认只显示：接收时间、通道就绪时间、结束时间、末次活动、连接寿命、通道建立耗时。
- 阶段耗时（客户端握手、目标恢复、DNS、出站 TCP、出站 TLS、代理握手）放进默认折叠的"阶段耗时"面板（antd `Collapse`）。
- "失败原因"：在结果分组里把"失败阶段 + 错误码"合并展示成一句中文，例如"出站建连失败：出站连接失败（CONNECT_FAILED）"，复用现有的 `failureStages`、`errorCodes` 映射。
- 对应修改 `ObservabilityLogAudit.test.tsx`。

## 3. 兼容性设计（必读）

### 3.1 解析策略：对未知字段必须宽松（先行修复）

**原则**：跨版本传输的 JSON（Worker 与控制端之间的所有消息）必须"宽进"——遇到不认识的字段直接忽略，不能因为多一个字段就拒收整条消息。这样今后任何一侧增删字段，都不会因为版本错开而出问题。

**现状有缺陷**：Jackson 默认是严格模式（`FAIL_ON_UNKNOWN_PROPERTIES = true`）。控制端解析 Worker 消息用的是手工创建的 `WorkerManagementConnectionService.objectMapper`（`new ObjectMapper()`，没有关闭这个开关；注意它不是 Spring Boot 自动配置的那个，Spring Boot 的默认配置是宽松的），`common` 里的 V1 DTO 除 `SessionLogV1`（只忽略了两个历史字段）外也没有 `@JsonIgnoreProperties`。Worker 侧的 mapper 已经是宽松的（`ProxyWorkerApplication` 第 78 行），只有控制端这一侧不对。

如果不修，字段删除后的后果是：旧 Worker 仍会发送这些字段，控制端 `readValue` 抛异常，外层 `catch` 吞掉，整批日志既不入库也不回 ACK；Worker 持续重发，缓冲区堆满，新版日志采集停止。

**必须先行的兼容改动（第一批发布，随控制端）：**

1. **改成宽松解析**：给 `WorkerManagementConnectionService.objectMapper` 增加 `.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)`，并检查控制端其他解析 Worker 报文的 `ObjectMapper`（如 `TelemetryStore.mapper`）是否也有同样问题；同时在 `TelemetryRecordV1`（子类继承）、`SessionLogV1`、`DnsQueryLogV1`、`CredentialAuthLogV1`、`CredentialAuthAnalysisV1`、`TelemetryBatchV1`、`WorkerManagementMessage` 上统一加 `@JsonIgnoreProperties(ignoreUnknown = true)`，两层都做，不依赖某一个 mapper 的配置。`SessionLogV1` 现有的 `@JsonIgnoreProperties({...})` 一并替换。
2. `TelemetryBatchV1.events` 保留一个**已废弃、仅用于忽略**的字段（类型用 `List<com.fasterxml.jackson.databind.JsonNode>`，注释说明原因），不再参与 `recordCount()`、不写库。控制端要**接收并丢弃**旧 Worker 发来的事件，而不是靠"未知字段"路径隐式处理，这样可以明确统计丢弃数量。
3. 旧 Worker 发来的认证记录里，`authOutcome` 不属于失败类（`SUCCESS`、`CHALLENGE`、`CANCELLED`）的，在 `TelemetryStore.ingest` 里**接收并丢弃**，不入库，以保证新旧 Worker 数据口径一致。
4. **全部记录都被丢弃的批次要返回成功 ACK**。当前 `ingest()` 在 `recordCount() < 1` 时抛 `INVALID_BATCH`，如果一个旧 Worker 批次里只有事件或成功认证，过滤后记录数为 0，会得到失败 ACK，Worker 可能反复重试甚至把批次判为有毒数据。改为：先校验 `batchId`、协议版本和大小，再过滤，过滤后为空时直接返回 `persisted = true` 的 ACK，不进入缓冲区。大小、条数上限的校验仍按原始批次计算。
5. 所有读取 `verified_username`、`event` 表、已删列的 SQL 都改完后再发布（见 §2.2 末行），否则新表缺列会导致查询报错。

**反向兼容（新 Worker 对旧控制端）**：新 Worker 只是少发字段、不发 `events`，旧控制端把缺失的字段按 `null` 处理，`TelemetryBatchV1.events` 默认值是 `List.of()`，不会出错；被删列在表里只是写入 `NULL`（列均为 `Nullable`）。旧控制端的认证统计里不会再看到成功记录，属预期。

### 3.2 发布顺序

1. **先发控制端**（含第 3.1 节全部兼容改动）。此时旧 Worker 照常上报，被忽略的字段、事件和成功认证记录被安全丢弃，页面上诊断事件标签消失。
2. **再发 Worker**，多个 Worker 可以逐个灰度，顺序任意。
3. 所有 Worker 升级后，方可执行 ClickHouse 清理脚本（§4 第二步）。

如果必须在控制端之前先升级 Worker，也不会出现数据丢失，只是旧控制端会把认证统计里的成功次数显示为 0。

### 3.3 回滚

- 回滚控制端：新 Worker 继续上报，不会出错（见反向兼容）；但在执行 §4 第二步清理脚本之后就**不能回滚**到旧版本，因为旧代码会向已被删除的列写入。
- 回滚 Worker：旧 Worker 会重新发送被删字段，新控制端按 §3.1 忽略，不受影响。

## 4. ClickHouse 升级脚本

控制端启动时按 `TelemetrySchema.ddl()` 执行 `CREATE TABLE IF NOT EXISTS`，列由 DTO 字段反射生成，写入的列也由 DTO 决定。因此**字段删除后，新写入的数据就不再包含它们**，无需额外开关，也无需启动时做表结构迁移。

**第一步（随本次发布）：不改表结构。** 旧列保留，今后新数据在这些列上为 `NULL`；ClickHouse 按列存储，全 `NULL` 的列压缩后几乎不占空间。旧数据的详情来自 `raw_json`，前端只展示字段清单内的字段，已删字段自然不再显示，**无需**重写历史数据。好处是仍可随时回滚。

**第二步（所有 Worker 升级并稳定运行后，手工执行）：** 新增 `scripts/clickhouse/telemetry-slimming-cleanup.sql`，**不放进启动流程自动执行**（删表删列不可恢复），并在 README 与测试文档中说明执行前提和不可回滚的风险。

```sql
-- 1. 诊断事件表整表删除
DROP TABLE IF EXISTS telemetry.proxy_event_log_v1;

-- 2. 连接日志专属字段
ALTER TABLE telemetry.proxy_session_log_v1
  DROP COLUMN IF EXISTS error_message,
  DROP COLUMN IF EXISTS close_initiator,
  DROP COLUMN IF EXISTS close_reason,
  DROP COLUMN IF EXISTS outbound_mode,
  DROP COLUMN IF EXISTS channel_ready_basis,
  DROP COLUMN IF EXISTS target_confirmation_level,
  DROP COLUMN IF EXISTS proxy_handshake_outcome,
  DROP COLUMN IF EXISTS traffic_classification_version,
  DROP COLUMN IF EXISTS traffic_category_basis,
  DROP COLUMN IF EXISTS target_address_class,
  DROP COLUMN IF EXISTS target_network_scope,
  DROP COLUMN IF EXISTS client_network_scope,
  DROP COLUMN IF EXISTS outbound_peer_network_scope,
  DROP COLUMN IF EXISTS route_selection_duration_us,
  DROP COLUMN IF EXISTS upload_backpressure_duration_us,
  DROP COLUMN IF EXISTS download_backpressure_duration_us,
  DROP COLUMN IF EXISTS first_upload_at,
  DROP COLUMN IF EXISTS first_download_at,
  DROP COLUMN IF EXISTS route_selection;

-- 3. 认证日志专属字段
ALTER TABLE telemetry.credential_auth_log_v1
  DROP COLUMN IF EXISTS verified_username;

-- 4. 公共字段：连接、DNS、认证三张表各执行一次
ALTER TABLE telemetry.proxy_session_log_v1
  DROP COLUMN IF EXISTS client_ip_source,
  DROP COLUMN IF EXISTS transport_peer_ip,
  DROP COLUMN IF EXISTS transport_peer_port,
  DROP COLUMN IF EXISTS config_version,
  DROP COLUMN IF EXISTS rule_set_version;
-- telemetry.dns_query_log_v1、telemetry.credential_auth_log_v1 同上
```

说明：这些列都不在 `ORDER BY`（`worker_id, worker_instance_id, occurred_at, record_id`）和分区键里，可以直接 `DROP COLUMN`。另外，历史 `SUCCESS` 认证行不需要清理，所有认证统计查询都已按失败类过滤；如要释放空间，可单独执行 `ALTER TABLE ... DELETE WHERE auth_outcome IN ('SUCCESS','CHALLENGE','CANCELLED')`，它是异步 mutation，低峰期执行。

## 5. 实施步骤（建议分三个 PR，各自可独立发布）

**PR 1：兼容层（只改控制端和 common，不删任何字段）**
- §3.1 第 1、2、3、4 点（宽松解析、`events` 忽略、成功认证丢弃、空批次 ACK）。这一项与《Worker 遗留 HTTP 调用与旧日志链路清理设计》的控制端清理共用同一处宽松解析改动，谁先做谁落地，另一份直接复用。
- 补测试：旧格式批次（带 `events`、带将被删除字段、只含事件、只含成功认证）能正常返回 ACK 且不影响其他记录。

**PR 2：删除诊断事件与认证只发失败**
- §2.1、§2.2 全部内容，包括邮件巡检、分布页、总览页、审计页、`retention` 数组下标修正、部署文件。

**PR 3：字段精简与详情页**
- §2.3、§2.4，以及 `scripts/clickhouse/telemetry-slimming-cleanup.sql`（此 PR 只提交脚本，不自动执行）。
- 文档同步：`docs/code-index.md`（日志与看板链路、网关观测 B1 描述、`proxy-worker/telemetry` 描述）、`docs/development/gateway-observability-log-model-design.md`、`docs/development/mail-inspection.md`、`docs/testing/gateway-observability-*.md`（含 `OBSERVABILITY_EVENTS_ENABLED` 的说明）、`docs/development/README.md`。

## 6. 验证

**构建与单元测试**
- `make backend-test`、`mvn -pl backend/control-manager test -DskipTests=false`、`make frontend-test`、`make build` 全部通过。
- 全局搜索无残留：`ProxyEventLogV1`、`eventType`、`TelemetrySchema.EVENTS`、`eventsEnabled`、`verifiedUsername`、`clientIpSource`、`transportPeer`、`configVersion`（仅 `InboundConfig` 内允许保留）、`ruleSetVersion`、`trafficCategoryBasis`、`outboundMode`、`OBSERVABILITY_EVENTS_ENABLED`。
- 新增针对 `TelemetryStore.ingest` 的兼容用例：①旧格式完整批次；②只有事件的批次；③只有成功认证的批次；④带未知字段的批次。四种都应返回成功 ACK，且只有会话、DNS、失败认证入库。
- 针对 `retention` 数组：配置三种保留天数，断言各表的 TTL 与配置一一对应。

**混跑验证（必做）**
1. 用 PR 3 之前的 Worker 镜像加新控制端运行，访问一段流量并故意输错一次代理密码。检查：控制端无解析异常日志、`/api/observability/v1` 状态中的待确认批次不堆积、连接与 DNS 日志正常入库、仅失败认证入库、没有任何事件入库。
2. 新 Worker 加旧控制端运行同样场景：不报错、日志入库。
3. 新 Worker 加新控制端：连接日志不含已删字段；一次成功认证不产生认证记录；该连接的用户身份仍被识别；故意输错密码产生一条失败记录，并在连接详情"尝试登录账号"里能看到。
4. 混跑期间停止/重启控制端，确认 Worker 缓冲重发后正常 ACK。

**页面验证**
- 日志审计页：只有连接、DNS、认证审计三个标签；连接详情没有已删字段，阶段耗时默认折叠，失败连接有一句话的失败原因。
- 总览页、分布页：认证只展示失败，没有成功列和认证结果筛选。
- 巡检邮件预览：不再有"认证成功位置与变化"一节，其他小节正常。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 版本错开时控制端因未知字段拒收批次、Worker 缓冲堆满 | §3.1 改为宽松解析，必须先发布并测试通过 |
| 删除 `EVENTS` 后 `retention` 下标错位，认证表 TTL 取错 | §2.1 已列明，并要求新增 TTL 对应关系的单元测试 |
| 新表缺列导致仍引用 `verified_username` 的 SQL 报错 | §2.2 列出四处位置，并要求在全局搜索中确认 |
| 失去"异地登录"邮件提醒 | 已与需求方确认接受；如需保留，可后续另做"成功认证按用户＋来源 IP＋小时聚合上报" |
| 清理脚本执行后无法回滚 | 脚本只手工执行，README 与测试文档写明前提：所有 Worker 已升级且稳定运行 |
