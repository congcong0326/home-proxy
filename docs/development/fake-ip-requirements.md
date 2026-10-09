# Fake IP DNS 与透明代理联动需求

## 文档状态

- 状态：需求已讨论，待实现
- 更新日期：2026-09-27
- 适用范围：`DNS_SERVER` 入站、`TP_PROXY` 入站、`DIRECT` 出站及配置发布链路
- 参考实现：本地 `.reference/` 中的 Xray-core `v26.3.27`

## 1. 背景与目标

当前透明代理从原始目标 IP 接收 TCP 连接，并尝试从 HTTP Host 或 TLS ClientHello SNI 恢复域名。无 Host、无 SNI、ECH 或非 HTTP/TLS 流量无法稳定命中域名规则。主路由把 worker 的 DNS 入站提供给局域网客户端后，可以在 DNS 查询时返回 Fake IP，并在透明连接到来时反查原始域名。

本需求确定四项能力：

| 项目 | 决策 |
| --- | --- |
| Fake IP 映射 | DNS 应答 TTL 固定为 1 秒；worker 内以容量受限的 LRU 保存双向映射，不为映射设置时间过期。映射未命中时继续嗅探。 |
| 真实目标解析 | 提供 worker 内部的异步 DNS 查询服务，区分 `CLIENT_FAKE` 和 `OUTBOUND_REAL`。两种模式共用 DNS 入站的身份识别、域名规则选择、阻断、重写和上游选择；`OUTBOUND_REAL` 跳过 Fake IP 发放，为本机需要连接的域名端点提供地址。 |
| 真实 DNS 结果缓存 | 单独缓存真实 A/AAAA 答案，按上游 TTL 过期，正向缓存最多 300 秒；同解析视图的并发查询合并。 |
| 配置联动 | 每个 worker 至多有一个启用的 DNS 入站和一个启用的透明代理入站。只在 DNS 入站配置 Fake IP 开关；透明代理从同一 worker 的运行配置中定位 DNS 入站。 |

首期目标是同一 worker 上的 IPv4 DNS A 查询与 TCP 透明代理。Fake IP 映射不持久化；worker 重启后通过现有 HTTP Host/TLS SNI 嗅探尽可能恢复域名。

## 2. 现有代码边界

- DNS 入站从 `UdpDnsQueryHandler` 生成 `ProxyTunnelRequest`，经过 `RouterService` 和 DNS 出站策略回复客户端。
- `DnsForwardProtocolStrategy`、`DnsOverTlsProtocolStrategy` 及 `AbstractDnsUpstreamConnector` 已具备 UDP/DoT 上游连接、报文编解码、查询 ID 关联及上游切换能力。
- 现有 DoT 上游已配置为 IP 地址，连接上游时不依赖系统 DNS；共享 DNS 查询服务可复用这条 DoT 上游路径。
- `AbstractDnsProxyProtocolStrategy` 中的 Guava 缓存只保存约 10 秒的待响应查询，收到响应即移除；它不是 DNS 答案缓存。
- `TransparentServerInitializer` 已保存原始目标 IP/端口；`ProtocolDetectHandler` 负责 HTTP Host/TLS SNI 嗅探；`TransparentServerHandler` 构建隧道请求。
- `ProxyTunnelRequest.getFinalTargetHost()` 当前优先使用 `targetIp`。当该字段保存 Fake IP 时，`DIRECT` 会错误连接假地址，需要拆开“原始目标”和“实际连接目标”。
- `DirectOutboundConnector` 是各 TCP 入站共用的直连实现，当前可能通过系统默认解析器解析域名。主路由的系统 DNS 若指向 worker 的 Fake IP DNS，SOCKS5 等显式代理的域名直连也可能再次获得假地址。

## 3. 配置模型与生效条件

### 3.1 唯一开关与运行时定位

`DNS_SERVER` 入站新增 `fakeIpEnabled: boolean`，默认 `false`。`TP_PROXY` 不新增绑定 ID 或 Fake IP 开关。管理台只在 DNS 入站显示此开关。

首期配置约束是每个 worker 至多有一个启用的 `DNS_SERVER` 入站和一个启用的 `TP_PROXY` 入站。当前代码只校验同一 worker 的监听 IP/端口不重复，尚未校验协议实例数量；控制端保存配置、发布聚合配置和 worker 应用配置时均需校验该约束。发现多个启用的 DNS 入站时返回明确配置错误，不能直接用 `findAny()` 随机挑选。

透明代理从同一份 worker 运行配置快照中定位唯一启用的 DNS 入站。下列判断只控制是否继续发放新的 Fake IP：

```java
boolean isFakeIpActive(ConfigSnapshot snapshot) {
    List<DnsInbound> activeDns = snapshot.enabledInbounds(ProtocolType.DNS_SERVER);
    if (activeDns.size() > 1) throw new InvalidConfigException("worker 存在多个启用的 DNS 入站");
    return activeDns.size() == 1 && activeDns.get(0).isFakeIpEnabled();
}
```

- 没有启用的 DNS 入站，或其 Fake IP 开关关闭时，不再发放新 Fake IP。透明代理仍需识别 Fake IP 地址池并处理客户端持有的旧地址：有保留的映射就反查；没有映射就尝试嗅探，仍无法恢复域名则关闭连接。不能因为关闭开关就把池内地址当作普通公网目标。
- DNS 入站开关开启时，DNS 入站与同一 worker 的透明代理使用同一个进程级 `FakeIpRegistry`，不需要跨入站引用字段。
- 开启 Fake IP 前应确认同一 worker 已有启用的透明代理；否则客户端收到假地址后没有对应的透明 TCP 接收端。控制端应阻止发布这一无效组合。
- 开启 Fake IP 前需校验 DNS 入站的兜底路由提供有效的真实 DNS 转发，且所有 UDP DNS/DoT 上游端点均配置为 IP 字面量，不指回本机 Fake IP DNS。兜底路由只处理未命中规则的域名；`OUTBOUND_REAL` 必须先按域名选择路由，不能固定使用兜底上游。
- 首期透明代理使用域名规则分流；启用 Fake IP 的透明代理路由配置中不使用依赖目标 IP 地理位置的 GEO 条件。由配置规避该条件，不为 GEO 路由新增异步解析流程。
- 开关与唯一入站的判断要来自同一份配置快照。普通配置刷新不重建 `FakeIpRegistry`；关闭开关只停止新地址发放，已有映射可继续供旧连接反查。worker 重启后映射丢失，但地址池判断仍必须生效。

首期 Fake IP 地址池与容量是 worker 级参数：默认 IPv4 地址池 `198.18.0.0/15`，LRU 容量建议 32768 条。配置模型仅新增上述一个开关；若以后允许同一 worker 上多个 DNS 或透明代理入站，再设计显式绑定或池隔离。

启动和配置发布时需校验 LRU 容量不大于可分配地址数，地址池不覆盖 worker 的局域网、VPN、容器网段或 DNS 上游地址，系统路由和透明代理规则能够接住整个地址池。变更地址池时不能立即把旧池当成真实目标地址；首期若地址池固定，则禁止运行时变更。

控制端需同步更新数据库迁移、实体、请求对象、聚合配置 DTO 与 hash；worker 运行时配置及前端类型同步更新。

### 3.2 部署条件

自动定位 DNS 入站不改变系统网络规则。客户端的 DNS 查询必须进入启用 Fake IP 的 DNS 监听端口，后续发往 Fake IP 的 TCP 流量必须进入同一 worker 的透明代理。`backend/tproxy_netty.sh` 的 UDP/53 劫持默认关闭，部署时需启用或通过 DHCP 等方式指定 worker DNS。

DNS 入站当前按查询报文的源 IP 识别设备。若主路由把多个客户端的 DNS 请求源地址转换成路由器 IP，而透明代理看到的是客户端 IP，同一域名的客户端查询与 `OUTBOUND_REAL` 可能命中不同的设备 DNS 规则。首期要求这两个入口看到相同的客户端源 IP，或仅使用不依赖设备身份的 DNS 路由；不满足时需要后续增加明确的身份关联机制。

DNS 入站开启 Fake IP、但网络中的透明 TCP 流量未进入该 worker 的透明代理时，客户端拿到假地址后无法正常连接。管理台或部署文档应明确提示这项前提。

worker 应先验证完整配置并启动透明代理监听，再开放 DNS Fake IP 应答；配置刷新失败时继续使用上一份可用快照，不能出现 DNS 已发放 Fake IP 而透明代理未就绪的中间状态。一次请求的选路、缓存键与连接过程使用同一配置版本。

## 4. Fake IP 应答与映射

### 4.1 客户端 DNS 查询

```text
客户端 A 查询 -> DNS_SERVER 入站 -> 共享 DNS 选路
  -> 阻断/显式重写：沿用原有结果
  -> 普通转发路由且 CLIENT_FAKE：查询或分配 Fake IP
  -> 写入双向映射 -> 返回 A 记录，TTL=1 秒
```

- Fake IP 只表示域名身份，不保存或代表当前真实 A 记录。发放 Fake IP 时不必先向真实 DNS 上游查询。
- 同一规范化域名在映射尚在 LRU 中时重复查询，返回同一个 Fake IP。
- 域名规范化至少包括小写化、去除末尾点和 IDN 转 ASCII；用于分配、反查、缓存键及日志的形式保持一致。
- Fake IP 开关关闭时沿用现有 DNS 路由、重写和上游转发行为。开启时先执行同一套 DNS 路由决策：命中 `BLOCK` 仍阻断，命中 `DNS_REWRITING` 仍返回配置答案；其余可发放 Fake IP 的 A 查询才在应答阶段返回 Fake IP。`OUTBOUND_REAL` 对这些请求沿选中的转发路由查询真实上游，不发放 Fake IP。具体规则优先级应在实现前用用例固定。
- 首期对会发放 Fake IPv4 的域名，客户端 AAAA 查询返回 `NOERROR` 空答案，避免真实 IPv6 地址绕开 IPv4 透明代理；显式阻断和重写仍按既有规则处理。IPv6 专用域名可能因此不可达，需要在部署说明中提示。其他非 A 类型保留现有处理路径；IPv6 Fake IP 及透明代理留待后续阶段。
- Fake IP 响应保留客户端查询 ID 和 Question，设置与现有 DNS 入站一致的响应标志及错误码；只在应答 A 记录上使用 1 秒 TTL。不能把出站上游的 DNS 响应对象直接复用给多个客户端。

### 4.2 内存映射

`FakeIpRegistry` 维护 `domain -> fakeIp` 与 `fakeIp -> domain` 两个索引，由同一个 LRU 条目管理，分配和淘汰必须保持原子一致。容量达到上限时淘汰最久未使用的映射；不设置 `expireAfterWrite` 或 `expireAfterAccess`。域名查询和 Fake IP 反查都算一次使用。地址分配不能占用当前仍有映射的 IP；在有未发放过的池内地址时，优先使用新地址，再复用已淘汰地址，以降低短时间误归属概率。

DNS 应答的 1 秒 TTL 仅控制客户端 DNS 缓存，不是内部映射寿命，也不保证客户端恰好在 1 秒后停止使用该地址。容量淘汰、worker 重启和客户端自行延长缓存都可能造成反查未命中；旧假地址在被重新分配后还可能产生错误归属。实现需要记录这类风险和指标，不能把 1 秒 TTL 当作强一致保证。

配置的普通刷新不应清空映射。worker 重启后允许映射丢失；旧 Fake IP 连接按下一节的嗅探回退处理。

## 5. 透明代理的域名恢复与出站目标

### 5.1 域名恢复顺序

```text
TP_PROXY 的原始目标 IP/端口
  -> 目标 IP 在 Fake IP 地址池内？
     -> 是且命中映射：立即取得域名并开始选路
     -> 是但映射未命中：限时尝试 HTTP Host/TLS SNI 嗅探
     -> 否：沿用现有透明代理流程
  -> 得到逻辑目标后执行现有用户与路由匹配
```

- 映射命中时，以 DNS 查询时的域名作为域名路由目标，直接建立后续出站，不等待客户端首包。当前 `ProtocolDetectHandler` 会等待首包；实现时须在其前面反查并构造不含首包的隧道请求，后续客户端数据仍由 `RequestAppendHandler` 在建连期间暂存，不能丢失或重复转发。这样才能支持服务端先发送数据的 TCP 协议。HTTP Host/SNI 如能在不延迟建连的情况下取得，可用于审计；两者不同时不覆盖映射结果，并记录指标。
- 映射未命中时必须继续嗅探，覆盖 LRU 淘汰和 worker 重启场景。嗅探必须设置最大等待时间和缓冲上限；嗅探得到域名后可以继续域名路由和真实 DNS 解析。
- 对普通真实 IP 流量，嗅探失败时保留现有按原始 IP 路由与连接行为。
- 对属于 Fake IP 池的目标，即使映射未命中、嗅探也失败，也不能把该地址交给 `DIRECT` 或上游代理作为业务目标；应结束连接并记录原因。
- 原始目标端口始终保留，除非明确命中目标重写配置。
- Fake IP 反查与上述嗅探优先级只在 `TP_PROXY` 入站执行。SOCKS5、HTTP CONNECT、Shadowsocks 等显式代理不因目标 IP 落入 Fake IP 池而自动做映射反查，也不改变其客户端指定的目标。

请求模型必须区分：原始目标 IP/端口、逻辑目标域名及其来源、路由结果、真实解析 IP、最终连接 IP/端口。`targetIp` 不得同时表示原始 Fake IP 和最终直连 IP。访问日志保留原始假地址与实际连接地址。

### 5.2 路由后的解析规则

| 路由结果 | 业务目标处理 |
| --- | --- |
| `DIRECT`，原始目标为真实 IP | 继续连接该原始 IP，不因嗅探到域名而自动重复解析。 |
| `DIRECT`，原始目标为 Fake IP 且已恢复域名 | 使用 `OUTBOUND_REAL` 按 DNS 入站的域名规则异步解析，经过最终地址校验后建连；DNS 路由阻断时连接失败。 |
| `DIRECT`，SOCKS5 等显式代理提交域名 | 仍按客户端提交的域名直连；底层使用 `OUTBOUND_REAL` 经过同一套 DNS 域名规则取得连接 IP，保留现有公网地址校验，不参与 Fake IP 反查。 |
| `OUTBOUND_PROXY` | 把恢复的域名和原始端口放入上游代理协议，由上游解析业务目标。worker 连接上游代理端点；端点若配置为域名，也必须经 `OUTBOUND_REAL` 取得非 Fake IP 地址。 |
| `DESTINATION_OVERRIDE` | 使用管理员配置的端点，不解析原始业务域名；配置端点本身若是域名，也必须经 `OUTBOUND_REAL` 解析。 |
| `BLOCK` | 不解析、不连接。 |

首期按域名分流，透明代理路由不配置 GEO 条件。若未来恢复 GEO 分流，不能以 Fake IP 判断地理位置，需要另行设计路由前真实解析及多地址选择规则。

## 6. 进程内 DNS 查询接口与异步连接

### 6.1 接口语义

提供 worker 进程内 DNS 查询服务，不新增供客户端调用的 HTTP API。服务处于 DNS 入站传输层和上游连接器之间，统一负责身份识别、DNS 路由选择、应答策略、上游查询及答案缓存。接口可采用以下等价形式：

```java
Future<DnsResult> query(DnsQuestion question, QueryMode mode, DnsQueryContext context);

enum QueryMode {
    CLIENT_FAKE,
    OUTBOUND_REAL
}
```

- `DnsQueryContext` 至少包含请求来源、原始客户端 IP 和当前配置快照；内部请求以原始客户端 IP 按 DNS 入站现有设备绑定规则定位 DNS 用户，再按该用户的 DNS 路由规则选路。找不到设备绑定时先使用 DNS 入站的匿名用户及其路由，未匹配时使用 DNS 入站的兜底路由。SOCKS5 等入站的认证用户与 DNS 入站设备用户不是同一个概念；不能直接把前者的代理路由当成 DNS 路由。
- 对透明代理，`DnsQueryContext` 的客户端 IP 必须取原始入站对端，不能取 worker 的上游连接地址；若 DNS 查询经过 NAT 导致源 IP 不一致，应按部署条件处理。对显式代理，使用该入站实际看到的客户端 IP；不能保证与客户端自行发出的 DNS 查询具有相同身份。
- 两种模式先运行同一个 DNS 路由选择器，按现有规则顺序匹配域名，并保留 `BLOCK`、`DNS_REWRITING`、UDP DNS/DoT 上游选择及兜底行为。`CLIENT_FAKE` 只在选路后、符合条件的客户端 A 应答阶段分配 Fake IP；它不会绕过阻断和显式重写。
- `OUTBOUND_REAL` 跳过 Fake IP 应答分支。选中的 DNS 路由若为转发，则查询该路由配置的真实上游，返回地址和 TTL；若为 `DNS_REWRITING`，则返回管理员配置的地址；若为 `BLOCK`，则返回明确的阻断结果，不尝试其他上游。这里的“真实”只表示不会由 Fake IP 池分配地址，不表示绕过 DNS 重写策略。需要本地解析域名的 `DIRECT` 出站、上游代理端点及目标重写端点都复用此模式；显式代理的业务目标交给上游时不在本地解析，也不启用透明代理的 Fake IP 反查逻辑。
- 目前 `RoutesQueryService` 在 DNS 用户未绑定路由时可能从其他用户的路由中 `findAny()`；这会让内部调用拿到不可预测的解析策略。抽取共享 DNS 路由选择器时，需要统一改为明确的匿名用户/兜底行为，并让 UDP DNS 入站与内部 API 使用同一实现。
- DNS 上游自身使用配置中的 IP 字面量和端口，不能再调用同一个 `OUTBOUND_REAL` 解析上游地址，否则形成递归依赖。首期沿用现有 UDP DNS/DoT 连接配置，无需为 Fake IP 增加 DoT 专用字段。
- `OUTBOUND_REAL` 的结果若包含 Fake IP 地址池中的地址，不可用来连接；解析失败或被 DNS 规则阻断时不能退回系统默认 DNS 或 Fake IP 地址。显式重写到内网地址仍受现有直连目的地址校验约束，不能凭 DNS 重写绕过该校验。
- 若 worker 没有启用的 DNS 入站，普通 SOCKS5 等入站的 `DIRECT` 域名请求暂时保留当前系统解析行为，以兼容现有部署。只要存在唯一启用的 DNS 入站，`DIRECT` 域名请求就执行它的完整 DNS 路由策略；选中的路由无效、阻断或解析失败时应显式失败，不静默退回系统 DNS。Fake IP 模式还要求存在可供一般域名解析的有效真实 DNS 转发路径，不允许靠系统 DNS 兼容回退。
- DNS 入站收到报文后调用同一个服务并编码结果回客户端；直连连接器调用该服务取得地址。两者共用选路、重写、阻断、上游查询及缓存，仅在 Fake IP 应答和结果消费上不同。

现有 `RouterService` 按请求同步执行路由匹配，`UdpDnsQueryHandler` 构造隧道请求，上游连接器和 `AbstractDnsProxyProtocolStrategy` 处理转发。实现时应将 DNS 选路抽成不依赖入站 channel 的共享决策组件，把上游查询抽成以 Future 返回结果的组件；UDP 入站和内部调用都从共享 DNS 服务入口进入。TCP 侧仍按域名同步选路，选中 `DIRECT` 后再异步查询真实地址。`AbstractDnsProxyProtocolStrategy` 当前将响应直接写回 DNS 入站 channel，不能原样给 `DIRECT` 调用。

共享上游通道的 pending 项须记录每次查询各自的完成回调、问题和超时任务，响应必须按上游查询 ID 与 Question 校验后分发。当前 10 秒 Guava pending 淘汰只会丢弃关联记录，不会主动完成内部 Future；新服务需明确查询超时、取消、入站关闭及上游通道关闭时的失败回调，并在结束时移除 pending 项。多上游故障切换也须覆盖查询超时，而非只处理建连失败。

上游返回 `TC=1` 的截断响应时，内部 `OUTBOUND_REAL` 不得从不完整的答案中取 IP 或写入缓存；应通过可用的 TCP/DoT 路径重试，若没有此路径则返回明确解析失败。客户端 DNS 入站可以按协议返回截断标志，但首期只监听 UDP，客户端通过 TCP 重试可能失败，属于部署限制。

### 6.2 非阻塞要求

```text
路由选中 DIRECT
  -> 按 DNS 入站策略选择路由
  -> 命中阻断则失败，命中重写则取得配置地址
  -> 命中转发则查询对应解析视图的真实 DNS 缓存
  -> 缓存未命中时异步请求选定的真实上游
  -> 收到并校验真实 IP
  -> connect(已解析的 InetSocketAddress)
  -> 连接成功后才建立 relay
```

DNS 查询和 TCP 连接构成同一次异步出站操作。调用方不得在 Netty event loop 中使用 `Future.get()`、`sync()`、`await()` 或阻塞式 `InetAddress.getByName()`。DNS 返回不等于连接成功；只有目标 TCP 连接成功后才完成现有 relay promise。DNS 失败、超时或入站关闭时释放待发首包和相关资源，不能留下悬挂的 pending 查询。

## 7. 真实 DNS 答案缓存

真实答案缓存与 Fake IP LRU 完全独立。`CLIENT_FAKE` 的 1 秒 TTL 不控制真实答案缓存；当前 DNS 待响应查询缓存也不承担此职责。

| 结果 | 首期建议缓存规则 |
| --- | --- |
| 有效 A/AAAA | 使用上游答案 TTL 与 300 秒上限中的较小值；TTL 为 0 不缓存。 |
| CNAME 链后的 A/AAAA | 缓存寿命不超过链上相关记录的最短 TTL，并受 300 秒上限约束。 |
| NXDOMAIN / NODATA | 有 SOA 负缓存信息时按其负 TTL 缓存，最多 30 秒；否则不缓存。 |
| 超时 / SERVFAIL / 截断或无效报文 | 不缓存。 |

- 缓存键至少包含规范化域名、A/AAAA 类型、选中路由或上游解析视图，以及会影响选路的 DNS 用户身份或配置版本。不同用户策略、上游组或配置版本可能返回不同答案，不能混用；配置变更时失效旧视图的条目。
- `DNS_REWRITING` 的答案直接读取当前配置，不使用上游 TTL 缓存；`BLOCK` 也不进入真实答案缓存。
- 缓存值保存真实 IP 列表、到期时间和必要的应答状态。读取不延长到期时间；过期后重新查询。缓存命中时直接使用已解析 IP 建连，不能再通过系统 resolver 查询一次。
- 可继续使用项目已有 Guava：`maximumSize(10_000)` 作为初始容量，在值内保存按上游 TTL 计算的独立到期时间，并用全局最长保留时间做兜底。容量和 300 秒上限以后可按命中率与上游查询量调整。
- 维护独立的 in-flight 表，合并相同缓存键的并发未命中查询；成功或失败后都移除对应 in-flight 条目。
- 返回 Fake IP、受损或不可信的真实解析结果不得进入缓存。现有受保护入站的公网地址校验仍在最终连接前执行，且必须校验与实际连接的是同一批地址。

缓存过期只影响下一次新建连接，已建立的 TCP 连接不因 DNS 缓存更新而中断。

## 8. 模块改动范围

| 模块 | 主要改动 |
| --- | --- |
| `backend/common` | 入站配置 DTO 增加 `fakeIpEnabled` 字段及必要的配置校验模型。 |
| `backend/control-manager` | Flyway 迁移、入站实体与创建/更新校验、DTO 映射、聚合配置及 hash 计算。 |
| `backend/proxy-worker` | `FakeIpRegistry`、共享异步 `DnsQueryService`、真实答案缓存、DNS 入站 Fake IP 应答、透明入站反查、直连异步解析及请求目标语义拆分。 |
| `frontend` | DNS Fake IP 开关及同 worker 透明代理状态提示，同步 TypeScript 类型。 |
| `docs` 与部署脚本 | 说明 DNS/TPROXY 同 worker 与网络引流条件；如需修改脚本默认值，单独评估部署兼容性。 |

至少暴露 Fake IP 发放、LRU 淘汰和反查未命中次数，真实 DNS 的分路由查询/超时/截断/缓存命中次数，以及因地址池结果或目的地址校验而拒绝连接的次数。访问日志应能同时定位原始 Fake IP、恢复的域名及来源、选中的 DNS 路由、最终连接 IP 和配置版本，以便排查错误分流。

## 9. 验收条件

1. DNS 入站 Fake IP 关闭时，现有 DNS 转发与普通真实 IP 的透明代理行为不变；若客户端仍持有旧 Fake IP，透明代理按地址池防护规则处理。
2. 开启 Fake IP 后，符合条件的 A 查询返回池内地址且 TTL 为 1 秒；同一域名在映射未淘汰时取得相同地址，发放过程不查询真实上游。
3. 同一 worker 没有启用的 DNS 入站，或唯一 DNS 入站的 Fake IP 开关关闭时，不发放新 Fake IP；透明代理仍识别旧 Fake IP 地址池。
4. 同一 worker 出现多个启用的 DNS 入站或透明代理入站时，配置校验明确报错；不能通过 `findAny()` 随机选择。启用 Fake IP 时必须有同 worker 的透明代理，DNS 与透明代理读写同一份映射。
5. 透明代理通过 Fake IP 恢复域名，即使首包没有 Host/SNI 也能命中域名路由；映射未命中时继续嗅探。
6. 映射未命中且限时嗅探失败时，不会把 Fake IP 交给 `DIRECT` 或上游代理；关闭开关、配置刷新和 worker 重启后，旧 Fake IP 也不会被误当作真实地址。普通真实 IP 流量保持原有行为。
7. `DIRECT` 对 Fake IP 恢复出的域名异步请求 `OUTBOUND_REAL`，按唯一 DNS 入站的设备身份和域名规则选择转发、重写或阻断；转发时从选中的真实上游取得地址并连接。上游代理和目标重写的域名端点也使用该真实解析路径，DNS 上游则使用引导 IP。缺少可用真实转发路径时不能启用 Fake IP；系统 DNS 指向本机 Fake IP 服务时也不会出现解析或连接循环。
8. 真实答案缓存遵守上游 TTL 与 300 秒上限；命中不发上游查询，并发相同未命中合并；DNS 缓存过期不影响已有 TCP 连接。
9. SOCKS5、HTTP CONNECT、Shadowsocks 等显式代理不会触发 Fake IP 映射反查；其域名 `DIRECT` 使用 DNS 入站的同一套分流、阻断和重写规则，并保留原有地址校验。`OUTBOUND_PROXY` 传递业务域名给上游，不在本地查询业务目标；`BLOCK`、目标重写和现有 DNS 重写无行为回归。
10. DNS 查询和直连建连不阻塞 Netty event loop；连接成功前不建立 relay，失败时释放待发首包与查询状态。
11. 普通配置刷新不清空 Fake IP 映射；worker 重启丢失映射后仍尝试 HTTP Host/TLS SNI 嗅探。
12. Fake IP 反查命中时无需等待客户端首包即可开始出站；DNS 上游超时、截断、取消或通道关闭都能完成请求并释放 pending 状态，不会使直连长期挂起。
13. 可发放 Fake IPv4 的域名收到 AAAA 查询时返回空答案；发布 Fake IP 配置时透明代理已就绪，失败回滚不会短暂发放无法接收的 Fake IP。
14. 两个域名命中不同 DNS 上游时，`OUTBOUND_REAL` 分别查询选中的上游；不同设备 DNS 策略的缓存互不串用。显式重写与阻断规则在客户端 DNS 和内部查询中一致生效。
15. 启用 Fake IP 的透明代理路由不配置 GEO 条件；DNS 上游地址为 IP 字面量，worker 的系统 DNS 指向本机 Fake IP 服务时仍可正常访问所选 UDP DNS/DoT 上游。

## 10. 待确认事项与边界

- IPv6 Fake IP 与 IPv6 透明代理何时支持。首期对适用 Fake IPv4 的域名返回空 AAAA 答案，IPv6 专用站点可能受影响。
- 客户端通过 DNS over TCP、DoH 或 DoT 绕过当前 UDP/53 入站时，Fake IP 不会生效；QUIC/UDP 透明代理也不在首期范围。
- DNS 显式重写、阻断、普通转发与 Fake IP 的完整优先级，需要在实现前按现有路由语义固定测试用例。
- 有限地址池和 LRU 淘汰可能使仍被客户端缓存的旧假地址被重新分配。1 秒 DNS TTL 只能降低概率，不能消除风险；若要强保证，需要另行设计地址复用隔离或持久租约。
- 多 worker 共享映射、跨 worker DNS/透明代理分离部署以及重启后持久恢复不属于首期范围。
- 若以后允许同一 worker 同时启用多个 DNS 或多个透明代理入站，需要恢复显式关联或其他确定性作用域模型；首期的自动定位不能继续使用。
