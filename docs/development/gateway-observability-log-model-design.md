# 代理网关日志模型与双轨演进设计

## 1. 文档状态与实施约束

- 更新日期：2026-10-09。
- 状态：阶段 A 已实施并完成本地保留数据 E2E 验证；阶段 B/C/D 待实施。阶段 A 契约、范围与验收见 [实施与验证记录](../testing/gateway-observability-stage-a.md)。
- 涉及模块：`common`、`proxy-worker`、`control-manager`、`frontend`，以及 ClickHouse 部署与运维。
- 目标：建立可准确统计、可解释连接过程的新日志模型，支持 Fake IP、目标重写至内网的流量拆分、用户名密码认证来源观测，再支撑网关性能、路由、策略和故障分析。
- 本次需求补充：月度流量重点分离命中目标地址重写后访问家庭 NAS 等内网目标的流量，该流量仅为目标重写场景的子集；新增实际使用的用户名密码认证日志，重点发现异常来源 IP／地理位置，成功与失败均记录。

用户明确要求采用双轨演进，以下约束优先于本文其他实施建议：

1. 双轨阶段保留所有既有业务代码、日志 DTO、采集与上报路径、存储实现、API、页面和环境中的访问日志表。可以增加埋点、适配器和新能力，但不能通过替换、删除或改变旧行为来实现新模型。
2. 旧日志继续正常采集、上报、落库；旧日志审计、聚合分析、流量概览、入站配置中的统计查询等继续使用原数据源。
3. 新日志使用独立 DTO、队列、传输批次、表、查询 API 和前端分类，与旧日志并行观察一段时间。观察期不预设截止日期，不自动切换。
4. 用户确认新模型稳定后，才进入现有页面查询迁移阶段。迁移按页面／功能逐项执行，保留回退旧查询的能力。
5. 现有页面迁移完成且用户另行确认可以清理后，才删除旧代码和环境中的访问日志表。页面迁移与旧链路清理是两个独立阶段。
6. 此前“旧数据可以不要”的表述不能解释为立即清表授权；以本节双轨要求为准。新模型不依赖旧数据回填，旧表在清理阶段前继续保留。

## 2. 背景与实际数据依据

当前 `AccessLog` 同时承载 DNS 应答、代理连接结果和传输错误，记录单位与字段语义不统一。

2026-10-09 对 NAS ClickHouse 的只读检查发现，`default.access_log` 约有 931 万条记录。重点观察窗口为北京时间 2026-10-08 20:38 至 2026-10-09 10:40：

| 观测项 | 样本结果 | 设计影响 |
| --- | --- | --- |
| 客户端 Fake IP DNS 应答 | 10,271 条，1,559 个域名 | 已有实际使用量，值得建立独立 DNS 观测 |
| 原始目标在 Fake IP 池内的透明代理日志 | 4,306 条 | 日志条数不能直接视为连接数 |
| 恢复来源 | `FAKE_IP` 4,283 条、`TLS_SNI` 21 条、`HTTP_HOST` 2 条 | 回退来源能发现仅看最终成功率无法发现的问题 |
| 成功且空回答的 DNS 日志 | 10,424 条 | 缺少查询类型和空应答原因，不能全部认定为预期 AAAA 行为 |
| DNS 阶段耗时 | 本次检查的 DNS 与 Fake IP 连接样本中 `dns_duration_ms` 均为 0 | 0 混合了未测量与实际快速完成，需要重定义空值 |
| 连接地址组合的关联检查 | 54 组同时存在成功和错误记录 | 存在一条连接多条事件，不能用当前 `request_id` 消除重复 |

实际样本中，同一连接在同一毫秒写入一条 200 和多条 `WRITE_ERROR`，各条携带相同累计流量。当前 `AccessLogUtil` 每次记录重新生成请求 ID；`RelayHandler` 写错误，已建立连接的关闭回调又写成功日志，因此按 ID 去重仍不能获得唯一连接汇总。

上述数字是特定窗口的检查结果，不是完整健康判定，也不作为上线后的固定阈值。只有一个 Worker 样本，不能据此认定多 Worker 行为正常。

## 3. 模型原则与边界

### 3.1 按记录单位划分

| 类型 | 一条逻辑记录代表什么 | 主要用途 |
| --- | --- | --- |
| 连接汇总 | 一条代理连接的唯一结束汇总，包含早期失败 | 最终结果、连接数、最终转发流量、连接寿命 |
| DNS 查询 | 一次逻辑 DNS 查询的最终结果 | 客户端应答、真实解析、缓存、重试与策略行为 |
| 诊断事件 | 一条连接的关键阶段结果或异常 | 域名恢复、建连质量、过程追踪与及时观测 |
| 用户名密码认证 | 一次入站认证检查或明确的认证协议结果 | 认证来源、成功／失败、挑战／拒绝、异常位置分析 |

前三类为访问观测，第四类为本次明确新增的独立认证模型 `CredentialAuthLogV1`。现有 `AuthLog`、旧认证队列、旧上报及 `default.auth_log` 保留，不通过改写旧 DTO 或删除旧链路实现新认证审计。新模型记录本节约定的真实凭据认证行为，不把匿名或设备 IP 识别伪装成密码认证。

不按 SOCKS5、HTTP CONNECT、Shadowsocks、透明代理分别建立连接表，它们共享“连接”的统计单位。未来若新增 HTTP 请求审计，一条 HTTP 请求应独立建模，用 `connection_id` 关联连接，不能假设一个 keep-alive／多路复用连接只有一个请求。

### 3.2 统计与解释分离

- 总连接数和最终流量来自去重后的连接汇总。
- 域名恢复率与建连成功率来自对应的唯一阶段结果事件。
- DNS 查询量与结果来自去重后的 DNS 查询记录，并区分客户端查询和内部解析。
- 诊断错误记录不重复携带可用于求和的累计流量。
- 一条连接的建连成功与传输异常可以同时成立，不使用一个通用 200/500 表示全部行为。
- 未执行、不适用和未测量用显式状态或 `null` 表示；不能统一转为 0、空字符串或虚构成功。

### 3.3 观测边界

普通 HTTPS 隧道只观测连接和转发，不能据此推断 HTTP 状态、网页渲染耗时、每次请求的 TTFB 或下载业务是否成功。上游代理负责目标解析时，本机不知道的真实目标 IP 应为空。

异常退出可能没有最终汇总。不能把有开始／阶段事件、无结束记录的连接自动认定为成功或填入零流量；应标记结束未知，并结合 Worker 实例和运行指标解释。

## 4. 公共标识、时间与上下文

新增独立的 `SessionLogV1`、`DnsQueryLogV1`、`ProxyEventLogV1`、`CredentialAuthLogV1` 等 DTO；下列为约定字段，Java／前端采用 camelCase，ClickHouse 采用 snake_case。

| 字段 | 含义与约束 |
| --- | --- |
| `schema_version` | 新模型初始版本为 1，与现有 `AccessLog` 独立 |
| `worker_id` | 稳定节点 ID；管理端从已认证管理连接绑定，不能仅信任日志声明 |
| `worker_instance_id` | Worker 每次启动生成的实例 ID；标识重启与累计计数器重置 |
| `connection_id` | TCP 接收时生成一次，贯穿认证、恢复、路由、连接与关闭；早期失败也应具备 |
| `query_id` | 每次逻辑 DNS 查询生成一次，不使用可复用的 DNS transaction ID 代替 |
| `event_id`／`record_id` | 每个事件／事实的稳定唯一标识；重试、重发不重新生成 |
| `occurred_at` | Worker 产生该结果的时间，UTC；同一记录重发保持不变 |
| `received_at` | 管理端首次接受该记录的时间，用于计算上报延迟 |
| `inbound_id`、`inbound_name` | 发生时的入站标识与名称快照 |
| `user_id`、`username` | 识别到的用户；识别前失败可以为空，不强制填写匿名用户 |
| `identity_source` | `ACCOUNT_AUTH`、`DEVICE_IP`、`ANONYMOUS`、`UNKNOWN` |
| `client_ip/port` | 网关认定的客户端地址 |
| `client_ip_source`、`transport_peer_ip/port` | 客户端地址来源及实际连接对端；转发地址仅使用已有可信入口机制 |
| `config_version`、`rule_set_version` | 本次决策使用的配置和规则集版本快照，不用当前版本回填历史 |

ID 可采用 UUID 等全局唯一方案，连接状态中固定保存；不依赖客户端端口与时间组合近似去重。诊断事件还应保存每连接递增的 `event_sequence`，详情按该序号及时间排序，避免同毫秒事件顺序不清。

壁钟用于时间线，单调时钟用于耗时计算。耗时建议使用微秒精度的非负整数，前端转换为毫秒；测得不足 1 ms 与未测量必须可区分。JSON 传输和前端处理需注意 UInt64 超出 JavaScript 安全整数范围，标识、字节等大整数按明确的字符串契约传输。

## 5. 连接汇总模型

### 5.1 生命周期、结果与流量

| 字段 | 定义 |
| --- | --- |
| `started_at` | 接收连接的时间 |
| `connected_at` | 出站通道已经可以转发数据的时间；未建立则为空 |
| `ended_at` | 本机完成该连接收尾的时间；作为汇总 `occurred_at` |
| `connect_outcome` | `CONNECTED`、`FAILED`、`BLOCKED`、`CANCELLED`、`NOT_ATTEMPTED` |
| `session_outcome` | `NORMAL_END`、`ERROR_END`、`BLOCKED`、`CANCELLED` |
| `failure_stage` | 如 `AUTH`、`TARGET_RECOVERY`、`ROUTING`、`DNS`、`OUTBOUND_CONNECT`、`PROXY_HANDSHAKE`、`RELAY` |
| `error_code`、`error_message` | 稳定错误分类与具体说明；聚合按错误码，不按自由文本 |
| `close_initiator`、`close_reason` | 客户端／目标／网关／未知，以及正常关闭、重置、超时、配置重载等 |
| `upload_bytes`、`download_bytes` | 客户端到出站、出站到客户端，成功转发的载荷字节，最终只汇总一次 |
| `session_duration_us` | 从接收至结束；不是网页请求耗时 |
| `first_upload_at`、`first_download_at`、`last_activity_at` | 首次双向转发和最后活动时间；没有数据则为空 |

转发载荷字节不是 NIC 流量、TCP 重传量或协议加密开销。接收、排队但未成功转发的数据不计入这两个字段。DNS 报文大小可单独记录在 DNS 模型中；不得与隧道载荷不加说明地求和为“用户下载流量”。

已建立连接后发生传输错误时，`connect_outcome=CONNECTED`、`session_outcome=ERROR_END`、`failure_stage=RELAY`。错误后的关闭不能覆盖成正常结束。客户端在 DNS／建连期间主动取消可为 `CANCELLED`；已正常传输后的客户端关闭通常为 `NORMAL_END`，不能仅按关闭发起方判错。

保存第一个导致结束的错误及必要的错误摘要，后续附带异常放入事件；关闭原因保留实际触发因素。结束逻辑必须唯一，不能同时从错误路径与 closeFuture 写出两份汇总。

### 5.2 目标地址与出站端点

| 字段 | 定义 |
| --- | --- |
| `original_dst_ip/port` | 透明代理的原始目的地址；可能为 Fake IP。显式代理未获得原始 IP 时允许为空 |
| `logical_target_host/port` | 客户端声明或恢复出的逻辑目标；保留域名身份 |
| `target_source` | `FAKE_IP_MAPPING`、`TLS_SNI`、`HTTP_HOST`、`INBOUND_REQUEST`、`ORIGINAL_IP` |
| `effective_target_host/port` | 路由改写后的实际请求目标 |
| `selected_target_ip` | 本地目标解析选中的地址；由上游解析时为空 |
| `outbound_peer_ip/port` | 本机实际连接的端点，可能是上游代理 |
| `outbound_mode`、`outbound_protocol`、`outbound_node_id` | 直连／代理／目标重写，以及协议与稳定端点标识 |

IP 字段只接受 IP 字面量；未知值为空，不能回填域名。没有独立出站节点实体时，可以使用路由 ID 和端点标识关联，不在日志项目中强制重构配置模型。目标地理位置与出站节点地理位置分开记录，Fake IP 不参与真实目标地理统计。

### 5.3 路由、限流与性能

记录 `route_id/name`、`route_action`、`route_selection=RULE_MATCH/DEFAULT`、`matched_rule_id`、`matched_rule_set`。规则缺少稳定 ID 时采用可追踪的版本与规则位置组合，不能只保存随时可改的展示名称。无需记录全部未命中规则。

阶段耗时区分客户端握手、域名恢复、路由选择、本地 DNS、出站 TCP、出站 TLS／代理握手和整体通道建立。通道建立以“可以转发”为成功边界，不能仅以 TCP connect 返回成功判定。

不同阶段可能重叠或有包含关系，API 应说明起止点，不允许直接把所有耗时相加。上游代理握手并不保证目标业务请求成功；各协议记录本机实际能确认的结果。

扩展字段包括 `rate_limit_policy_id`、`rate_limit_wait_us`、按上传／下载方向区分的 `backpressure_duration_us`。只有实际执行限流／背压时才累计；未埋点为 `null`，不能由存在配置推断。相同时间段的背压不能因多个回调重复累计。

### 5.4 目标重写至内网的流量拆分

用户的具体场景是：客户端访问一个用于引流的域名，DNS 返回外网 IP，连接进入主路由代理；代理识别域名并命中 `DESTINATION_OVERRIDE`，将目标转换为家庭 NAS 的内网 IP／端口，最终向 NAS 转发。普通 LAN 机器互访通常直接互联，不在本需求的代理流量统计范围内。

```text
nas.example.test → DNS 返回公网 IP → 主路由代理识别域名
  → DESTINATION_OVERRIDE → 192.168.10.85:8096 → NAS
```

本场景中的“fake 域名”指用于引流／映射的域名，不要求使用 `198.18.0.0/15` Fake IP 池。Fake IP 反查恢复域名与 `DESTINATION_OVERRIDE` 改变连接目标是两个独立动作；两者可以同时发生，但不能互相替代。

核心统计集合定义为：

```text
用户关注的内网访问 = 实际应用目标重写 ∩ 重写后的实际目标为内网地址
```

命中重写规则但重写到公网地址仍为外网访问，不能把全部重写流量计为内网。只改端口也可能是目标重写，分类依据最终连接地址而不是域名是否改变。仅 DNS_REWRITING 应答或 Fake IP 恢复不构成目标地址重写。

新增以下连接汇总字段，并在建连结果保留决策快照：

| 字段 | 含义 |
| --- | --- |
| `destination_override_applied` | 本次是否实际应用 DESTINATION_OVERRIDE；路由名称包含“重写”或配置不完整不能充当依据 |
| `effective_target_host/port` | 重写后的实际目标配置快照；保留原始域名、原始目的地址与该字段，形成前后对照 |
| `selected_target_ip` | 重写域名解析后选定的真实目标 IP；成功直连时以实际 peer 校验 |
| `target_network_scope`、`target_address_class` | 真实目标地址的 INTERNAL／EXTERNAL／UNKNOWN 及具体地址类别，不混入域名资产归属 |
| `traffic_category` | `INTERNAL_REWRITE`、`EXTERNAL`、`OTHER_INTERNAL`、`UNKNOWN`，作为月报的业务分类 |
| `traffic_category_basis` | `OVERRIDE_PRIVATE_TARGET`、`PUBLIC_TARGET`、`OUTBOUND_PROXY_ROUTE`、`NON_OVERRIDE_PRIVATE_TARGET`、`UNRESOLVED` 等判定依据 |
| `traffic_classification_version` | 分类算法版本；配合 route ID／config version 重现本次判断 |
| `client_network_scope`、`outbound_peer_network_scope` | 独立的来源／出站端点属性，用于排障，不代替实际重写判定 |

分类复用现有路由与实际连接信息，不要求用户额外配置 NAS 地址、家庭域名资产或一套目标归属规则：

1. 从实际决策读取 `route_action=DESTINATION_OVERRIDE` 和是否应用有效重写，保存 rule/route ID、配置版本与重写前后目标。不得按展示名称、域名包含 NAS 字样或 DNS 回答猜测。
2. 对重写目标为 IP 字面量的情况直接分类；目标为域名时使用连接过程中实际解析／选定的 IP，不能仅凭域名认定内网，不新增统计专用 DNS 查询。IPv4-mapped IPv6 先规范化。
3. 实际应用重写且最终目标为 RFC1918／IPv6 ULA 等可确认的内网地址，分类为 INTERNAL_REWRITE；重写至公网单播为 EXTERNAL；尚未解析或特殊地址无法确认则 UNKNOWN。Fake IP、保留／基准测试地址不属于家庭 NAS 私网，不使用“非公网即内网”的判断。
4. 普通公网直连归 EXTERNAL；普通 OUTBOUND_PROXY 业务归 EXTERNAL，依据记录为 OUTBOUND_PROXY_ROUTE，这是该网关的业务流量分类，不宣称已知远端真实目标 IP。上游解析时 `selected_target_ip`／`target_network_scope` 可以仍为空／UNKNOWN，不能把代理地址填入真实目标字段。
5. 如出现未应用目标重写却直连私网的代理连接，保留 OTHER_INTERNAL 并单独展示，不把它混入用户关注的 INTERNAL_REWRITE，也不错误归为 EXTERNAL。无法判定的剩余情况保留 UNKNOWN。特殊部署的地址归类扩展可以后续单独设计，不在首期引入家庭资产归属配置。

对于本需求的 DESTINATION_OVERRIDE，当前代码通过 DirectOutboundConnector 直接连接重写端点，实际 peer 可以作为最终目标依据。后续若扩展为经上游代理进行目标重写，则仍以最终目标为准，不能以代理节点 IP 推断内网。日志只描述已有行为，不改变选路、DNS、目的地址校验和旧字段语义。

唯一连接汇总分别累计上传与下载，保持：

```text
总转发流量 = 重写至内网流量 + 外网流量 + 其他内网流量 + 未分类流量
重写流量 = 重写至内网流量 + 重写至外网流量 + 重写目标未分类流量
```

首要卡片为“内网访问（目标重写）”与“外网访问”；其他内网／未分类单列为补充项。可按重写规则、原始域名、重写后的 IP／端口、客户端、用户、Worker、入站、时间筛选，查看哪个映射服务产生大流量。上传／下载、趋势、月度与导出均使用同一分类，且计数／流量仅来自唯一汇总。分类覆盖率展示 UNKNOWN 的影响，不将其默认为外网。

阶段 B 在新分类下提供“流量分布”页面；阶段 C 经用户确认后才迁移原流量概览／入站统计。历史记录保持连接发生时的路由、配置和最终目标，后续规则变更不能悄悄改变历史分类。

来源网络与目标重写分类独立：公网客户端经过代理并被重写到私网 NAS，同样属于 INTERNAL_REWRITE；这并不表示没有经过 WAN。代理载荷统计不等于宽带账单。未经过网关的 LAN 直接互访不采集、不补算，也不纳入该统计的覆盖声明。

### 5.5 出站详细信息与实现复杂度

目标重写流量拆分属于连接汇总的派生分类，复用路由动作、是否应用重写、有效目标和实际连接地址即可。不新增日志类型／独立流量表，不逐包分类，不额外解析域名，也不增加一套家庭资产配置。实现可集中在新观测上下文和分类器，最终汇总时形成一次确定结果。模型的主要复杂度仍在统一生命周期、去重和可靠上报，而不是内网分类。

新模型完整覆盖“入站身份 → 原始／恢复目标 → 路由决策／目标重写 → 出站端点／协议阶段 → 转发与结束”。出站摘要保存在同一 session summary 和 CONNECT_RESULT；详细异常及未来重试保存在关联事件，首期不新增一张单独的出站日志表。

#### 5.5.1 出站摘要字段

| 字段组 | 字段与约束 |
| --- | --- |
| 选择结果 | `route_id/name/action`、`outbound_mode/protocol/node_id`、出站端点名称／配置版本；入站协议与出站协议各自保存 |
| 目标与端点 | 原始域名／IP、effective target、selected target IP、`outbound_endpoint_host/port`（配置中的连接端点）、`outbound_peer_ip/port`（实际 socket 端点）分开 |
| 本机出站地址 | `outbound_local_ip/port` 从已建立 socket 读取，便于多出口／绑定地址排障；网卡与路径只有实际观测到才能记录 |
| DNS 责任 | `target_resolution_mode=LOCAL/UPSTREAM/IP_LITERAL/UNKNOWN`；本地目标解析与代理节点解析通过 query ID 和 resolve purpose 区分 |
| 分阶段结果 | `tcp_connect_outcome`、`outbound_tls_outcome`、`proxy_auth_outcome`、`proxy_tunnel_outcome`，不适用为 NOT_APPLICABLE，未知为 UNKNOWN |
| 分阶段时间 | 节点／目标解析、TCP、TLS／REALITY、代理认证／通道握手、整体通道可转发耗时；实际执行时测量，不能从整体耗时猜子阶段 |
| 协议响应 | `upstream_response_code`、`upstream_response_code_type`，保存 HTTP CONNECT 状态／SOCKS reply 等，不能当目标网站 HTTP 状态 |
| 可转发边界 | `channel_ready_at`、`channel_ready_basis`；区分 TCP_CONNECTED、LOCAL_REQUEST_SENT、UPSTREAM_ACCEPTED |
| 上游确认 | `upstream_acceptance=CONFIRMED/REJECTED/UNCONFIRMED/NOT_APPLICABLE`，和本机可以开始转发不是同一个含义 |
| 尝试与错误 | `outbound_attempt_count`、最终采用的 attempt ID、阶段／错误码；重试时每次尝试独立标识，最终汇总仍只有一条 |

出站 TLS 字段仅表示网关与上游间实际执行的安全握手，不从用户 HTTPS 负载猜目标网站 TLS 成功。上游认证字段只保存是否尝试、方法和结果，不保存出站密码、认证头、密钥或完整握手内容；也不混入第 7.1 节的客户端入站密码认证表。

现有 `ProxyTunnelRequest.getFinalTargetHost()` 在 OUTBOUND_PROXY 路由中可能返回代理节点端点，所以新模型不能直接把它写成 effective target。有效业务目标与连接端点分别从路由语义、发送到上游的目标和实际 socket 获取。旧方法与旧字段保持原有行为。

#### 5.5.2 不同出站协议的确认边界

| 出站 | 本机通道可转发的观测点 | 能确认到什么程度 |
| --- | --- | --- |
| DIRECT／DESTINATION_OVERRIDE | TCP 建立成功 | 连接到了实际目标端口，尚不能证明业务响应正常 |
| HTTP CONNECT | 收到上游 CONNECT 200 并完成隧道切换 | 上游接受通道，非目标网站返回 HTTP 200 |
| SOCKS5 | 方法／认证完成，CONNECT reply SUCCESS | 上游接受连接请求；认证失败、方法拒绝与目标连接失败分别记录 |
| Shadowsocks | 本机实际协议初始化及目标头发送完成 | 无通用的远端目标建连成功应答时为 UNCONFIRMED，不伪造上游确认 |
| VLESS REALITY | REALITY 握手完成及请求初始化发送完成，按现有 handler 的实际就绪点 | 记录本机可转发；收到可确认的 VLESS 响应时另记确认时间／事件，不让初始请求发送成功冒充远端目标已连接 |

当前代码的 connector `ChannelFuture` 通常覆盖地址解析和 TCP 连接，而 `relayPromise` 还承载代理初始化／握手。旧成功标记和目标连接耗时偏向前者；新采集需独立观察两种结果，并在协议 handler 处记录子阶段，不改变旧日志语义。

`connect_outcome=CONNECTED` 定义为本机通道已可以转发，并配合 readiness basis 解释确认强度；HTTP／SOCKS 的上游明确应答与 Shadowsocks／VLESS 的本机请求发送不能视为完全相同的远端证据。上游确认率按有确认能力的协议及真实观测结果计算，不把不支持确认的协议自动视为失败。

TCP 成功但上游拒绝时，新 CONNECT_RESULT 为 FAILED，failure stage 为 PROXY_AUTH／PROXY_HANDSHAKE 等，TCP 子阶段仍为成功。若本机已可转发后才收到拒绝／异常，保存诊断事件和 ERROR_END，不改写此前真实的就绪时间。各时间点快照进入最终汇总，阶段事件提供及时观测。

#### 5.5.3 示例与实施范围

- NAS 重写：入站 TP_PROXY，原域名 nas.example.test，重写目标 192.168.10.85:8096，出站 DIRECT，实际 peer 同为 NAS 地址，TCP 成功、代理握手不适用，业务分类 INTERNAL_REWRITE。
- 国外网站经 SOCKS5 上游：入站可为 TP_PROXY／HTTP CONNECT，业务目标仍为网站域名；出站 SOCKS5、配置端点和实际代理 IP 独立保存；本机目标解析由上游负责则真实目标 IP 未知；按协商、代理认证、CONNECT 应答分别记录结果。
- HTTP 上游 TCP 连通但返回 407：保存 TCP 成功、上游 407、通道建立失败；不计为客户端密码错误，也不计为目标网站 HTTP 错误。

首期采集所有现有 connector 的确定结果和实际可观测子阶段，未埋点明确为空／UNKNOWN；不为日志设计增加原本不存在的自动重试、节点切换、握手或探测。未来若实现这些能力，增加 OUTBOUND_ATTEMPT_RESULT 等关联事件，按 attempt ID 统计尝试质量，按 connection ID 统计连接结果，不能把两者混算。

## 6. DNS 查询模型

| 字段 | 定义 |
| --- | --- |
| `query_id`、`parent_connection_id` | 逻辑查询 ID；代理内部解析可关联连接，客户端查询通常无父连接 |
| `query_origin` | `CLIENT` 或 `PROXY_RESOLVE` |
| `resolve_purpose` | 内部解析为目标地址、上游代理端点等，避免混淆 |
| `qname`、`qtype`、`qclass`、`transport` | 规范化 DNS 名称、查询类型、类及 UDP／TCP／DoT 等传输方式；按实际支持能力记录 |
| `rcode` | DNS 数值响应码，不能用 HTTP 200/502 代替 |
| `outcome` | `ANSWERED`、`BLOCKED`、`ERROR`、`CANCELLED`；协议否定应答与系统错误分别解释 |
| `answer_mode` | `FAKE_IP`、`REAL`、`REWRITE`、`BLOCK`、`UNKNOWN` |
| `answer_ips`、`ttl_seconds` | 地址回答与实际应答 TTL；需要表达多 RR TTL 时保留结构化答案扩展 |
| `empty_answer_reason` | 如 `IPV6_SUPPRESSED`、`NODATA`、`POLICY_BLOCK`、`ERROR`、`UNKNOWN` |
| `cache_status` | `HIT`、`MISS`、`NOT_APPLICABLE`、`UNKNOWN` |
| `shared_lookup_id` | 并发查询合并时关联一次真实上游解析，区分逻辑查询量与实际上游请求量 |
| `dns_route_id`、`resolver_endpoint` | DNS 决策与实际解析端点；未访问上游则端点为空 |
| `upstream_attempt_count` | 本次解析的上游尝试次数，不等同于客户端重试次数 |
| `duration_us`、`upstream_duration_us` | 查询整体与上游阶段耗时；缓存命中时上游耗时为空 |
| `response_sent`、`error_code/message` | 客户端回复是否成功提交发送以及错误；生成应答不等于成功发送 |
| `request_bytes`、`response_bytes` | DNS 报文大小，独立于连接载荷流量 |

Fake IP AAAA 抑制应明确记录 `qtype=AAAA`、`rcode=NOERROR`、`answer_mode=FAKE_IP`、`empty_answer_reason=IPV6_SUPPRESSED`。显式阻断、重写和不适用 Fake IP 的名称沿用实际策略，不把所有 AAAA 空应答都判为预期行为。

DNS `NXDOMAIN` 是协议否定应答，不直接等于网关服务异常；系统超时、上游不可用、应答解码失败应有不同错误码。内部解析没有对客户端发送应答，`response_sent` 为不适用；UDP 写成功仅说明本机发送完成，不保证客户端收到。

一条逻辑 DNS 查询最终只写一份结果；内部重试可写关联的诊断事件。多个客户端订阅合并解析时各有 query ID，但共享解析的实际请求次数不能按订阅数重复累计。

客户端查询与后续 TCP 连接不建立强制一对一关系。可新增 `mapping_id` 与 `mapping_registry_id` 辅助分析租约及地址复用；映射表实例重建时变更 registry ID，不能仅用 Fake IP 地址跨重启强行关联域名。

## 7. 诊断事件与早期失败覆盖

首期重点实现下列事件，不逐包记录，不把每次 write 回调变成一条持久日志：

| 事件 | 记录单位 | 主要内容 |
| --- | --- | --- |
| `TARGET_RECOVERY_RESULT` | 每条需要恢复目标的连接一个最终结果 | 是否为 Fake IP、映射 HIT/MISS/NOT_ATTEMPTED、回退方式、最终恢复结果和耗时 |
| `CONNECT_RESULT` | 每条连接一个整体建连结果 | 是否尝试建连、结果、失败阶段、各阶段耗时、路由与出站端点 |
| `RELAY_ERROR` | 每连接按错误类别与方向聚合 | 错误类别、首末发生时间、重复次数，辅助诊断 |

`TARGET_RECOVERY_RESULT` 的结果包括 `RECOVERED`、`TIMEOUT`、`INVALID`、`UNAVAILABLE`、`CANCELLED`。映射命中也必须记录，即使后续路由阻断或建连失败；这样恢复率不受最终连接结果影响。

`CONNECT_RESULT` 区分是否进入建连阶段。只统计已经尝试建连的记录时，跳过 `NOT_ATTEMPTED`；策略阻断和客户端取消独立展示。整体结果事件与未来每次尝试事件使用不同类型，不能把重试次数当连接数。

恢复失败、认证失败、路由缺失、私网目的地址拒绝、环回／自连接拒绝、嗅探超时、缓冲超限和 BLOCK 策略均需在新链路形成明确结果。目标尚未知时保留为空，不能因为上下文不完整而跳过新日志。

重复错误在连接内聚合，保存 `repeat_count`、`first_occurred_at`、`last_occurred_at`。连接结束时输出最终错误摘要，首次异常可以即时输出单独事件；若同一事件做版本更新，必须复用 event ID 并带单调 revision，查询取最终版本，不能把初版和终版当两次错误。首期优先采用首次异常事件加独立最终摘要，避免引入无必要的更新模型。

事件不存放可被当成最终流量求和的字节字段。由 connection ID 展开过程；需要跨连接查看 DNS 时保留 query ID 关联。认证行为使用下节新增的独立模型，不采集密码、Token 或解密业务内容。

### 7.1 新用户名密码认证日志

#### 7.1.1 范围与记录时机

新建 `CredentialAuthLogV1` 和 `telemetry.credential_auth_log_v1`，服务于入站凭据验证与异常认证来源分析。首期覆盖当前代码实际进行用户名密码验证的 SOCKS5 PASSWORD 和 HTTP CONNECT Basic。未来新入站支持用户名密码时复用本契约。

透明代理、DNS、TLS SNI、设备 IP 匹配、匿名入口不生成密码认证成功日志；Shadowsocks 密钥校验不等同于用户名密码，不强行并入本表。管理台 `/admin/login` 属于管理面认证，保持原模型，若需统一展示再通过显式类型另行接入。上游代理认证亦不算客户端入站认证。

- 每次实际提交凭据并完成检查时立即记录成功、失败或策略拒绝，不能等隧道建立／连接关闭后再记录。
- 成功验证即写 SUCCESS，即使客户端随后没发送目标请求或建连失败，也不能改成认证失败。
- 失败早于目标与路由确定，仍必须有 connection ID、入站和来源 IP；user ID、用户名确认信息、目标允许为空。
- SOCKS5 临时账号锁定写 DENIED；不支持 PASSWORD 的方法协商写单独协议拒绝，不能当密码错误。
- HTTP 缺少 Proxy-Authorization 时的 407 写 CHALLENGE／MISSING_CREDENTIALS，它可能是合法客户端的首次挑战；不计入密码验证失败率。
- Basic 编码或格式错误写 MALFORMED；密码错误写 FAILURE／INVALID_CREDENTIALS。异常与连接关闭不会对同一尝试重复写记录。
- 一次挑战和之后重新连接提交凭据是不同尝试；同一连接允许多个认证请求时以 auth attempt ID 区分，不以“每连接仅一条”去重。

仅增加审计埋点，不改变现有密码比较、错误响应、账号锁定或协议行为，也不启用新的自动封禁。

#### 7.1.2 字段与来源定位

除公共 Worker／实例／入站／连接／时间／模型版本字段外，定义：

| 字段 | 含义 |
| --- | --- |
| `auth_attempt_id` | 本次认证尝试的稳定 ID，作为记录去重依据；重发保持不变 |
| `auth_method`、`auth_event_kind` | SOCKS5_PASSWORD／HTTP_BASIC，以及 CREDENTIAL_CHECK／CHALLENGE／PROTOCOL_REJECTION |
| `attempted_username` | 客户端提交的用户名，标记为未确认输入；按日志长度限制、去控制字符并转义展示 |
| `user_id`、`verified_username` | SUCCESS 时为已确认账号；其他结果只保留能够确认的关联信息，不能伪造已认证身份 |
| `auth_outcome`、`reason_code` | SUCCESS／FAILURE／DENIED／CHALLENGE／MALFORMED／CANCELLED／ERROR，以及结构化原因 |
| `client_ip/port`、`client_ip_source`、`transport_peer_ip/port` | 实际来源与可信转发来源，禁止信任任意自报头 |
| `source_network_scope` | INTERNAL／EXTERNAL／UNKNOWN，按来源规则分类，不能使用目标网络归属 |
| `source_country_code`、`source_country`、`source_region`、`source_city` | 地理位置及 ISO 国家码；未知为空，区域缺失不能补成所在国家 |
| `geo_lookup_status`、`geo_database_version` | RESOLVED／PRIVATE／NOT_FOUND／DB_UNAVAILABLE／ERROR／UNKNOWN；记录 GeoIP 版本 |
| `auth_duration_us`、`config_version` | 实际检查耗时和生效配置，未检查阶段为空 |

源 IP 的 GeoIP 丰富在管理端新日志处理链路中执行，使用已验证的 IP 字面量和有界缓存，不做反向解析，不在 Netty 认证线程进行磁盘查询。私网来源记 PRIVATE，不填写一个虚假的国家／城市。GeoLite2 无结果或数据库缺失时保留认证记录并显示位置未知，不能当成中国或“正常位置”。

Worker 原始认证结果在重发时不可变；GeoIP 与风险标签属于管理端分析结果，拥有独立版本与状态。其补充／重算不能产生新 auth attempt，也不能修改认证是否成功。同 ID 内容冲突检查针对原始事实，避免将丰富字段变化误判为协议冲突。

日志中禁止保存密码、密码散列、原始 Authorization／Proxy-Authorization、Basic Base64／解码串、完整认证报文或可能包含凭据的异常消息。无法安全拆出用户名时留空，不为了诊断保存整段失败输入。需要 ASN／运营商分析时新增实际数据源，目前 GeoLite2-City 不保证提供 ASN。

#### 7.1.3 异常来源分析

成功和失败都要可按来源 IP、国家／地区、账号、Worker、入站、协议、结果、时间筛选；未知账号按 attempted username 分析，不能丢弃。主要指标如下：

| 分析 | 口径／用途 |
| --- | --- |
| 认证成功率与失败原因 | SUCCESS / (SUCCESS + FAILURE)，仅实际完成凭据验证；DENIED、CHALLENGE、MALFORMED、CANCELLED、ERROR 独立展示 |
| 来源 IP／国家分布 | 同时呈现成功与失败、独立 IP 数、未知位置占比 |
| 高频失败来源 | 同一 IP 在可配置窗口内的失败次数与尝试账号数，区分集中账号攻击和多账号尝试 |
| 新来源／新国家 | 与此前成功来源及账号／入站明确配置的常用地区比较，尤其标记陌生地区 SUCCESS |
| 不符合预期地区 | 对已明确配置的账号／入站预期国家或网络规则判断；地区不明时为无法判断 |
| 失败后成功 | 同来源、同账号的一段失败之后出现成功，可展开关联尝试，不直接认定入侵 |
| 地理位置短时变化 | 同账号在短时间出现不同国家的成功尝试，作为提示；NAT、VPN、漫游和 GeoIP 精度都可能影响判断 |

地理异常采用可解释标签，例如 NEW_SOURCE_IP、NEW_COUNTRY、OUTSIDE_EXPECTED_REGION、FAILURE_BURST、LOCATION_UNKNOWN，保存规则版本和比较依据。新 IP 不直接等于异常，城市变化不单独判定高风险。初次上线无历史基线时标记 BASELINE_INSUFFICIENT，不把所有首次来源判为异常。

历史基线按账号及实际授权的入站／Worker 范围建立，失败尝试不进入成功基线；滚动成功历史仅作为比较证据，不自动改写明确配置的可信范围。异常成功不能因为重复出现就静默变成可信来源。分析规则与窗口可配置，位置未知不能被当作正常而过滤。风险判断不改变认证结果，首期提供界面提示与追踪，不自动封禁或发送外部通知。

认证尝试按稳定 ID 完整采集，不按错误级别抽样；挑战或畸形请求数量过大时用独立容量控制与采集完整性指标处理，不能影响成功／失败日志与原认证流程。账号锁定、拒绝、系统错误与真实密码检查有不同分母，页面和 API 必须说明。

## 8. 双轨采集与可靠上报

### 8.1 新旧链路隔离

```text
代理生命周期／DNS 结果
  ├─ 现有 AccessLogUtil、AuthLog、旧队列、WSS 字段
  │    └─ 现有 LogService／旧 writer → default.access_log／auth_log → 现有页面
  └─ 新观测上下文、collector、独立队列与批次
       └─ 新 ingest／writer → telemetry.*_v1 → 新 API／网关观测页面
```

新增上下文在接收连接时建立，不替换现有 `ProxyContext`／`ProxyTimeContext`。从协议、认证、恢复、路由、DNS、connector 和 relay 的实际行为增加新观测钩子；旧 `logSuccess/logFailure/logDns` 的调用与语义继续保留，包括既有过滤行为。不能为修正新统计而改变旧成功回调或旧 BLOCK 过滤。新凭据认证有独立类型／队列／开关，保留旧 AuthLog 的全部设施；旧表未使用或为空也不是删除理由。

新模型使用独立收尾方法和状态机，在 EventLoop 内串行修改，跨线程完成操作回到所属 EventLoop。最终汇总有一次性保护，错误不会被关闭回调覆盖。发布前复制不可变快照，不把可变上下文直接交给异步队列。

观测失败不改变实际转发结果，也不拖慢旧日志和配置轮询。新存储故障不能阻止旧日志进入旧 writer。用独立批次、队列、资源限额和异常边界隔离新链路，不要求新旧日志具有分布式事务一致性。

### 8.2 控制开关与协议兼容

新采集、诊断细节和新查询可独立启停，初始按 Worker 灰度开启。关闭新链路不关闭旧链路；已有旧日志上报开关默认保持其原有语义，不暗中扩大或缩小采集范围。新增开关的传播方式应同步 `common`、管理端配置/hash、Worker 和前端。

管理端先升级。新协议通过明确的版本／能力协商启用独立新日志批次及 ACK；缺少协商时新 Worker 只使用旧链路，不向旧管理端发送未知消息类型。不能假定增加 JSON 字段对所有旧反序列化实现都安全；升级测试必须验证真实旧版本。

| 组合 | 行为 |
| --- | --- |
| 旧管理端 + 旧 Worker | 保持现状 |
| 新管理端 + 旧 Worker | 旧日志可用，新观测显示未支持／未开启 |
| 新管理端 + 新 Worker，未开启新采集 | 仅旧日志，新查询无样本 |
| 新管理端 + 新 Worker，协商并开启 | 双轨运行 |
| 管理端回退／能力消失 | 新 Worker 停止新协议发送，保留未确认批次，旧链路继续 |

新管理消息 DTO、能力声明、批次 ACK 与任务／配置消息的异常隔离在实施时补充协议细节。批次大小按记录数和字节数限制，不能因日志积压饿死心跳和配置下发。

### 8.3 交付与重试

2026-10-09 按用户后续确认调整：Worker 沿用有界内存缓冲思路，不为新日志增加本地磁盘持久化。适用于当前内网部署，由 controller 及时消费；交付语义明确为 BEST_EFFORT_WITH_ACK，保留进程内重试与幂等查询。

1. 固定 record/event ID 与记录内容后入队，发送时组批并固定 batch ID；重发保持身份和内容不变。
2. Worker 只保存有界内存队列与待 ACK 批次，不写日志缓存文件，不执行 fsync，不在启动时恢复日志。默认最多 5,000 个事实、4 MiB 序列化记录载荷；待 ACK 事实也占用额度。
3. 管理端完成 CK 持久化后 ACK；仅进入管理端内存队列不能发送持久化确认。
4. CK 不可用、连接断开或 ACK 丢失时，在当前 Worker 进程内保留批次并退避重试。同一 ID 不同内容视为协议冲突，不能静默选一份。
5. Worker 停止、重启或崩溃后，未确认的内存记录可能丢失；容量不足时拒绝新记录并增加 dropped，不驱逐待确认批次。状态显式返回 bufferMode=MEMORY、restartRecoverySupported=false、内存占用、容量与丢弃计数。
6. 不承诺跨重启至少一次交付或端到端 exactly-once。缺少最终汇总的连接保持结束未知，不补造成功或最终流量。本次验收改为内存容量、稳定批次、ACK 重试与重启后无恢复，撤销 Worker 持久队列/重启恢复要求；controller 中已落库的新旧日志数据仍保留。

## 9. ClickHouse 存储与查询约束

建议在同一 CK 实例新增独立数据库 `telemetry`，表名为：

```text
telemetry.proxy_session_log_v1
telemetry.dns_query_log_v1
telemetry.proxy_event_log_v1
telemetry.credential_auth_log_v1
```

原 `default.access_log`、`default.auth_log` 和现有初始化器保留。新表初始化使用独立组件，不把旧表 ALTER 成新模型，不对旧表 TRUNCATE／DROP，不把旧 MySQL 日度／分钟聚合清理当作新模型初始化步骤。

- 事件时间使用 `DateTime64(..., 'UTC')`，页面按用户时区展示。
- 结果／协议／阶段用低基数字符串，ID、域名、地址按实际基数选择类型。
- 不适用的数值、IP 和标识允许为空，避免沿用旧 writer 的全字段 null-to-zero 规则。
- 记录不可变；如需更新事件摘要则使用明确 revision，不借助接收时间冒充业务版本。
- 可采用 `ReplacingMergeTree`，但业务 API 必须显式去重，不能等待后台合并。
- 分区使用稳定事件时间；排序／替换键包含 Worker、实例、稳定时间和记录 ID，重发保持完全一致，使重复进入同一分区和键。
- 初期查询使用经过基准测试的 `FINAL` 或按记录 ID 取唯一行后聚合；禁止只对请求量去重、流量仍对原始行求和。
- 重试原始行上直接建立求和物化视图会重复累计，必须在唯一事实层或幂等聚合机制之上实施。
- 连接、DNS、事件、新认证分别配置保留期；认证至少覆盖配置的异常分析窗口，否则基线标记不足。双轨观察期根据磁盘预算设定新表策略，旧表保留策略不在本需求中自动改变。不要预设“观察若干天即清旧表”。

API 查询默认强制 Worker 范围和时间窗口，使用参数化过滤、受控排序字段及分页限制。多表展开详情时按稳定 ID 关联，不在所有看板指标中强制大表 JOIN。

## 10. 指标定义

以下统计先去重，明确记录时间、分母、样本量和采集覆盖范围。分母为 0 显示暂无数据，不能显示 100% 成功。

| 指标 | 数据源与口径 |
| --- | --- |
| 客户端 Fake IP 应答量 | CLIENT DNS 查询，A、FAKE_IP、实际有池内地址，按 query ID 去重 |
| AAAA 预期抑制量 | CLIENT DNS，AAAA、NOERROR、FAKE_IP、IPV6_SUPPRESSED |
| Fake IP 映射命中率 | 池内连接恢复结果中 HIT / (HIT + MISS)，按 connection ID 取唯一结果；未尝试不入分母 |
| 域名恢复成功率 | 池内恢复尝试中 RECOVERED / 全部最终恢复结果；取消独立展示，并明确是否计入当前视图分母 |
| 回退使用率 | 池内恢复结果中实际进入回退的连接 / 完成映射查询的连接 |
| 回退成功率 | 实际回退尝试中 RECOVERED / 全部最终回退结果；取消独立展示 |
| 建连成功率 | CONNECT_RESULT 中 CONNECTED / 已尝试且结果为 CONNECTED 或 FAILED 的连接；BLOCKED、CANCELLED 单列 |
| TCP／代理握手失败率 | 按实际执行该阶段的尝试计算，不以全部连接为分母 |
| 出站节点／协议质量 | 按 route／node／protocol 比较 TCP、认证、代理通道结果与耗时；就绪依据和确认能力分别展示，不用请求发送成功代表远端接受 |
| 最终传输异常率 | 汇总中已建连且 ERROR_END、失败阶段 RELAY / 已结束且已建连的连接 |
| 策略阻断量 | DNS 与连接各按自身记录单位计数，不能直接相加称为被阻断连接／广告数 |
| DNS 系统错误率 | CLIENT 与 PROXY_RESOLVE 分开，超时／上游故障等系统错误占比；NXDOMAIN、策略阻断另列 |
| DNS 缓存命中率 | 可缓存的真实解析 HIT / (HIT + MISS)，排除 Fake IP 发放、重写、阻断和未知 |
| 上游实际请求量 | 用 shared lookup 和真实 attempt 统计／运行计数器，不能把共享查询订阅数当请求量 |
| 建连 P50/P95/P99 | 成功、失败分开，按路由／节点／协议分组，不混入未执行和未知耗时 |
| 连接数与最终流量 | 唯一 session summary，按 ended_at 汇总；只累加 upload/download 一次 |
| 内网访问（目标重写）流量 | 同一唯一 session summary 中实际应用 DESTINATION_OVERRIDE 且最终目标为内网，即 traffic_category=INTERNAL_REWRITE；上传／下载只累计一次 |
| 外网／其他内网／未分类流量 | 按 traffic_category 分组；普通代理业务、公网直连、重写至公网归 EXTERNAL，非重写私网直连单列 OTHER_INTERNAL，四类之和等于总量 |
| 目标重写流量与内网占比 | 实际重写的汇总中，分别统计最终内网／公网／未知；INTERNAL_REWRITE 仅为目标重写集合的子集 |
| 流量分类覆盖率 | 已分类字节 / 全部字节；同时显示 UNKNOWN 原因，不当宽带用量，不以代理节点 IP 填补真实目标 |
| 认证结果与来源异常 | 独立 credential auth 表，按 auth attempt ID 去重；凭据成功率、来源位置与风险标签遵循 7.1 节，未知位置和基线不足单列 |
| 空转发连接量 | 汇总中已建连且双向字节为 0，不称为业务失败 |
| 路由兜底比例 | 完成路由决策的连接中 DEFAULT 占比，保留配置与规则集版本 |
| 限流／背压影响 | 实际等待时长与连接分布，按方向和策略分组 |

客户端 DNS 缓存、预解析和连接复用使 DNS 应答与连接数没有固定比例，不能使用“连接数 / DNS 应答数”作为成功率。最终流量按结束时间归属不代表逐分钟实际传输速率；实时速率由运行指标或独立增量采样承担。

运行指标继续采集活跃／待建连数量、接收连接速率、实时吞吐、CPU／内存／FD／EventLoop 延迟、映射容量／占用／淘汰、新日志队列积压／丢弃／写入失败／ACK 延迟。累计计数按 worker instance 处理重置，不能跨重启直接做负差分。

## 11. 前端与 API 双轨入口

### 11.1 新分类

新增顶级导航“网关观测”，建议路径：

| 页面 | 路径建议 | 展示内容 |
| --- | --- | --- |
| Fake IP 观测 | `/config/observability/fake-ip` | 应答、映射、回退、建连趋势和异常定位 |
| 连接记录 | `/config/observability/sessions` | 唯一汇总、流量、生命周期、路由；详情展开事件与内部 DNS |
| DNS 查询 | `/config/observability/dns` | 查询类型、来源、模式、响应码、缓存、耗时 |
| 诊断事件 | `/config/observability/events` | 恢复／建连／传输异常，按连接追踪 |
| 流量分布 | `/config/observability/traffic` | 重写至内网与外网月度／趋势，其他内网／未分类，重写规则／目标和客户端 TopN |
| 认证审计 | `/config/observability/authentication` | 密码认证成功／失败、挑战／拒绝、来源地理分布、异常来源提示与详情 |

已有“访问概览”下的日志审计／聚合分析，以及仪表盘流量概览、入站配置统计保持原路由、API、类型与数据源。新导航只添加，不覆盖既有入口，不在旧页面悄悄混入新表数据。

新页面显示采集是否启用、模型版本、开始时间、最后接收时间、选中 Worker 的能力与数据完整性。部分 Worker 不支持新模型时明确显示覆盖不足，不能把缺失节点当作零流量或健康。

流量页主要展示“内网访问（目标重写）”“外网访问”，补充“其他内网”“未分类”；月度／日度趋势、上传／下载、过滤和导出均保留 trafficCategory 维度。连接明细展示是否实际应用目标重写、命中规则、原始域名／IP、重写目标、最终连接 IP 和分类依据。复用现有目标重写配置，不新增家庭资产归属配置入口。认证页展示实际来源与可信转发来源、国家／地区、GeoIP 状态、提交用户名、已确认账号、结果／原因和风险标签；认证失败无需目标地址也能查看。身份与地理未知状态不隐藏。

### 11.2 API

新增独立命名空间 `/api/observability/v1`，包含 sessions、dns、events、credential-auth、Fake IP 聚合、分业务分类流量统计／时间序列、认证来源分布／风险分析、采集状态。流量参数提供 trafficCategory、destinationOverrideApplied、routeId、原始域名、重写目标／最终 IP、分类算法版本；targetNetworkScope 作为独立地址维度，不代替业务分类。返回四类及总量／覆盖率，并提供实际重写集合中的内外网分布。认证参数提供来源 IP、用户名／账号、协议、认证结果、国家／地区、风险标签和 GeoIP 状态。详情通过稳定记录／连接／认证尝试 ID 获取，不沿用旧请求 ID 查找。

流量拆分读取既有目标重写路由的生效快照和实际连接信息，不新增家庭资产、域名归属或 NAS 地址配置，亦不修改现有配置 CRUD。新增认证预期地区／来源规则与分析窗口属于观测配置，不能意外变成新认证授权或改变既有放行行为。实施前为认证观测配置补充 common DTO、存储迁移、发布 hash、前端契约和兼容测试。

保留 `/api/logs` 及所有既有统计 API 的语义。现有 `AccessLogStoreFactory` 不切换到新 store；新 API 走独立 repository/service。前端新增独立类型文件和 API client 方法，复用鉴权与基础请求设施，不复用旧 `AccessLogDetail` 承载不同语义。

## 12. 常见访问的记录示例

| 场景 | 新日志记录 | 可解释的问题 |
| --- | --- | --- |
| 国内网站 Fake IP 直连 | 客户端 A／AAAA 查询；映射 HIT；关联连接的真实解析；CONNECT_RESULT；唯一汇总 | 是 DNS 慢、建连慢，还是策略错误 |
| 国外网站经过上游 | 客户端 Fake IP 查询；映射 HIT；TCP／代理握手结果；汇总 | 上游端口不通与代理握手失败分别定位；远端解析 IP 保持未知 |
| 客户端缓存旧 Fake IP | 可能没有新客户端查询；映射 MISS、SNI 回退成功；建连结果；汇总 | 最终正常但映射状态已经异常 |
| 映射 MISS 且嗅探超时 | 恢复 TIMEOUT；未尝试建连的结果／汇总 | 恢复失败不能错算成目标服务器连接失败 |
| 广告 DNS 阻断 | 一条 BLOCK DNS 结果；没有后续连接则没有连接汇总 | 策略生效量与系统故障分开 |
| 下载 20 MB 后重置 | 建连成功事件；聚合传输异常；ERROR_END 汇总且下载 20 MB | 一条连接只计 20 MB，不因多个错误重复求和 |
| 引流域名 DNS 返回公网 IP，代理重写到 NAS | DESTINATION_OVERRIDE 实际生效，最终 192.168.10.85:8096，INTERNAL_REWRITE；下载 800 GB | 800 GB 计入内网访问（目标重写），无需再配置 NAS 归属 |
| 同样命中目标重写，但重写到公网服务 | DESTINATION_OVERRIDE 实际生效，最终目标为公网 IP，EXTERNAL | 内网访问仅为重写场景的子集，不把全部重写都当内网 |
| 重写到域名，实际解析到内网／公网 | 记录重写域名和真正选定／连接的 IP，据实际结果分类 | 不根据域名看起来像 NAS 或 DNS 原回答决定分类 |
| 家庭 NAS 保持公网访问，未重写到私网 | 公网实际目标或正常出站代理业务，EXTERNAL | 不因服务属于家庭 NAS 就自动归内网，按本次实际行为统计 |
| 普通 LAN 设备直接互访 | 未经过网关，无代理日志 | 不补算到代理内网流量 |
| 月度重写至 NAS 800 GB、外网 200 GB、其他内网 0、未知 10 GB | 唯一汇总四分类求和，总量 1,010 GB | 明确展示重写至内网 800 GB、外网 200 GB 和未知 10 GB |
| 陌生国家认证失败／成功 | 独立认证尝试，保留来源、国家、用户名、结果和风险依据 | 同时发现失败试探与异常成功；位置未知和新基线不直接等同攻击 |
| HTTP 首次无凭据后正常认证 | 第一条 CHALLENGE，下一次凭据提交 SUCCESS | 407 挑战不计成密码错误，不虚增失败率 |

## 13. 分阶段实施与用户确认点

### 阶段 A：新增模型与独立基础设施

- 新 common DTO、枚举、能力协商及日志批次／ACK 契约。
- 新表初始化、独立 ingest/writer/query service 与有界内存缓冲（按后续用户确认，不做 Worker 日志持久化）。
- Worker 接收时建立新上下文；独立开关，默认不改变旧行为。
- 增加目标重写是否应用、原始／重写／实际目标、业务流量分类与算法版本；复用现有重写路由，无需新家庭资产配置。新增独立凭据认证 DTO／表、来源 GeoIP 与异常分析契约。
- 增加模型、生命周期、重试、幂等与兼容测试。

完成标准：旧代码与旧环境表全部保留；新基础设施可独立关闭；原转发、旧采集、旧 API 和旧页面继续工作。

### 阶段 B：双轨采集与新界面

- 逐个 Worker 开启，覆盖核心恢复、DNS、建连、关闭及早期失败，同时开启 SOCKS5 PASSWORD／HTTP Basic 的新认证审计。
- 新增“网关观测”分类及独立 API，包括流量分布与认证审计；旧页面继续查询旧表。
- 验证公网引流域名重写至 NAS、重写至公网、重写域名实际解析、正常外网代理及 OTHER_INTERNAL／UNKNOWN 的拆分，认证成功／失败／挑战／异常来源和位置缺失的可解释性。
- 记录新链路资源开销、丢弃与延迟，验证正常访问及故障场景。
- 继续观察，不根据时间到期或自动指标达标切换页面。

完成标准：形成可供用户审阅的数据、对比说明、异常分析与验证报告。用户明确确认新模型稳定后才能启动阶段 C。

### 阶段 C：逐项迁移现有查询

建议顺序：入站配置中的统计查询 → 用户／全局流量概览 → 聚合分析 → 旧日志审计的展示与入口整理；具体批次由实际调用依赖决定。

- 每个功能使用明确的新查询适配或独立数据源选择，可快速回退旧源。
- 流量概览、入站流量及导出统一使用 INTERNAL_REWRITE／EXTERNAL／OTHER_INTERNAL／UNKNOWN，主展示重写至内网与外网，不能迁移后继续只显示混合总量、把全部重写归内网或隐藏补充分类。
- 不改变入站等配置 CRUD 的 MySQL 数据源，只迁移相关日志统计查询。
- 一次查询只使用一种事实来源，不相加新旧数据，不按“新数据为空”自动混用旧数据。
- 明确迁移后的历史覆盖起点；不要求回填旧模型，也不伪造新模型启用前的统计。
- 保留旧采集、代码与表，验证周期内回退仍有效。

完成标准：用户确认各功能迁移正常，列明所有旧表消费者、后台任务、报表、备份／运维依赖与回退窗口。页面已迁移不构成旧表清理授权。

### 阶段 D：最终清理（独立任务）

用户另行确认后制定具体清理变更和运维步骤：停止旧采集与旧写入，确认无剩余读写消费者；处理旧汇总／后台任务依赖；按确认范围归档或删除旧数据；删除旧代码与环境表；更新部署、备份、恢复与研发文档。

不得在阶段 A/B/C 的部署脚本、初始化器、Flyway、TTL 或默认配置中藏入旧表删除行为。清理访问日志不自动包含认证表或其他业务数据。删除旧表后不能再承诺仅靠配置回退到旧源，需明确备份恢复方案或已接受的不可回退边界。

## 14. 对比、验收与测试

### 14.1 新旧统计差异解释

双轨是并行采集与独立验证，不是要求两个模型 count/sum 完全一致。旧日志有重复事件、重复累计流量、状态覆盖和过滤，新模型补齐早期失败及阻断；这些差异是预期修正。

优先用可控连接／DNS 查询与已知转发载荷验证新模型，再与旧链路对照相同 Worker、窗口、协议、客户端和目标。确认每项差异能够解释，不能把旧表作为正确值反向调整新模型。旧模型没有稳定连接 ID 时，地址组合与时间仅用于辅助排查，不能作为新系统身份规则。

### 14.2 必须覆盖的场景

- 正常 Fake IP A、预期 AAAA 抑制、真实 DNS、缓存命中、NXDOMAIN、策略阻断、上游超时。
- 映射命中、映射丢失后 SNI／Host 恢复、无可恢复域名、嗅探超时与缓冲超限。
- 多 DNS 订阅合并、上游重试、客户端取消与发送失败，验证逻辑查询量和实际上游请求量。
- 直连与各已支持代理出站，TCP 成功但代理握手失败、目标拒绝连接、传输重置、写失败。
- 出站配置端点／实际 peer／业务目标／本机 local 地址分离；HTTP 上游 407 与入站认证分离，SOCKS 协商／认证／CONNECT 拒绝分离；Shadowsocks 无确认与 VLESS 本机请求发送就绪不伪造远端接受。
- 同一连接多次错误及关闭竞争，确认一个汇总、稳定 ID、最终流量只计一次。
- 认证失败、私网目的拒绝、自连接拒绝、无路由、配置重载、Worker 重启及结束未知。
- 公网引流域名→目标重写→NAS 私网；重写至公网；重写域名解析为私网／公网／失败；仅端口重写；重写配置无效；RFC1918／ULA／CGNAT／Fake IP／特殊地址；核对重写内网集合为实际重写子集及四分类流量守恒。
- Fake IP 恢复、DNS_REWRITING 与 DESTINATION_OVERRIDE 分离；普通代理远端目标未知但业务分类为 EXTERNAL 时仍保留真实 IP 未知；非重写私网直连为 OTHER_INTERNAL，不混入重写 NAS 流量。
- 外部客户端重写至 NAS 与 LAN 客户端访问外网，确认来源与分类独立；目标重写分类不冒充 WAN 计费，LAN 直接互访不补算；规则变更前后历史快照保持不变。
- SOCKS5 PASSWORD／HTTP Basic 成功、密码错误、锁定、无可接受方法、无凭据挑战、编码畸形、验证后没建立隧道、同连接多尝试与重发去重；匿名／设备 IP／Shadowsocks 不写虚假密码成功。
- 来源公网／私网／IPv6／可信转发、GeoIP 缺失、未知国家、陌生地区成功、失败突增、冷启动与风险规则版本；检查密码、认证头／Base64／解码串和错误消息不进入新日志或前端。
- 批次重复、ACK 丢失、CK 失败、管理端重启后的进程内重试、Worker 重启后未确认内存记录丢失边界、队列限额、同 ID 内容冲突。
- 新链路故障／关闭时旧日志仍写入；四种升级组合及管理端回退；现有页面与 API 回归。
- 前端无样本、未开启、旧 Worker、不完整数据、大整数与时区展示。

实施测试使用实际可控字节验证统计，不用简单复刻实现的测试证明语义正确。对前端新增功能执行相关 Jest 检查和构建；后端按模块显式开启管理端测试。端到端场景可复用 Fake IP 与 worker 测试设施，并增加新表断言。

### 14.3 性能与容量

灰度记录开启新采集前后的吞吐、建连延迟、CPU、堆内存、EventLoop 延迟、内存队列占用、CK 写入与查询耗时。双轨增加写入与存储开销，发布前为 NAS 设定可观察的容量预算，不预设无数据依据的固定性能阈值。

事件按类别、级别和重复合并控制数量；核心汇总、DNS 最终结果、恢复与建连结果、新凭据认证尝试不随意抽样，否则成功率与分母失真。认证挑战／畸形请求突增须有独立容量边界和覆盖提示。若未来引入抽样，必须记录采样规则与覆盖信息并单独定义统计口径。

## 15. 代码定位与实施交付

| 模块 | 既有入口（保留） | 新增方向 |
| --- | --- | --- |
| common | `AccessLog`、`AuthLog`、`ProxyContext`、`ProxyTimeContext`、管理 WSS DTO | 独立访问／凭据认证 V1 DTO、重写与分类字段、认证观测配置、上下文契约、协议能力／批次／ACK |
| proxy-worker | `AccessLogUtil`、`WebSocketLogPublisher`、协议 handler、`RouterService`、connector、`RelayHandler`、管理 WSS | 新 collector、状态机、有界内存队列、附加埋点、归属分类、SOCKS5／HTTP 认证采集与运行指标 |
| control-manager | `LogService`、`LogController`、`AccessLogStoreFactory`、ClickHouse 旧 writer/initializer、管理 WSS | 新初始化、独立 ingest/query、幂等事实、目标重写流量统计、GeoIP／认证异常分析、观测配置与采集状态 API |
| frontend | `App.tsx`、`ProxyConfig.tsx`、`LogAudit`、`TrafficOverview`、`InboundManagement`、`api.ts` | 新导航、重写内网／外网流量分布、认证审计／观测配置、types/client 方法；后续分批迁移 |
| docs/部署 | 现有 Fake IP、worker、Docker 文档与环境表 | 新模型部署／恢复／容量说明与迁移报告，清理另列任务 |

阶段 A/B 的交付包括模型/API 契约、测试与构建结果、前端截图、双轨兼容验证、运行开销与数据差异报告、关闭新链路的回退步骤。现有代码可增加调用和兼容分支，但任何删除或改写旧日志行为的变更必须留到用户确认的后续阶段。

## 16. 相关文档

- [代码索引](../code-index.md)
- [Fake IP DNS 与透明代理联动需求](fake-ip-requirements.md)
- [Fake IP 实施任务](fake-ip-implementation-tasks.md)
- [Fake IP 部署与验证](fake-ip-deployment.md)
- [Fake IP 透明代理 E2E](../testing/fake-ip-transparent-e2e.md)
- [生产多 Worker 升级操作手册](production-multi-worker-upgrade-runbook.md)
- [DIRECT 出站目标地址安全校验设计](direct-outbound-address-safety-design.md)
