# 网关观测 B1：新版日志审计实施与验证

更新日期：2026-10-09。对应 [双轨设计第 13 节](../development/gateway-observability-log-model-design.md) 的 B1。B1 已完成；B2 关键数据可信验证／用户确认、C 查询迁移与 D 清理尚未执行。

## 页面与实现范围

管理台新增 **网关观测 → 新版日志审计**，路由 `/config/observability/log-audit`。四个标签分别查询连接汇总、DNS 查询、诊断事件、凭据认证，复用已有管理台鉴权与 Ant Design 基础设施，使用独立 V1 类型、client 与表。

- Worker、开始／结束时间、用户名包含、来源 IP 精确匹配、入站协议和结果筛选在 ClickHouse 执行后分页。
- 连接另支持原始／逻辑／有效目标及选定目标 IP 包含、路由名称包含或 ID 精确匹配、建连结果、四类业务流量；DNS 支持域名包含／查询类型；事件支持事件类型；认证支持认证方式，用户名同时搜索尝试／已验证用户名。
- 每页 50 条，列表显示完整匹配数量；查询和翻页不在浏览器过滤当前页。切换 Worker 或标签重置筛选；重新查询重置页码。浏览上限为 offset 10,000，达到上限提示缩小范围。
- 每条记录通过 Worker、时间窗口及稳定记录 ID 单独加载详情，失败可重试。认证失败／挑战不要求有目标地址。
- 连接详情展示原始、逻辑、实际目标、重写生效、出站 peer、路由快照、建连／结束结果、唯一上传／下载、分类依据与算法版本、阶段时间／耗时。DNS、事件、认证有独立详情；认证展示来源 GeoIP 和分析覆盖。
- 连接详情的事件／DNS／认证分别分页，使用连接 ID；DNS 同时匹配 parentConnectionId。关联只携带 Worker、时间和连接 ID，不继承列表中的用户名、结果、目标等筛选，不以地址／相近时间猜测关联。
- 事实的 UInt64 字段继续使用十进制字符串；字节在列表和详情中按 1024 进位显示为 B／KB／MB／GB／TB／PB／EB 等单位，最多两位小数，悬停保留完整整数 B；单位换算和舍入使用 BigInt，避免大整数精度丢失。耗时按字符串转换为 ms 并保留原始 µs，无 JS 浮点舍入。时间按浏览器时区显示并保留原始小数精度，列表标题／筛选注明时区，悬停查看原始 UTC；按用户要求移除列表旁重复的 UTC 查询窗口说明。

根据用户审阅反馈，已移除“采集与覆盖状态”卡片，不在日志审计展示队列、丢弃、ACK 等运维指标；状态 API 保留，供后续运维／验证使用。页面只保留查询关闭、查询失败、暂无样本等直接影响浏览的提示。连接结束／建连结果和已知错误原因改为中文，筛选使用中文选项，悬停仍可查看原始代码。认证阶段失败且没有目标／路由时，显示“认证未通过，目标未记录／认证阶段未进入选路”，其他空目标显示“目标未记录”；详情耗时空值仍明确为未测量／未采集，UNKNOWN 不转换成成功或零。

原 `/api/logs`、旧 V1 数组查询、旧日志审计、流量、聚合和入站统计保留原行为；不修改 common DTO、Worker 采集、路由、配置 CRUD、数据库迁移或旧表。

## 连接详情字段说明（用户审阅补充）

删除详情顶部“空值表示未测量、未采集或不适用；UNKNOWN 保留未知语义”及其阶段说明。来源依据、目标识别来源、出站方式、路由动作和选路依据显示中文；悬停值仍可查看原始代码，未识别的新枚举保留原文，显式 UNKNOWN 显示“未知”。相关字段旁提供可悬停或聚焦的说明。

| 字段 | 含义与当前采集边界 |
| --- | --- |
| 来源依据 | TRANSPORT_PEER 显示“连接对端地址”：取自 Worker 接收连接时的网络对端。经过 NAT 或上游代理时，可能是出口／上游代理地址。传输 peer 改称“连接对端”。 |
| 规则集版本 | 本次路由决策使用的规则集版本快照，与配置版本不同。当前 Worker 没有写入 ruleSetVersion，空值显示“未上报规则集版本”，不能据此判断没有使用规则；历史记录已有版本时原样显示。 |
| 原始目标 IP／原始端口 | 当前在选路钩子读取 request.originalTargetIp；仅有原始 IP 时才配套写入端口。透明代理填入截获连接的原始目的 IP（可为 Fake-IP）与端口；普通代理当前不单独记录这组字段，目标与端口见逻辑目标／逻辑端口。不能拿逻辑域名或实际出站 IP 补填原始 IP。认证失败显示“认证阶段未记录目标”；透明代理缺失显示“未记录原始目标”；普通代理缺失显示“此入站不单独记录原始目标”。 |
| 目标识别来源 | 原“目标恢复来源”改名，包含直接取得与恢复两种情况。INBOUND_REQUEST 显示“客户端请求提供”；FAKE_IP_MAPPING、HTTP_HOST、TLS_SNI、ORIGINAL_IP 分别显示 Fake-IP 映射恢复、HTTP Host 识别、TLS SNI 识别、原始目标 IP。 |
| 出站方式 | 按实际 RoutePolicy 翻译为直连、目标重写、阻断、自定义 DNS 解析、出站代理；路由动作使用同一翻译。出站 peer 改称“出站对端”，使用上游代理时此地址是代理节点，不一定是最终目标。 |

本次仅调整前端表达，不补填或修改已有事实。新版审计既有 7 项 Jest 通过，前端生产构建通过（保留其他页面已有 lint 与 bundle 提示）。浏览器使用本地生产构建和显式展示样例验证中文值／原始代码、字段说明、普通代理／透明代理的原始目标、已有规则版本、新枚举原文、UNKNOWN 与移动端聚焦说明；没有调用真实管理端或生成业务流量。

展示样例截图：[规则集版本说明](assets/gateway-observability-b1/session-field-help.png)、[目标字段与中文出站方式](assets/gateway-observability-b1/session-target-fields.png)。截图中的账号、Worker 和地址均为展示样例，不作为业务事实验证。

## 新增 API 与查询边界

权限沿用 `/api/observability/v1/**` 的 ADMIN／SUPER_ADMIN 管理台鉴权。新 API 只查询独立 `telemetry.*_v1`，所有查询使用 FINAL 去重，固定按 occurred_at DESC、record_id 排序。

| 路径 | 结果 |
| --- | --- |
| `GET /api/observability/v1/{type}/page` | `{items,total,limit,offset,maxOffset,timeBoundary}`；total 为十进制字符串，maxOffset=10000，timeBoundary=`[from,to)` |
| `GET /api/observability/v1/{type}/{recordId}` | 单条独立事实及 receivedAt、可空 analysis；不在范围内返回 404 |
| `GET /api/observability/v1/status?workerId=...` | 原运行指标、scope 与开关保持原契约；状态基于当前进程 |

`type` 为 `sessions`、`dns`、`events` 或 `credential-auth`。列表和详情都强制指定 workerId、ISO 时间 from／to；时间范围最大 31 天，开始包含、结束不含，不能逆序／相等。详情仍要求有界时间窗口，以避免稳定 ID 在全部历史分区中无界扫描。

数据库时间列为 DateTime64(6, UTC)，原始事实可带更高精度小数；页面显示原始事实时间，数据库筛选按持久时间列精度执行。服务端列表 limit 默认 50、允许 1..200；offset 允许 0..10000。列表选择页面时将 total 截断为可浏览页数，原始匹配总量仍无损显示。普通列表与关联查询都受相同窗口／分页限制。

| 范围 | 可选参数 |
| --- | --- |
| 通用 | connectionId、username、clientIp、protocol、outcome |
| 连接 | target、connectOutcome、route、trafficCategory |
| DNS | target（qname）、qtype；connectionId 匹配 connection_id 或 parent_connection_id |
| 事件 | eventType |
| 认证 | authMethod；username 同时搜索 username／attempted_username／verified_username |

所有用户输入作为 SQL 参数绑定；列与表由类型白名单选择。用户名／目标等“包含”使用字符串包含，不把 `%` 或 `_` 当成 SQL 通配符。通用 outcome 在连接上指结束结果、认证上指认证结果、DNS／事件上指各自 outcome，建连结果单独筛选。不适用的类型筛选、非法 UUID／范围／分页返回 400；关闭查询或存储失败返回 503；数据库异常不直接暴露给前端。详情仅接收 Worker／时间范围，不继承列表筛选。

## 验证结果

- 管理端及依赖测试：common 15、control-manager 95，共 110 项通过，其中新增 7 项审计测试，覆盖过滤前分页、count／列表同一筛选、输入参数化、DNS 父连接关联、认证尝试用户名、失败账号关联／筛选且不冒充已验证身份、稳定 ID、空值／大整数、UTC 参数绑定和 400／404／503 边界。
- 前端相关 Jest：新版审计 7、导航 6，共 13 项通过；覆盖筛选下发、翻页／重置、稳定 ID 详情、关联不继承列表筛选、四类独立单位、无目标认证、查询关闭／隐藏运维信息／认证早期失败文案／错误与重试、大整数／空值／µs／时区显示。
- `make docker-build-control` 通过：前端生产构建、静态资源同步、后端多模块打包与管理端镜像构建。现有 MailGateway／authSlice lint 提示、浏览器兼容数据过期和 bundle 大小提示仍存在，新页面没有新增构建错误。
- `make e2e-observability-b1` 通过：只读查询本地保留的 home-01 事实，匹配连接 66、DNS 16、事件 94、认证 62。四类详情与列表事实一致，首／次页排序一致，多字段组合筛选、无样本、时间包含／排除边界、跨 Worker 404、31 天／分页限额和旧 API 兼容均通过。
- 只读 E2E 额外查询 FAILURE／CHALLENGE／DENIED／MALFORMED 认证样本；认证 DTO 没有密码／认证头字段。复用显式隔离的合成协议夹具，验证新版详情仍返回 `9007199254740993` 字节字符串。合成值不作为真实业务流量。
- Chromium 浏览器验证通过：由菜单进入页面，四类列表与详情可打开，目标筛选确实调用后端，认证失败详情可独立打开；Asia/Shanghai 时区、390px 移动端截图已保存，无 pageerror。

机器可读 API 报告：`/tmp/gateway-observability-b1-e2e.json`。浏览器执行结果：`/tmp/b1-auth-identity-browser.log`。测试／构建记录：`/tmp/b1-auth-identity-backend-test.log`、`/tmp/b1-auth-identity-frontend-test.log`、`/tmp/b1-auth-identity-build.log`。

```bash
mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl control-manager -am test -DskipTests=false
CI=true npm --prefix frontend test -- --watch=false --runInBand \
  ObservabilityLogAudit.test.tsx ProxyConfig.test.tsx
make docker-build-control
make e2e-observability-b1

# 浏览器工具为临时外部依赖，不改 frontend/package.json；需要 Chromium 及运行库。
NODE_PATH=/tmp/home-proxy-b1-browser/node_modules \
  node scripts/e2e/gateway-observability-b1-browser.cjs
```

只读 API 检查需要当前 Worker 在最近一天有四类保留样本；认证结果检查复用阶段 A 已生成的失败／挑战等 fixtures，不自行新增流量或清表。更换环境时先按阶段 A 验证文档准备样本；超过一天的历史记录须重新选择窗口。浏览器脚本默认截图输出 `/tmp/gateway-observability-b1-screenshots`，可用 E2E_SCREENSHOT_DIR 更改，默认本地控制端为 `https://127.0.0.1:18443`。

## 截图

| 页面 | 截图 |
| --- | --- |
| 连接列表 | [sessions.png](assets/gateway-observability-b1/sessions.png) |
| 连接中的失败尝试账号 | [session-auth-failure.png](assets/gateway-observability-b1/session-auth-failure.png) |
| 目标重写详情 | [session-detail.png](assets/gateway-observability-b1/session-detail.png) |
| 稳定连接 ID 关联 | [session-related.png](assets/gateway-observability-b1/session-related.png) |
| DNS 列表／详情 | [列表](assets/gateway-observability-b1/dns.png) · [详情](assets/gateway-observability-b1/dns-detail.png) |
| 事件列表／详情 | [列表](assets/gateway-observability-b1/events.png) · [详情](assets/gateway-observability-b1/events-detail.png) |
| 认证失败列表／详情 | [列表](assets/gateway-observability-b1/auth.png) · [详情](assets/gateway-observability-b1/credential-auth-detail.png) |
| 移动端 | [mobile.png](assets/gateway-observability-b1/mobile.png) |

截图使用本地测试账号、保留的受控样本，未包含密码、管理 Token 或认证原文。

## 本地容器与回退

本次已重新构建并重建 `home-proxy-control-manager`，镜像 `home-proxy-control-manager:latest`；新页面随管理端静态资源交付。本地 HTTPS 端口仍为 18443，observability ingest/query 原启用值已保留。

部署仅执行管理端 `up -d --no-deps --force-recreate control-manager`，从原容器环境继承部署配置；Worker、MySQL 和 ClickHouse 容器没有重建，数据卷保持挂载，旧表及全部新表继续保留。Worker 没有代码变化，不需为 B1 更新或重启；避免额外丢失待 ACK 内存事实。本次没有另存升级前管理端镜像；镜像版本回退需从对应源码重新构建。旧日志入口和独立查询开关仍可直接用于功能回退。

- UI 回退：继续使用原“访问概览 → 日志审计”，旧页面／旧统计没有切换。
- 关闭新版查询：部署配置设置 OBSERVABILITY_QUERY_ENABLED=false，按现有环境重新创建管理端；页面显示未开启，已落库事实保留。
- 停止新日志接收：OBSERVABILITY_INGEST_ENABLED=false。Worker 不再协商新发送，当前进程仍可能在有界缓冲暂存；容量／重启缺失边界见阶段 A 文档。
- 停止 Worker 新采集：按阶段 A 记录设置 OBSERVABILITY_ENABLED=false 并重建指定 Worker；重启会丢失未确认内存记录。

回退／关闭只更改指定开关并保留其他环境配置；不使用 down -v、DROP／TRUNCATE，不删除旧表／数据卷。

## 仍保留的覆盖边界

B1 验证的是审计查询与浏览能力，保留样本数量不是业务完整性证明。当前状态 complete=true 仅表示当前 Worker 进程未报告丢弃／序列化错误；不能推导历史无缺失、全部协议已完整观测或 Worker 重启恢复。

关联受所选时间范围约束，连接早期事件可能在窗口外；当前客户端 DNS 往往没有 parentConnectionId，内部 DNS／非 A/AAAA、完整恢复失败、细分建连耗时、完整风险规则尚未采集。空关联与未测量字段明确展示，不补造事实；旧 Worker 未协商状态无法精确区分版本／关闭／断连。

B2 仍需针对选定 Worker 和已知字节流量检查唯一汇总、分类、认证、异常／失败、运行开销和缺失影响。当前受控历史样本仅复用于 B1 回归，不代替新的 B2 观察与用户确认；现有查询不迁移，高级观测／清理不自动启动。

## 连接列表读法（用户审阅补充）

一行对应网关接收的一条连接的结束汇总，包含认证失败、挑战后关闭、没有完成代理请求的连接，并不等同一次成功网站访问。目标与路由当前在选路钩子中写入：早于此阶段关闭或认证失败时，即便协议报文已有目标文字，当前日志也可能未保存它。

本地 home-01 保留的 66 个阶段 A 样本中，32 条缺少目标／路由：21 条认证失败（11 条凭据错误、5 条 Basic 格式错误、5 条无可接受认证方式），11 条在完成代理请求前结束。其余 34 条都有目标／路由。样本包含故意产生的失败／提前关闭测试，不能据空值比例推断正常访问的目标采集率。

| 结束结果 + 建连结果 | 含义 |
| --- | --- |
| 正常结束 + 通道已建立 | 已建立代理通道，未记录异常后关闭；不能据此推断 HTTP 状态或网页业务成功 |
| 异常结束 + 未尝试建连 | 当前认证失败样本在出站建连前失败；看错误原因与失败阶段 |
| 异常结束 + 建连失败 | 出站连接尝试失败；不与认证失败混为目标服务器故障 |
| 异常结束 + 通道已建立 | 建立通道后发生异常，如转发写入失败 |
| 提前结束 + 建连未完成 | 代理流程未完成便结束，单凭此结果不认定目标网站故障 |
| 策略阻断 | 路由／安全规则拒绝，不自动当系统故障 |

“目标／路由”第一行是有效目标（重写后优先），第二行是命中路由。详情进一步区分原始／逻辑／实际选定目标。查问题先看结果和原因，再看详情失败阶段及关联诊断／认证；看转发流量时可筛选建连结果“通道已建立”，其中仍可能包含建立后发生异常的连接。

## 失败认证账号展示修正

失败时提交的账号已由 Worker 写入独立 CredentialAuthLogV1.attemptedUsername；连接汇总的 username／userId 仅承载已识别身份，不能把客户端声称的账号冒充已确认用户。此前连接列表仅展示 username，导致已经保存的失败账号显示为空。

修正仅增加管理端新版 page／detail 的查询关联：批量按 Worker、workerInstanceId、connectionId 关联认证事实，返回 credentialAuthAttempts 摘要及 credentialAuthAttemptsTruncated；不修改原始汇总／认证事实，不回填表，不修改 common DTO 或 Worker。连接列表显示“尝试账号：xxx（未认证）”，详情单独列出每次尝试和结果；同连接多个尝试保留独立记录，已识别用户和用户 ID 仍不从失败尝试推导。没有提交账号或格式无法解析时明确显示“账号未提交或无法解析”；没有关联样本时显示“用户未记录”，不声称未提交。

连接的用户名筛选同时搜索已识别用户名和关联的 attemptedUsername，在后端过滤后分页，count 与列表使用同一条件。关联／尝试账号搜索从查询 to 向前最多 31 天，包含可能早于列表 from 的认证；避免窄窗口只有连接结束汇总时丢失紧邻的认证账号。超过此范围的长期连接可能没有可关联尝试，原有独立认证分页仍可查询指定时间窗口。

每次列表只额外执行一次批量认证查询，每条连接附带最近最多 20 次尝试，溢出提示完整记录需到关联认证审计查看；摘要只含认证尝试 ID、提交账号、结果、方式和时间，没有密码、认证头或报文。旧 V1 数组接口及旧日志链路保持原行为。

只读本地 E2E 补充核对历史 11 条 INVALID_CREDENTIALS 连接：可关联尝试账号，username／userId 仍为空，用户名筛选能找到失败连接；将 from 缩至结束时间后，认证摘要仍可关联。浏览器补充验证失败账号与详情显示，并更新截图。

## 连接详情的细分耗时补充

后续已实现客户端握手、目标恢复、路由选择、本机出站 DNS、实际 TCP 建连、REALITY TLS／代理握手以及双向背压耗时；阶段 A／B1 上述“细分耗时未采集”是当时的基线。当前适用协议、起止点和验证见 [连接阶段耗时采集](gateway-observability-session-timing.md)。旧记录保持原值，无法从旧总耗时回填细分耗时。详情增加字段说明，明确不适用阶段；移除没有执行来源的限流等待。

## 诊断事件上下文与中文展示修正

此前 Worker 创建诊断事件时仅复制连接 ID、入站 ID／名称与配置版本，漏掉已有的用户、来源地址和入站协议，导致列表普遍显示未记录。现在每次事件从连接上下文复制当时已知的用户 ID／名称、身份来源、来源 IP／端口、来源依据、连接对端地址／端口、入站协议及规则集版本；认证前未知身份仍为空，不使用后续身份回填早期事件。历史事实保持原值，更新 Worker 后的新事件才包含补采集字段。

事件列表、详情与筛选增加中文事件类型和结果，阶段也显示中文：CONNECT_RESULT／CHANNEL_SETUP 显示“建连结果／通道建立”，FIRST_ERROR 显示“首次错误”，ERROR_SUMMARY 显示“错误汇总”，TARGET_RECOVERY_RESULT 显示“目标恢复结果”。悬停保留原始代码，未识别代码按原文显示；筛选仍向后端传原始枚举值。此修改不改变日志表、API 契约或旧日志链路。

## 四类日志的枚举展示与下拉筛选

认证结果在列表和详情统一显示认证通过／失败／拒绝、请求提供凭据、认证格式错误／取消／异常；DNS 结果显示已应答／策略阻断／查询异常／取消。协议、认证方式、DNS 查询类型的列表、详情及筛选复用同一标签，悬停保留原始代码，未识别值按原文显示。认证事件、原因码、身份来源及已知 DNS 应答／缓存字段补充中文说明。

| 日志 | 下拉筛选字段 |
| --- | --- |
| 连接记录 | 入站协议、结束结果、建连结果、流量分类 |
| DNS 查询 | 入站协议、结果、查询类型（当前采集 A／AAAA） |
| 诊断事件 | 入站协议、结果、事件类型 |
| 认证审计 | 入站协议、认证结果、认证方式 |

各下拉可搜索中文选项、清空；协议选项按该类日志的实际入站能力列出。用户名、来源 IP、目标／域名和路由名称／ID 保持文本筛选。下拉向后端提交原始协议／枚举值，不用中文标签筛选；结果字段按记录类型分别取 sessionOutcome、outcome 或 authOutcome，避免混用连接结束结果与认证结果。重置与切换标签清除筛选，不增加新的后端筛选字段或更改事实数据。

## 本地容器更新与新日志验收（2026-10-09）

运行 `make docker-build`，随后沿用现有 `/tmp/session-timing-control-runtime.json`、`/tmp/session-timing-worker-runtime.json` 覆盖配置，仅以 `up -d --no-deps --force-recreate` 更新管理端和 home-01 Worker。容器 JAR 的 SHA-256 与本次构建一致，环境、网络和挂载均与更新前一致。MySQL／ClickHouse 容器 ID 未变，保留全部卷。新 Worker 实例为 `dc08f22c-4160-4e10-a7cd-63298ec41110`。

| 验证 | 结果 |
| --- | --- |
| 阶段 A 真实流量 E2E | 11 条连接、11 条认证、2 条 DNS、15 条事件；新增 DNS 入站协议与所有事件上下文断言通过 |
| 上游协议与阶段耗时 E2E | 8 条连接、7 条认证、10 条事件；HTTP／SOCKS5／Shadowsocks／REALITY、上游拒绝、双向背压及 TLS SNI 通过 |
| B1 只读 API E2E | 四类列表、详情、分页、多字段查询和失败认证账号关联通过 |
| 独立事实复核 | 本次共 19 条连接、18 条认证、2 条 DNS、25 条事件；协议／来源／用户名与关联连接一致，逐条详情和筛选匹配，无重复 ID |
| Chromium 浏览器 E2E | 真实部署的四类列表与详情、中文枚举下拉、DNS／事件／认证组合筛选、移动端截图通过，pageerror 为 0 |

TLS SNI 不产生密码认证事实，所以认证记录比连接少一条。事件中的认证前身份仍为空；已识别用户、来源和协议不会再因漏复制而普遍缺失。更新前固定窗口的连接 91、DNS 21、事件 127、认证 84 条保持原数量，原有 6 个活动入站保留。耗时 E2E 临时入站已禁用，所有测试日志保留；审核时队列深度、丢弃和序列化失败均为 0。

人工验收：打开 <https://127.0.0.1:18443/config/observability/log-audit>，选择 `home-01`，清空用户及其他筛选，按 **北京时间 2026-10-09 17:33:00 至 17:37:00**（UTC 09:33:00 至 09:37:00）查询；四类日志可分别查看本次新增样本。连接／事件／认证可用 `home-01-telemetry-a` 或 `home-01-timing-e2e` 定位，DNS 域名为 `stage-a.telemetry.test`。切换标签会重置筛选，需重新填写时间窗口。

证据：[独立 API 审核](assets/audit-fixes/api-review.json)、[阶段 A](assets/audit-fixes/stage-a.json)、[上游耗时](assets/audit-fixes/timing.json)、[B1 API](assets/audit-fixes/b1.json)、[浏览器结果](assets/audit-fixes/browser-review.json)。截图：[连接](assets/audit-fixes/sessions.png)、[DNS](assets/audit-fixes/dns.png)、[事件](assets/audit-fixes/events.png)、[认证](assets/audit-fixes/auth.png)、[认证详情](assets/audit-fixes/credential-auth-detail.png)。构建和测试日志保存在 `/tmp/observability-audit-{deploy-build,stage-a,timing,b1,browser}.log`。

回退镜像已保存：`home-proxy-control-manager:before-audit-fixes-20261009T092752Z`、`home-proxy-worker:before-audit-fixes-20261009T092752Z`。需要回退时用对应镜像重建应用容器，沿用当前运行覆盖配置；无需清理日志或数据库卷。
