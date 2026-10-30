# 一期 UDP 透明代理转发内核设计

整体分期与关键决策见 [UDP 代理整体规划](udp-proxy-roadmap.md)。本文只描述一期。

## 1. 目标与范围

一期目标：把 UDP 透明代理的转发内核做扎实。出站只有 `DIRECT`，不接入日志模型，不增加管理台配置。

包含：

- `TP_PROXY` 入站在同一监听地址和端口上额外启动 UDP 监听。
- 接收 TPROXY 引流的 UDP，取得客户端地址 C 和原始目标 T。
- 会话表、回包 socket 池、首包缓冲、空闲回收、容量上限、拒绝缓存。
- Fake IP 反查域名，走路由匹配；`DIRECT` 策略转发，其他策略拒绝。
- `DIRECT` UDP 出站：域名经现有 DNS 视图异步解析为真实 IP 后转发。
- 从 `RouterService` 提取纯路由方法。
- 脚本增加 UDP 引流开关。
- 内部计数器。

不包含：代理出站的 UDP、日志与观测事实、ICMP 拒绝、IPv6、本机发往 Fake IP 的 UDP 引流、多线程扩展。

## 2. 现状与约束

- `UdpProxyServer` 已在 epoll 下设置 `IP_TRANSPARENT` 和 `IP_RECVORIGDSTADDR`。Netty 4.1.127 的 epoll native `recvmsg` 会把原始目标写入 `NativeDatagramPacket.recipientAddr`，所以 `DatagramPacket.recipient()` 就是原始目标 T。实现时要先用 netns 实测确认。
- `EpollDatagramChannelConfig` 默认使用 `FixedRecvByteBufAllocator(2048)`，超过 2048 字节的报文会被截断，而且没有任何提示。UDP 监听 channel 和出站 channel 都必须显式设置更大的接收缓冲，见第 8 节。
- `TransparentProxyServer` 继承 `TcpProxyServer`。`ProxyContext` 按入站 ID 管理一个 `AbstractProxyServer`，并保证先启动 `TP_PROXY` 再启动 DNS。
- `RouterService` 是 Netty handler，匹配逻辑是私有方法，兜底路由失败时直接关闭 `ChannelHandlerContext`，UDP 无法直接调用。
- `DestinationAddressPolicy.isProtectedInbound` 不包含 `TP_PROXY`，透明代理的 `DIRECT` 不做公网地址校验。UDP 沿用这一点，但同样拒绝把 Fake IP 地址池内的地址作为真实出站目标。
- `DirectOutboundConnector` 和 `OutboundEndpointResolver` 各有一份"先查 A、为空再查 AAAA"的 `queryAddresses`，都依赖入站 channel。

## 3. 组件

新代码放在 `proxy-worker` 的 `org.congcong.proxyworker.udp` 包下，名称为建议值。

| 组件 | 职责 |
|---|---|
| `TransparentUdpServer` | 继承 `UdpProxyServer`，绑定和 `TP_PROXY` 相同的监听地址与端口，持有一个 `UdpSessionManager`。由 `TransparentProxyServer` 组合并管理启停。 |
| `TransparentUdpInboundHandler` | 监听 channel 上的 handler，从 `DatagramPacket` 取出 C、T 和 payload，交给 `UdpSessionManager`。 |
| `UdpSessionManager` | 一个监听 channel 一个实例，只在其 event loop 线程上访问。持有会话表和回包 socket 池，负责新建会话、分发报文、容量淘汰和全量关闭。 |
| `UdpSession` | 单个流的状态：key、状态、首包缓冲、路由、域名、出站 channel、回包 socket 引用、关闭标志。 |
| `ReplySocketPool` | `Map<InetSocketAddress, ReplySocket>`。`ReplySocket` 包含 channel 和引用计数。提供 `acquire(T)` 和 `release(T)`。 |
| `UdpOutboundConnector` | UDP 出站接口：`Future<Channel> open(UdpSession session, EventLoop eventLoop)`。 |
| `DirectUdpOutboundConnector` | 一期唯一的实现。 |
| `UdpRouteSupport` | 判断路由是否支持 UDP 转发。一期只有 `DIRECT` 返回 true。 |
| `RouteSelector` | 从 `RouterService` 提取出的纯路由方法，TCP 和 UDP 共用。 |
| `UdpRelayCounters` | 内部计数器。 |

## 4. 报文处理流程

### 4.1 客户端报文

```text
监听 channel 读到 DatagramPacket(C, T, data)
  ├─ T 是 IPv6，或 C/T 缺失 → 丢弃（计数 invalid）
  ├─ 会话表 get(C, T)
  │    READY       → 出站 channel 可写则 write(data)，不可写则丢弃（计数 drop_unwritable）
  │    CONNECTING  → 放进首包缓冲；超过上限则丢弃（计数 drop_pending_full）
  │    REJECTED    → 未过期则丢弃（计数 drop_rejected）；已过期则移除，按"不存在"处理
  │    不存在 ↓
  ├─ 解析目标
  │    T 在 Fake IP 地址池内 → FakeIpRegistry.reverseLookup(T)
  │         查不到 → 插入 REJECTED 会话，丢弃（计数 drop_fake_ip_miss）
  │         查到 → 规范化域名，作为 targetHost
  │    T 不在地址池内 → targetHost = T 的 IP 字面量
  ├─ 识别用户：FindUser.find(C 的 IP, 入站配置)，为空 → 插入 REJECTED 会话，丢弃
  ├─ 路由：RouteSelector 匹配，未命中用兜底路由
  │    UdpRouteSupport 不支持 → 插入 REJECTED 会话，丢弃（计数 drop_route_unsupported）
  ├─ 容量检查：会话数达到上限 → 关闭最久未活跃的会话
  ├─ 插入 CONNECTING 会话，data 放进首包缓冲
  ├─ ReplySocketPool.acquire(T)：已有则引用计数 +1；没有则新建并 bind 到 T
  └─ DirectUdpOutboundConnector.open(session, eventLoop)
       成功，且回包 socket 已 bind → 状态改为 READY，按顺序发出首包缓冲
       任一失败（DNS、connect、回包 socket bind、建立超时）→ 关闭会话，
                再插入 failureCacheSeconds 的 REJECTED 会话
```

建立阶段失败后留下短期 `REJECTED`，客户端重传的报文直接丢弃，不会每个报文都重新 bind 回包 socket、查 DNS。会话在建立前已被淘汰或服务器已关闭时不插入。`READY` 之后的失败（出站 channel 关闭、写出失败）只关闭会话，不插入 `REJECTED`。

首包缓冲入队前按实际长度拷贝。监听 channel 按 `maxDatagramSize` 分配接收 buffer，直接入队会让一个小报文占住 64KB，`pendingMaxBytes` 就约束不了实际内存。

插入 `CONNECTING` 必须先于任何异步操作。Fake IP 反查和路由是同步的；DNS 解析、回包 socket bind、出站 channel 的 bind/connect 都是异步的。如果等异步完成后才插入会话，同一个流的后续报文会建出重复会话。

### 4.2 目标回包

```text
出站 channel 读到 DatagramPacket(来自真实目标, data)
  → handler 持有会话引用，取出 C 和回包 socket
  → 回包 socket 可写：write(new DatagramPacket(data, C))，源地址自动为 T
    不可写：丢弃（计数 drop_unwritable）
```

出站 channel 在 connect 到真实目标后，内核只会递交来自该目标的报文，行为相当于对称 NAT。

### 4.3 会话关闭

所有关闭路径（空闲超时、容量淘汰、出站出错、回包 socket bind 失败、服务器关闭）都归结到同一个清理方法 `UdpSession.close(reason)`。该方法用关闭标志保证幂等：

```text
close(reason)：
  已关闭 → 返回
  标记关闭
  会话表 remove(key, this)              ← 带条件删除，避免误删同 key 的新会话
  释放首包缓冲中所有 ByteBuf
  出站 channel 存在且打开 → close()
  已 acquire 回包 socket → ReplySocketPool.release(T)
       引用计数 -1，降到 0 → 关闭回包 channel 并从池中移除
  计数 session_closed[reason]
```

出站 channel 建立后，把它的 `closeFuture` 挂上 `session.close(...)`，这样 channel 被任何原因关闭都会触发清理。容量淘汰和服务器关闭也只关闭出站 channel（还没有 channel 的直接调用 `close`），不直接操作会话表。

`REJECTED` 会话不持有出站 channel 和回包 socket，只记录过期时间。查表命中时检查是否过期，过期就移除；容量淘汰时可以优先淘汰它们。

## 5. 路由与策略

### 5.1 路由提取

从 `RouterService` 提取 `RouteSelector`：

- 输入：`ProxyTunnelRequest`、入站配置、路由用户 ID。
- 输出：命中的 `RouteConfig`，未命中时返回入站的兜底路由；兜底路由也不存在时返回空。
- 保留现有行为：规则顺序、GEO/DOMAIN/RULE_SET 匹配、`AD_BLOCK` 不命中、规则命中时设置 `telemetryMatchedRuleId` 和 `telemetryMatchedRuleSet`。
- `RouterService` 改为调用 `RouteSelector`。telemetry hook、`ProxyContext` 填充、非公网字面量拦截、兜底路由缺失时关闭连接，这些和 channel 相关的逻辑留在 handler 内。
- 补单测覆盖上述行为，并跑一遍现有 worker 单测和 `make e2e-transparent`，确认 TCP 不受影响。

UDP 构造路由输入的方式和 `TransparentServerHandler.createRequest` 一致：协议类型用 `TP_PROXY`，Fake IP 时 `targetIp` 为空，设置 `originalTargetIp` 和 `logicalTargetSource`（`FAKE_IP` 或 `ORIGINAL_IP`）。DNS 入站配置取自 `TransparentUdpServer` 的运行时配置快照 `dnsInboundConfig`，和 TCP 侧 `DNS_INBOUND_CONFIG` 属性的来源一致。

### 5.2 策略处理

| 命中路由的策略 | 一期处理 |
|---|---|
| `DIRECT`（包括兜底直连和自定义直连路由） | 转发 |
| `BLOCK` | 拒绝 |
| `OUTBOUND_PROXY`（任意出站类型） | 拒绝 |
| `DESTINATION_OVERRIDE` | 拒绝 |
| `DNS_REWRITING` | 拒绝 |

"拒绝"就是丢弃报文，并插入 `REJECTED` 会话。一期不回 ICMP。

路由只在会话建立时决定一次。配置刷新后，已有会话继续使用原路由直到关闭；新会话使用新的运行时配置快照。

## 6. DIRECT UDP 出站

```text
open(session, eventLoop)：
  targetHost 是 IP 字面量 → 目标 = 该 IP:port
  targetHost 是域名 → DNS 视图存在则 DnsQueryService 异步查询（A 为空再查 AAAA）
                      DNS 视图不存在 → 失败
  目标 IP 在 Fake IP 地址池内 → 失败（防回环）
  在 eventLoop 上新建 EpollDatagramChannel：
    bind(0.0.0.0:0 或 [::]:0，与目标同族) → connect(目标)
    pipeline：IdleStateHandler(0, 0, idle) → DirectUdpRelayHandler(session)
  connect 成功 → future 成功
```

- 提取 `queryAddresses` 为不依赖入站 channel 的公共方法，参数是 `DnsQueryContext` 和 `EventLoop`，供 `DirectOutboundConnector`、`OutboundEndpointResolver` 和 UDP 共用。`DnsQueryContext` 的客户端 IP 用 C。
- DNS 回调可能不在 event loop 线程，结果必须通过 `eventLoop.execute` 切回后再处理。若此时会话已关闭，要丢弃结果。
- 不做公网地址校验，和 TCP 透明代理一致。
- 出站 channel 的 `IdleStateHandler` 收到 `ALL_IDLE` 事件后关闭 channel。

## 7. 回包 socket

```text
acquire(T)：
  池中已有 → refCnt++，返回
  否则 → 在同一 eventLoop 上新建 EpollDatagramChannel：
          IP_TRANSPARENT = true，SO_REUSEADDR = true，AUTO_READ = false
          bind(T)
        放入池中，refCnt = 1
        bind 失败 → 从池中移除，所有等待它的会话关闭
release(T)：
  refCnt--，降到 0 → close()，从池中移除
```

- 回包 socket 只负责发送，`AUTO_READ = false`，不读取任何数据。
- bind 是异步的，多个会话可能同时等待同一个回包 socket 完成 bind，会话要等出站 channel 和回包 socket 都就绪后才进入 `READY`。
- 只有 epoll 支持 `IP_TRANSPARENT`。epoll 不可用时，`TransparentUdpServer` 不启动，记录警告。
- 回包 socket 数量不会超过会话数，受会话上限约束，不需要单独设上限。

## 8. 参数

一期写成常量，并允许用 `proxyworker.udp.*` 系统属性覆盖，风格和现有的 `proxyworker.netty.epoll.enabled` 一致。不进入管理台配置。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `proxyworker.udp.idleTimeoutSeconds` | 60 | 会话空闲超时，任一方向有流量都会刷新 |
| `proxyworker.udp.maxSessions` | 4096 | 单个监听的会话上限，超过时淘汰最久未活跃的会话 |
| `proxyworker.udp.maxSessionsPerClient` | 1024 | 单个客户端 IP 的会话上限，超过时拒绝新会话 |
| `proxyworker.udp.pendingMaxPackets` | 16 | 单个会话首包缓冲的报文数上限 |
| `proxyworker.udp.pendingMaxBytes` | 65536 | 单个会话首包缓冲的字节数上限 |
| `proxyworker.udp.rejectCacheSeconds` | 30 | `REJECTED` 会话的保留时间 |
| `proxyworker.udp.maxDatagramSize` | 65535 | 监听 channel 和出站 channel 的 `FixedRecvByteBufAllocator` 大小，避免默认 2048 字节截断 |
| `proxyworker.udp.connectTimeoutSeconds` | 10 | `CONNECTING` 会话从创建起算的建立超时，客户端重传不会刷新 |
| `proxyworker.udp.failureCacheSeconds` | 5 | 建立失败后 `REJECTED` 会话的保留时间，比策略拒绝短，便于瞬时 DNS 故障恢复 |

"最久未活跃"用 `LinkedHashMap(accessOrder = true)` 实现。客户端报文查表时会刷新顺序；目标回包不经过查表，所以回包时也要显式刷新一次顺序，否则只有下行流量的会话会被误淘汰。

## 9. 服务器生命周期

- `TransparentProxyServer.doStart`：先启动 TCP，再启动 `TransparentUdpServer`。UDP 启动失败只记录错误，不影响 TCP，因为 TCP 是现有核心功能。之后每次原地刷新运行时配置都会重试启动 UDP，不重启 TCP；`TransparentProxyServer.isUdpRunning()` 反映 UDP 是否在运行。epoll 不可用导致的跳过不重试。
- `TransparentProxyServer.doClose`：先关闭 UDP，即关闭所有会话的出站 channel、所有回包 socket，然后关闭监听 channel；再关闭 TCP。
- `updateRuntimeConfig`：同步更新 `TransparentUdpServer` 的运行时配置快照，新会话使用新快照。
- UDP 的 event loop group 生命周期跟随 `TransparentUdpServer`。监听 channel 只有一个，group 只需一个线程。
- 监听 IP 和 TCP 一致。TPROXY 要求监听 `0.0.0.0` 或 `--on-ip` 指定的地址，部署文档中已建议填 `0.0.0.0`。

## 10. 脚本改动

`backend/tproxy_netty.sh` 增加变量：

```bash
ENABLE_UDP_TPROXY=0          # 1 = LAN 发往 Fake IP 地址池的 UDP（除 53）交给 worker 透明代理
UDP_TPROXY_443=1             # 1 = 地址池内的 UDP/443（QUIC）也交给 worker；0 = 仍按 REJECT 规则拒绝
```

`ENABLE_UDP_TPROXY=1` 时需要 `ENABLE_TPROXY=1`，因为两者共用 fwmark 和策略路由表。规则示意：

```bash
iptables -t mangle -A PREROUTING -i ${LAN_IF} -s ${LAN_CIDR} -p udp -d "${FAKE_IP_CIDR}" \
  -m comment --comment "${CHAIN_LAN}_udp" -j ${CHAIN_LAN}_udp
iptables -t mangle -A ${CHAIN_LAN}_udp -p udp --dport 53 -j RETURN
# UDP_TPROXY_443=0 时：
iptables -t mangle -A ${CHAIN_LAN}_udp -p udp --dport 443 -j RETURN
iptables -t mangle -A ${CHAIN_LAN}_udp -p udp \
  -j TPROXY --on-port ${TPORT} --tproxy-mark ${MARK}/${MARK}
```

- 被 TPROXY 的报文进入本机 INPUT，不经过 FORWARD，所以 `REJECT_FAKE_IP_UDP` 规则保持不变：它仍然拒绝 `ENABLE_UDP_TPROXY=0` 时的地址池 UDP，以及被 RETURN 的端口。
- 地址池以外的 UDP 不受影响：UDP/443 仍按 `REJECT_UDP_443` 拒绝，其余仍由内核 NAT 转发。
- 防回环：回包 socket 的报文源地址是 T，目标是 LAN 客户端；出站 channel 的目标是真实 IP，代码已拒绝地址池内的目标。现有 OUTPUT 打标规则只匹配 TCP，所以 worker 自己发出的 UDP 都不会被重新引流，一期不需要 `SO_MARK`。
- 本机发往 Fake IP 的 UDP 不在一期范围，OUTPUT 的拒绝规则保持不变。
- 重复执行脚本时，先清理 `${CHAIN_LAN}_udp` 链和 PREROUTING 跳转规则，开关关闭时也要清理。
- 实现完成后，同步更新 [透明代理部署说明](transparent-proxy-deployment.md) 和 [Fake IP 部署与验证](fake-ip-deployment.md)。

回滚：设 `ENABLE_UDP_TPROXY=0`，重新执行脚本，即恢复为现有行为。worker 侧的 UDP 监听收不到流量，没有副作用。

## 11. 计数器

`UdpRelayCounters` 使用原子计数，内容包括：

- 当前值：会话数（按状态）、回包 socket 数。
- 累计值：新建会话数、按原因分类的关闭数（idle、evicted、connect_timeout、outbound_error、reply_bind_error、reply_write_error、rejected_expired、server_close）、上下行报文数与字节数。
- 丢弃数，按原因分类：invalid、fake_ip_miss、no_user、route_unsupported、pending_full、unwritable、per_client_limit、rejected、not_ready。

有活动时每 5 分钟输出一行 INFO 汇总日志。是否并入 `WorkerMetricsCollector` 心跳留给三期。

## 12. 测试

单元测试：

- `RouteSelector`：规则顺序、各类条件匹配、兜底路由、telemetry 字段，与提取前的行为一致。
- `UdpSessionManager`：用假的出站 connector 和假的回包 socket 池覆盖以下场景：
  - 同一 key 的并发首包只建一个会话，首包缓冲按顺序发出；
  - 缓冲按报文数和字节数截断；
  - 拒绝路径插入 `REJECTED` 会话，过期后重新评估；
  - 容量淘汰和每客户端上限；
  - `close` 幂等，引用计数在各条关闭路径上都只减一次；
  - `remove(key, this)` 不会误删新会话；
  - ByteBuf 在缓冲丢弃、会话关闭、写出失败时都被正确释放。
- `UdpRouteSupport`：策略表。

集成测试：在回环地址上用真实 epoll UDP 验证 `DirectUdpOutboundConnector`，包括 IP 直连、经 DNS 视图解析、地址池目标被拒、空闲超时后关闭。

E2E：扩展 `scripts/e2e/transparent-proxy-e2e.sh`，在源站侧增加 UDP echo fixture，客户端 namespace 内覆盖：

| 场景 | 断言 |
|---|---|
| Fake IP UDP 直连 | 客户端向 DNS 返回的 Fake IP 发 UDP，收到 echo，回包源地址等于 Fake IP:port |
| 多客户端端口、同一目标 | 两个客户端 socket 都能收到各自的回包，回包 socket 计数为 1 |
| 代理路由拒绝 | 命中 `OUTBOUND_PROXY` 路由的域名收不到回包，丢弃计数增长 |
| Fake IP 未命中 | 向未分配的地址池地址发 UDP，收不到回包 |
| 大报文 | 3000 字节报文完整往返，验证没有被截断 |
| 空闲回收 | 超时后会话数、回包 socket 数归零，worker fd 数回到基线 |
| 开关关闭 | `ENABLE_UDP_TPROXY=0` 时地址池 UDP 收到 ICMP 端口不可达 |

手工验证 HTTP/3：

- 选一个路由为直连且支持 HTTP/3 的站点，在 Chrome 开发者工具的 Protocol 列确认显示 `h3`。也可以用支持 HTTP/3 的 curl 执行 `curl --http3-only`。
- 访问走代理路由的站点，确认页面正常加载，Protocol 列显示 `h2`。

## 13. 任务拆分

| 序号 | 任务 | 验证 | 状态 |
|---|---|---|---|
| 1 | netns 小实验：确认 `recipient()` 能拿到原始目标；bind 到非本机地址的回包 socket 发出的报文，客户端能正常收到 | 实验脚本与结论记录 | 已完成 |
| 2 | 提取 `RouteSelector` 和公共的 `queryAddresses`，TCP 行为不变 | 新增单测、现有 worker 单测、`make e2e-transparent` | 已完成 |
| 3 | `UdpSession`、`UdpSessionManager`、`ReplySocketPool`、`UdpRelayCounters` | 单元测试 | 已完成 |
| 4 | `UdpOutboundConnector`、`DirectUdpOutboundConnector`、`UdpRouteSupport` | 回环集成测试 | 已完成 |
| 5 | `TransparentUdpServer`、`TransparentUdpInboundHandler`，接入 `TransparentProxyServer` 生命周期 | 启停、配置刷新的单测 | 已完成 |
| 6 | 脚本开关与规则清理 | 重复执行、开关切换后 `iptables -t mangle -S` 检查 | 已完成 |
| 7 | E2E 场景扩展 | `make e2e-transparent` | 已完成 |
| 8 | 部署文档、`docs/code-index.md`、`docs/testing/proxy-worker-testing.md` 更新 | 文档评审 | 已完成 |
| 9 | 二期前置验证：Xray 对 Vision flow 与 UDP 命令的处理 | 结论记录到整体规划 | 已完成 |

## 14. 风险与待验证

- TPROXY UDP 和回包 socket 的内核行为需要在任务 1 中实测，包括 `SO_REUSEADDR` 下多个回包 socket 与监听 socket 的端口关系、`rp_filter` 对回包的影响。
- 走代理路由的 QUIC 被静默丢弃后，Chrome 会让 TCP 与 QUIC 竞速，并把该站点的 QUIC 标记为不可用。首次访问可能有几百毫秒的额外延迟。如果影响明显，可以设 `UDP_TPROXY_443=0` 恢复为脚本的 REJECT 规则。
- 单 event loop 是吞吐上限。家庭场景足够，但 BT 这类大流量 UDP 进入地址池后会占满这个线程。一期依赖"只引流地址池"控制范围，计数器用于观察实际负载。
- 对称 NAT 行为会让部分游戏、P2P 的 NAT 类型降级，属于一期已知限制。

## 15. 实施记录（2026-10-29）

任务 1–9 已实现，代码位于 `proxy-worker/udp`、`router/RouteSelector` 和 `dns/OutboundAddressQuery`。实验、参数、自动化验证与二期前置结论见 [一期实施与验证](../testing/udp-transparent-relay-phase1.md)。人工 HTTP/3 验收需要支持 HTTP/3 的客户端，当前环境的 curl 未编译此能力，留作部署验收。

实施细化：

- UDP 通过运行时快照 supplier 读取 TCP 服务的原子配置，保持现有 final 更新 API；旧会话不变，新会话使用刷新后的路由与 DNS 视图。
- 单秒 sweep 额外释放 CONNECTING 超时和冷拒绝缓存；出站 READY 仍使用 ALL_IDLE。CONNECTING 超时按创建时间计算（`connectTimeoutSeconds`），不受客户端重传影响。
- 评审后补充（2026-10-29）：首包缓冲按实际长度拷贝；建立失败插入 `failureCacheSeconds` 短期拒绝；UDP 启动失败在配置刷新时重试；回包方向写失败计为 `reply_write_error`，未就绪时到达的回包计为 `not_ready`。
- 容量淘汰及关闭统一经幂等 session.close，覆盖尚未完成的 connect/DNS；带条件删除不误删同 key 新流。
- 正常关闭额外输出最终内部计数，供 E2E 核对资源归零；不加入日志事实或心跳。
- 启停验证放在 `TransparentUdpServerIT`，与回环 connector 测试一起由 Failsafe 执行。
- 原透明 E2E 的 TCP 审计改用已存在的 V1 查询，避免等待已停用采集的旧 access 日志。
