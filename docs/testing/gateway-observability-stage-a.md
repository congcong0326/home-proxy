# 网关观测阶段 A：契约、实施与保留数据 E2E

更新日期：2026-10-09。对应 [双轨设计](../development/gateway-observability-log-model-design.md) 的阶段 A；后续按第 13 节调整为 B 新版日志审计与数据确认、C 逐项迁移现有查询、D 独立清理旧链路，其中 B1 已实施，见 [B1 审计页面记录](gateway-observability-b1.md)；B2／C／D 尚未实施。完整采集、专题看板与高级异常来源分析按实际功能依赖逐步补齐，不作为开始查询迁移的统一前置条件。

用户后续确认采用内网及时消费、有界内存缓冲方案。当前版本移除 Worker 日志磁盘缓存，交付语义为 `BEST_EFFORT_WITH_ACK`；不要求或承诺 Worker 重启恢复待确认日志。首版磁盘缓冲及 39 个事实的重启恢复测试仅为历史验证，已由本次内存方案替代，原先落库的记录继续保留。

## 范围与边界

新增 `common.telemetry` 四种事实 DTO、认证分析契约、分类枚举、批次和 ACK，管理端独立 `telemetry` 数据库/初始化/writer/query，以及 Worker 独立上下文、不可变快照和有界内存缓冲。基础埋点覆盖 TCP 接收、路由快照、通道就绪/失败、唯一关闭汇总、成功载荷写回调、SOCKS5 PASSWORD、HTTP Basic 和客户端 A/AAAA 应答路径。

阶段 A 实施时保留现有 AccessLog/AuthLog、队列、WSS 旧字段、writer/store、default.access_log/auth_log、旧 API 和页面，新计数器独立于旧计数器和旧日志开关，前端仅新增独立类型与 client 方法。2026-10-09 后续按用户要求停止旧采集／发送并将现有日志开关接入 V1，当前行为见下文“仅上报新版日志”；密码检查、锁定和协议响应不变。

未观测字段保持 null/UNKNOWN，包括 DNS 缓存、真实上游尝试、连接细分阶段耗时、限流/背压、代理握手确认等。没有完整恢复失败事件、非 A/AAAA 或内部 DNS 覆盖。普通上游代理的真实目标 IP 保持未知，业务分类按 OUTBOUND_PROXY_ROUTE 为 EXTERNAL；connector 就绪使用本机实际完成边界。目标重写至 RFC1918/ULA 才属于 INTERNAL_REWRITE；回环、基准测试、保留地址为 UNKNOWN。

认证 GeoIP 在管理端独立 ingest 线程处理，仅查 IP 字面量，缓存最多 2,000 个来源。分析独立存 analysis_json，不参与原始事实冲突判定。基线为 BASELINE_INSUFFICIENT，规则版本 contract-v1，仅提供 LOCATION_UNKNOWN；完整风险分析及配置传播留给后续独立的高级认证分析。密码、认证头、Basic 编码/解码串、原始报文和异常自由文本不进入新认证日志。

## 开关与部署

新采集/ingest/query 默认开启。2026-10-09 按用户要求将代码与 Compose 默认值从 false 调整为 true，避免仅更新镜像但沿用旧部署配置时出现 QUERY_DISABLED；显式设为 false 仍可关闭。随后将已有逐节点 `logReportingEnabled` 接入新版日志，V1 有效开关为 `OBSERVABILITY_ENABLED && logReportingEnabled`（JVM `observability.enabled` 优先于环境变量）。管理端开关由 Spring Boot 的 observability.ingest-enabled/query-enabled 绑定；不增加配置数据库字段或 Flyway 迁移，不修改既有配置 CRUD/hash。

| 配置 | 默认 | 用途 |
| --- | --- | --- |
| 节点 logReportingEnabled | true | 管理台“代理网关日志上报”，动态控制 V1 采集／发送 |
| Worker OBSERVABILITY_ENABLED | true | 每 Worker 打开新采集 |
| Worker OBSERVABILITY_AUTH_ENABLED | true | 总开关下独立凭据认证采集 |
| Worker OBSERVABILITY_EVENTS_ENABLED | true | 总开关下独立事件采集 |
| Worker OBSERVABILITY_BUFFER_CAPACITY | 5000 | 最大未确认事实数，包含待 ACK 批次 |
| Worker OBSERVABILITY_BUFFER_MAX_BYTES | 4194304 | 序列化事实载荷上限；实际堆占用另含对象/批次开销 |
| 管理端 OBSERVABILITY_INGEST_ENABLED | true | 协商/接收/写入 |
| 管理端 OBSERVABILITY_QUERY_ENABLED | true | 独立查询 |

先升级管理端，再升级 Worker，继续使用现有环境变量及 Docker 数据卷：

```bash
make backend-build
OBSERVABILITY_INGEST_ENABLED=true OBSERVABILITY_QUERY_ENABLED=true \
  docker compose -f deploy/docker/docker-compose.control.yml up -d --build --no-deps control-manager
OBSERVABILITY_ENABLED=true WORKER_ID=home-01 \
  docker compose -f deploy/docker/docker-compose.worker.yml up -d --build --no-deps proxy-worker
```

已移除 OBSERVABILITY_SPOOL_DIR/MAX_BYTES 配置及 TelemetrySpool。Worker 新日志不读写 /data/proxy-worker/telemetry-v1；原数据卷仍供管理 Token/配置使用，既有目录不自动清理。一次 shell 环境变量仅影响该次部署，应在本地环境文件或流水线保存长期配置。

### 仅上报新版日志

2026-10-09 按用户要求屏蔽旧“访问概览”：隐藏菜单和访问分布中的旧版聚合分析链接，原 `/config/overview/log-audit`、`/config/overview/aggregate` 分别跳转新版日志审计、访问分布。旧源码、API、表和历史数据保留；页面变更不会自动修改节点配置。

旧版日志在 Worker 中固定停用，不需要用户逐节点关闭旧开关：`AccessLogUtil` 不再采集成功／失败／DNS 日志，`CONFIG_POLL` 心跳帧的 `accessLogs`／`authLogs` 固定为空，即使旧队列中存在记录也不会发送。旧页面源码、日志 DTO、后端 API、表和历史数据继续保留。

新版日志沿用原“系统运维 → 代理节点 → 编辑 → 代理网关日志上报”，对应数据库和聚合配置中的 `logReportingEnabled`，字段名、默认值与配置 hash 均不变。开启后采集／上报新版连接、DNS、诊断事件、认证日志，关闭后停止新事实入队与 telemetry 发送。保存后由已有配置通道动态下发，无需重启，也不会因仅修改此开关重启代理监听；各节点独立设置，缺失或 null 按开启处理。以前将该开关设为 false 的节点升级后新版日志也会停止，需在页面打开才能恢复。

`OBSERVABILITY_ENABLED` 保留为部署层总开关，认证／事件仍受 `OBSERVABILITY_AUTH_ENABLED`／`OBSERVABILITY_EVENTS_ENABLED` 子开关控制；管理端 ingest/query 开关保持原语义。以上默认均为 true，页面开关不能覆盖部署层禁用；环境变量改变需重建容器或重启进程。

关闭页面开关后，已有新版待确认批次保留在有界内存中并暂停发送，重新开启继续发送；已发出的帧仍可能落库和返回 ACK，运行进程重启仍会丢失未确认记录。关闭期间不补采新事实，跨越关闭窗口的连接统计不能作为完整覆盖。配置下发和基本心跳不受日志开关影响，控制台／文件运行日志由另一个 `logLevel` 设置控制。

旧源只反映 Worker 升级停用旧采集前的历史，页面开关不能恢复旧日志；若需要恢复旧采集，需回退 Worker 版本，不能承诺仅靠配置回退到旧源。此变更不删除任何日志表或已有数据。

## V1 协议与内存缓冲

1. 支持且开启 ingest 的管理端在 Token 验证的 WSS 升级请求中加 X-Home-Proxy-Telemetry: 1。未看到该头的 Worker 不发送新消息类型/字段。
2. Worker 发送 TELEMETRY_HELLO（telemetryVersion=1），收到 TELEMETRY_ACCEPT 后才发 TELEMETRY_BATCH。新字段 NON_NULL 序列化，旧消息保持原结构。
3. Worker 产生事实时冻结 JSON，进入有界内存队列。独立 sender 发送时组批，每批至多 100 条、256 KiB；固定 UUID batchId，未 ACK 时保留同一批次/记录身份及内容。队列与待确认批次一起计算容量，满时拒绝新记录并增加 dropped，已有批次不被驱逐。
4. 不写日志缓存文件，没有 fsync、后台磁盘 writer 或启动恢复。sender 与旧心跳 poller 分开执行。连接失败、CK 故障、ACK 丢失时，当前进程中的批次保留并重试；失败 ACK 退避至最多 60 秒，无 ACK 至少等待 5 秒再发。
5. 管理端绑定已认证连接的 Worker ID，比对 CK raw_hash。相同事实重发认可，冲突拒绝；CK 同步持久写入所有新事实后才 ACK.persisted=true。部分失败不 ACK，重试跳过已经持久化的事实。
6. Worker 只在匹配 batchId、protocolVersion=1 且 persisted=true 后释放内存批次；未知、失败、旧版本 ACK 不释放数据。重试返回不可变快照的副本，调用方修改不影响下一次发送。
7. Worker 停止/重启/崩溃会丢失未确认内存日志。API 明示 bufferMode=MEMORY、restartRecoverySupported=false；不会补造缺失汇总/字节。状态指标按 worker instance 重置。控制端已落库记录不受 Worker 重启影响。

管理端单进程 ingest 队列最多 128 批，队列满不 ACK；Worker 按内存容量保留批次。阶段 B 检查页面所需详情查询、基本运行状态与实际 Worker 覆盖；多管理端并发、海量历史按 ID 查找及生产容量基准按实际部署和后续功能依赖验证。

## 新表与查询契约

四张独立表均使用 ReplacingMergeTree、occurred_at 月分区，排序键包含 Worker、实例、稳定时间和 record_id：

- telemetry.proxy_session_log_v1：record_id = connection_id。
- telemetry.dns_query_log_v1：record_id = query_id。
- telemetry.proxy_event_log_v1：record_id = event_id。
- telemetry.credential_auth_log_v1：record_id = auth_attempt_id。

DTO 字段有独立 snake_case 列，空值不转 0；另存原始 JSON、SHA-256、首次 received_at 和可空 analysis_json。时间为 UTC DateTime64(6)，UInt64 字节/耗时/ID/序号在 API 中使用十进制字符串。

默认无新表 TTL。可在首次建表时用 observability.session/dns/event/auth-retention-days 分别指定正整数；既有表不自动改 TTL。E2E 不 DROP/TRUNCATE/清理任何日志表。

ADMIN/SUPER_ADMIN 可查询 /api/observability/v1：

| 路径 | 内容 |
| --- | --- |
| GET /status | delivery=BEST_EFFORT_WITH_ACK，ingest/query 状态；可加 workerId 看协商、实例、内存模式/容量、队列、丢弃、序列化失败、重试/ACK；无协商为 UNSUPPORTED_OR_DISABLED |
| GET /sessions | 唯一连接汇总 |
| GET /dns | 唯一 DNS 事实 |
| GET /events | 唯一事件 |
| GET /credential-auth | 唯一认证尝试及 analysis |

列表强制 workerId、ISO UTC from/to，窗口不超过 31 天；limit 默认 100、最大 200，offset 最大 10,000；可选 connectionId。始终在 FINAL 后查询，按 occurred_at DESC、record_id 排序；无样本返回空数组，关闭查询返回 503。审计所需过滤、分页及详情页面属于阶段 B；业务聚合在阶段 C 的对应迁移批次或后续独立观测功能中补齐。

## E2E 与验收

```bash
make backend-test
npm --prefix frontend run build
make e2e-observability-stage-a-setup
make e2e-observability-stage-a
python3 scripts/e2e/fake-ip-persistent-e2e.py test
```

真实流量脚本复用当前 Worker 的唯一 Fake IP DNS，新增独立命名账号/路由/SOCKS5/HTTP fixtures；测试保留全部表、事实、卷及控制器 fixtures，只释放本次添加的临时地址/socket。协议探针使用 SYNTHETIC_PROTOCOL_PROBE 记录和独立 Worker，结束只禁用该 Worker。

本次内存版本验证包括：

- 内存缓冲的稳定快照、ACK 丢失重试、错误/未知 ACK、条数/字节限额（含待 ACK 数据）、溢出不驱逐已有事实、单批 100 条，以及新实例空缓冲/关闭清空。
- 生命周期、成功载荷、唯一收尾、CK 失败不 ACK、查询范围/FINAL、大整数/空值和严格旧 DTO 兼容。
- 真实代理 E2E 单窗口 11 条连接、11 条认证、2 条 DNS、15 条事件；三个 SOCKS5 连接各上传/下载 48 字节，分别 INTERNAL_REWRITE（10.254.94.1）、EXTERNAL（93.184.216.250，本机临时模拟公网端点）、UNKNOWN（127.0.0.1）。BLOCK 和认证成功后未发目标的记录保留。
- 认证成功/错误密码/无可接受方法/407 挑战/畸形 Basic，回环 GeoIP=PRIVATE 且国家/城市为空。DNS A=FAKE_IP 有地址，AAAA=NOERROR、IPV6_SUPPRESSED，responseSent=true。
- 实际 WSS/ClickHouse 探针验证持久 ACK、重发唯一行、物理重复行 FINAL 去重、首次 received_at 不变、冲突拒绝、认证连接绑定 Worker，以及 9,007,199,254,740,993 字节字符串无损。大数字为合成协议夹具。

首版已验证四种新旧版本组合、关闭采集不增长新表、原有 Fake IP 无 Host HTTP/server-first TCP/旧审计落库；改为内存方案后沿用协议协商和旧链路，重新跑新 E2E 核对新表数据及 MEMORY 状态。机器可读报告保留在 /tmp/gateway-observability-stage-a-e2e.json 和 /tmp/gateway-observability-protocol-probe.json。所有首版及本次已落库测试数据继续保留。

已知基线问题：make e2e-worker-proxy 在 direct.localhost SOCKS5 场景失败（inbound closed during DNS lookup），未修改仓库 HEAD 中同样复现；未改旧路由/转发语义来绕过。环境最初更老的 Worker 不能解析仓库原有 logLevel，兼容矩阵使用修改前仓库 HEAD 镜像。

## 开销、差异与回退

内存版本删除磁盘读写及 spool writer，仅保留独立 sender 和有界缓冲。status 用 bufferBytes/depth/capacity/maxBytes、dropped、serializationFailures、complete 展示当前进程状态；阶段 B 起持续观察实际启用 Worker 的运行开销与覆盖，生产吞吐、延迟、堆内存及多 Worker 容量按后续部署范围验证。

旧 BLOCK 日志继续过滤，新汇总记录 BLOCK；新凭据认证在检查时记录，未建隧道的成功也保留；新流量只累加成功载荷且唯一收尾。累计表数包含调试、多轮兼容和合成夹具，不能替代单次时间窗口分母。

优先关闭节点的“代理网关日志上报”（`logReportingEnabled=false`）动态停止新采集／发送；已有内存批次暂停，重新开启继续发送。`OBSERVABILITY_ENABLED=false` 并重建 Worker 也可停止新采集／发送，但重建会丢弃未确认内存批次，重新开启从新事实开始。管理端 INGEST_ENABLED=false 时 Worker 不协商／发送新协议，当前进程仍按容量暂存；QUERY_ENABLED=false 可独立关闭查询。当前 Worker 的旧采集／发送固定停用，恢复旧采集需回退版本；禁止 down -v、DROP/TRUNCATE 作为测试或回退步骤。

## 本次内存版本最终结果

- 后端 238 个单测通过（common 15、Worker 135、管理端 88），后端打包与前端构建通过。
- `make e2e-observability-stage-a` 通过；窗口 `2026-10-09T06:01:09.028347Z` 至 `2026-10-09T06:03:10.609899Z` 查到连接 11、DNS 2、事件 15、认证 11，分类与双向 48 字节均符合预期。
- 状态为 BEST_EFFORT_WITH_ACK / MEMORY / restartRecoverySupported=false，queueDepth=0、bufferBytes=0、dropped=0、serializationFailures=0。
- 本次真实流量 E2E 前后，旧 telemetry-v1 目录的文件清单与目录修改时间完全一致，目录状态未被新日志上报改变；新日志实现中已无文件读写路径。
- 旧表及所有新表中的历史/本次已落库测试数据均保留，没有清表或删除 Docker 数据卷。
