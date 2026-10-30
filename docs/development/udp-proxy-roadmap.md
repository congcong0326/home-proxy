# UDP 代理整体规划

## 1. 背景

目前所有入站、出站都只转发 TCP。唯一的 UDP 业务是 DNS 入站。透明代理部署脚本 `backend/tproxy_netty.sh` 用两条规则兜底：

- `REJECT_UDP_443`：拒绝 LAN 的 UDP/443（QUIC），客户端立即回落 TCP。
- `REJECT_FAKE_IP_UDP`：拒绝发往 Fake IP 地址池的 UDP，避免假地址被路由到公网后静默超时。

其余 UDP 由内核 NAT 直接转发，不经过 worker。由此留下两个缺口：

1. 开启 Fake IP 后，所有基于域名的 UDP 都不可用，因为它们的目标都在地址池内，被统一拒绝。
2. 需要走代理的 UDP（如境外语音、游戏）无法按域名分流，只能绕过代理直连。

## 2. 目标与非目标

目标：

- 透明代理支持 UDP，按域名分流，域名来自 Fake IP 反查。
- 直连出站和 VLESS Reality 出站支持 UDP。
- 路由命中不支持 UDP 的出站时，明确拒绝，不静默绕过代理直连。

当前规划内不做：

- SOCKS5 UDP ASSOCIATE 入站、Shadowsocks UDP 入站。
- Shadowsocks、SOCKS5、HTTP 等出站的 UDP 支持。
- QUIC Initial 解密嗅探 SNI。QUIC 被拒绝后浏览器会回落 TCP，由 TCP 侧 SNI 分流处理，收益低。
- Fake IP 关闭时的 DNS 反查映射。
- Mux.Cool / XUDP、FullCone NAT 行为。
- IPv6 透明代理。Fake IP 本身只发放 IPv4 地址。

## 3. 关键设计决策

以下结论在设计讨论中确定，各期实现时遵循。

| 决策 | 结论与理由 |
|---|---|
| 链路划分 | UDP 新建独立的会话与转发链路，不复用 `ProxyTunnelConnectorHandler`、`RelayHandler`、`OutboundConnector`。TCP 链路以"每会话一个入站 channel"为前提，UDP 透明代理所有会话共用一个监听 channel，前提不成立。 |
| 共用组件 | 路由匹配、`FindUser`、`FakeIpRegistry`、`DnsQueryService`、Reality 握手与 TLS 编解码、配置模型、`ProxyContext` 生命周期。其中路由匹配需要先从 `RouterService` handler 中提取出不依赖 channel 的纯方法。 |
| 回包源地址 | 内核不会替 UDP 伪装回包源地址。为每个原始目标 T 建立一个 `IP_TRANSPARENT` 回包 socket，bind 到 T，用 `sendto(C)` 回给客户端。该 socket 不 connect，同一个 T 的多个会话共享。 |
| 会话模型 | 会话表 key 为 `(客户端 C, 原始目标 T)`。会话与出站 channel 一一对应，回包 socket 与会话一对多，用引用计数管理。 |
| 生命周期 | 出站 channel 是会话生死的唯一依据。空闲超时由 `IdleStateHandler` 判定，容量淘汰通过关闭 channel 触发，清理逻辑只在 channel 关闭回调中执行一次。不使用带 TTL 的缓存组件。 |
| 线程模型 | 一个监听 channel 对应一个 event loop，出站 channel 和回包 socket 注册到同一个 event loop，会话表不需要加锁。家庭场景的包量够用。需要扩展时再考虑 `SO_REUSEPORT` 多监听 socket。 |
| UDP 出站接口 | 新建 `UdpOutboundConnector.open(session, eventLoop)`，返回的 future 在"连接 + 协议握手"都完成后才成功，channel 对外只收发裸 payload，协议封装由各 connector 在自己的 pipeline 内完成。 |
| 不支持时的语义 | 路由命中的出站不支持 UDP 转发时，直接拒绝（丢弃）。`DIRECT` 策略默认支持，不需要配置。 |
| 代理出站目标地址 | 代理出站直接把 Fake IP 反查到的域名发给远端解析，避免本地 DNS 污染。只有 `DIRECT` 在本地解析真实 IP。 |

## 4. 分期

### 一期：UDP 透明代理转发内核

设计文档：[一期 UDP 透明代理转发内核设计](udp-transparent-relay-phase1-design.md)。

范围：

- `TP_PROXY` 入站在同一端口启动 UDP 监听，接收 TPROXY 引流的 UDP。
- 会话表、回包 socket 池、空闲回收、容量上限、首包缓冲。
- Fake IP 反查域名后走路由匹配；`DIRECT` 策略转发，其他策略拒绝。
- `DIRECT` UDP 出站：本地异步解析真实 IP 后转发。
- 从 `RouterService` 提取纯路由方法，TCP 行为不变。
- 脚本增加 UDP 引流开关，只引流发往 Fake IP 地址池的 UDP，可放开 UDP/443 来测试 HTTP/3。
- 内部计数器，用于验证资源回收。不接入日志模型。

不加入任何管理台配置项，不修改 control-manager 和前端。

完成标准：

- 直连路由的 HTTP/3 站点能以 `h3` 访问。
- 走代理路由的站点 QUIC 被丢弃后正常回落 TCP。
- 空闲后会话数与回包 socket 数归零，fd 不持续增长。
- 关闭脚本开关后恢复为现有行为。

### 二期：Reality UDP 出站与"UDP转发"配置

范围：

- 路由新增配置项"UDP转发"，只有出站类型为 `VLESS_REALITY` 的路由可以开启，默认关闭。需要同步 common DTO、control-manager 实体与 Flyway 迁移、聚合配置 hash、worker 运行时配置、前端路由表单和类型。
- 开启"UDP转发"的 Reality 路由，UDP 改为转发；未开启的 Reality 路由和其他出站类型继续拒绝。`DIRECT` 不受该配置项影响。
- `VlessRealityUdpOutboundConnector`：复用现有 Reality 握手 handler，`VlessCodec` 增加命令字段（TCP `0x01`、UDP `0x02`），握手完成后加入 2 字节长度前缀的报文编解码 handler。每个 UDP 会话占用一条 Reality 连接。
- 目标地址用域名类型发送。

前置验证已于 2026-10-29 完成：核对 Xray-core v26.3.27 源码并运行真实 VLESS TLS 1.3 + UDP echo。请求 `flow=xtls-rprx-vision` + UDP command 会被拒绝；请求 flow 为空时，即使服务端账号配置 Vision，普通 UDP command 仍可完整往返。二期可将 UDP 请求 flow 留空，使用原生 UDP command + 两字节长度封帧，无需仅因账号 Vision 而引入 XUDP。实验未改动现有 Reality connector；二期仍需验证实际部署版本及 REALITY 传输。复现实验、源码位置和边界见 [一期实施记录](../testing/udp-transparent-relay-phase1.md#xray-二期前置验证)。

### 三期：日志与观测

范围：

- 新增 UDP 会话事实：会话结束时汇总一条，包含客户端、原始目标、域名、路由、出站、上下行字节数与包数、开始与结束时间、结束原因（空闲、淘汰、出错、拒绝）。
- worker 经现有 `TelemetryPublisher` 上报，control-manager 写入 ClickHouse，新版审计页面展示。按 [网关观测日志模型设计](gateway-observability-log-model-design.md) 的双轨原则扩展，不改动现有 TCP 事实。
- 一期的内部计数器可以并入 worker 心跳指标。

## 5. 后续候选

按价值排序，有需求时再立项：

1. 回 ICMP 端口不可达代替静默丢弃，让被拒绝的 UDP 应用立即失败而不是等超时。需要 raw socket 权限。
2. Fake IP 关闭时的 DNS 反查映射，`(客户端, 真实 IP) → 域名`，TCP 非 HTTP/TLS 流量同样可以受益。
3. Shadowsocks AEAD UDP 出站，以及入站的 UDP 支持（手机在外连回家时可用）。
4. XUDP 与 FullCone，改善游戏和 P2P 的 NAT 类型。
5. 本机发往 Fake IP 的 UDP 引流，对应 TCP 侧的 `ENABLE_LOCAL_FAKE_IP`。
6. `SO_REUSEPORT` 多监听 socket，或按流建立 connected socket 交由内核分拣，提升多核扩展性。
