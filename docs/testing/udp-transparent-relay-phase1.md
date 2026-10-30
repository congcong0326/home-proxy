# UDP 透明转发一期实施与验证

实现范围为 IPv4 `TP_PROXY` 入站和 DIRECT UDP；不增加 common 配置字段、管理台选项、V1 日志事实或心跳字段。默认脚本不引流 UDP，需要部署者显式开启。

## 运行时实现

`TransparentProxyServer` 组合 `TransparentUdpServer`，共用监听地址/端口。UDP 使用单线程 epoll，TCP 先启动，UDP 失败只记录错误；关闭时先释放 UDP 会话及 socket，再关闭 UDP 监听和 TCP。UDP 新会话通过 supplier 读取 TCP 服务同一份原子运行时快照，所以刷新无须覆写 `AbstractProxyServer` 的 final 更新方法；旧会话保留自己的路由和 DNS 视图。

会话 key 为 `(C,T)`。新流同步反查 Fake IP、识别用户并选路，先插入 CONNECTING，再异步创建共享透明回包 socket 和连接真实目标。两者都成功才发送首包缓冲。只有 DIRECT 可以转发；其余策略、用户缺失或映射缺失进入有界 REJECTED 缓存。每客户端上限统计包括拒绝条目，触及该上限时直接丢弃新流，避免拒绝缓存自身突破上限。

`LinkedHashMap(accessOrder=true)` 维护 LRU，回包也刷新顺序；全局满时优先清除拒绝项，再淘汰最久未活动流。出站 ALL_IDLE 关闭会话；每秒额外 sweep 处理尚未建成 channel 的 CONNECTING 超时（从创建起算，重传不刷新）和冷拒绝项过期，防止 DNS/bind 卡住时长期占用资源。建立失败后插入短期拒绝项，重传报文不再重复 bind 与 DNS。

`UdpSession.close(reason)` 幂等清理所有状态，包括未完成 connect 的 channel、DNS 订阅、首包 ByteBuf 和共享回包 socket 引用。异步成功晚于关闭时立即关闭新 channel。引用数归零即移除回包 socket；绑定失败时所有等待该 socket 的会话经失败监听关闭。

DIRECT 域名使用 `OutboundAddressQuery`：A 成功但为空才查 AAAA，支持取消当前查询。客户端地址 C 决定 DNS 设备视图；缺少 DNS 视图则失败。IP 字面量和解析答案都禁止 Fake IP 地址池，以免回环。与 TCP TP_PROXY 一致，不做公网地址限制。连接到实际目标的 UDP socket 只接收该目标的回包，属于对称 NAT。

## 参数和诊断

仅通过 JVM 系统属性 `proxyworker.udp.*` 配置，每个监听启动时捕获，修改后需要重启监听/worker：

| 后缀 | 默认值 |
| --- | --- |
| `idleTimeoutSeconds` | 60 |
| `maxSessions` | 4096 |
| `maxSessionsPerClient` | 1024 |
| `pendingMaxPackets` | 16 |
| `pendingMaxBytes` | 65536 |
| `rejectCacheSeconds` | 30 |
| `maxDatagramSize` | 65535 |
| `connectTimeoutSeconds` | 10 |
| `failureCacheSeconds` | 5 |

参数必须为正整数，`maxDatagramSize` 不得超过 65535。监听与出站都显式使用该大小的 `FixedRecvByteBufAllocator`，避免 epoll 默认 2048 字节截断。降低该值会限制接收长度。

`UdpRelayCounters` 为线程安全的内部计数器：`sessions_connecting/ready/rejected`、`reply_sockets` 是当前值，`sessions_created`、`session_closed_*`、`uplink/downlink_packets/bytes` 和 `drop_*` 为累计值。字节/报文仅在对应异步写成功后增加；拒绝不计入已转发业务载荷。`drop_rejected` 统计命中短期拒绝缓存的后续包。关闭原因额外有 `rejected_expired`，用于区分拒绝缓存自然到期。

有计数变化时每 5 分钟输出一条 `UDP relay` INFO 汇总；正常关闭时输出最终 `UDP relay closed`，便于不增加 API 的情况下核对资源回收。未接入正式日志模型。

## 内核原始目标实验

```bash
make e2e-udp-kernel
# 已打包时可跳过构建：
UDP_PROBE_BUILD=0 bash scripts/e2e/udp-original-destination-netns.sh
```

实验由两个独立 namespace 和 veth 构成，所有策略路由、sysctl 和 iptables 都位于临时 router namespace，退出删除。Java probe 使用项目实际 Netty epoll 依赖和 `ReplySocketPool`：监听 `0.0.0.0:39992`，将客户端发往 `198.18.2.3` 的 UDP 引入；验证 recipient 为原始目标，并从透明 socket bind(T) 回包。

2026-10-29 实测四组均通过：原目标端口 39992/39993 × router `rp_filter=0/1`。每组两个客户端端口各收到完整 3000 字节，回包源为 `198.18.2.3:原目标端口`，共享回包池大小为 1。`SO_REUSEADDR` 下目标端口与通配监听相同时也可正常绑定和发送。该结论针对实验的对称路由，复杂多 WAN/非对称路由仍须按部署排查反向路径过滤。

## 自动化验证

```bash
mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl proxy-worker -am verify -Pworker-it -DskipTests=false \
  -Dit.test=DirectUdpOutboundConnectorIT,TransparentUdpServerIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
make e2e-udp-rules
make e2e-transparent
make e2e-xray-udp-flow
```

- `RouteSelectorTest`：顺序、GEO/DOMAIN/RULE_SET、AD_BLOCK 不匹配、兜底及匹配规则 telemetry。
- `OutboundAddressQueryTest`：真实本地 DNS 先 A 再 AAAA，分别在 A/AAAA 阶段取消后 pending/in-flight 归零。
- `UdpSessionManagerTest`：重复首包只建一次、缓冲按包数/字节限额、拒绝缓存过期、新配置快照、上下行 LRU、全局/每客户端上限、条件删除、ByteBuf 丢弃/关闭/写失败释放、引用计数和迟到回调。
- `DirectUdpOutboundConnectorIT`：真实 epoll 直连和 DNS 视图、3000 字节、真实收发字节数、池内目标/污染/无 DNS 视图失败、空闲后资源清空。回环 echo 已占用 T，测试用随机端口回复池替身；原始回包源地址由上述 netns 及完整 E2E 验证。
- `TransparentUdpServerIT`：TCP/UDP 共用端口及关闭释放、配置刷新、epoll 禁用时跳过 UDP、UDP 绑定失败时 TCP 仍可用。
- 规则专项：在独立 netns 重复应用真实 `backend/tproxy_netty.sh`，检查唯一跳转、53 RETURN、443 开关切换和关闭后清链，保留 FORWARD/OUTPUT REJECT；UDP 开启但 TCP 关闭时在改规则前报错。
- 完整 E2E：Fake IP UDP 回包原始源地址、两个客户端端口共享一个回包 socket、3000 字节完整往返、不支持的代理路由/未知映射拒绝、空闲后两个会话/回包 socket 归零及 fd 回基线、关闭跳转后收到 ICMP。正常重启读取关闭汇总，核对拒绝计数增加且 gauge 归零。TCP HTTP/TLS、路由、DNS、设备身份、审计、指标和重启恢复继续执行。

2026-10-29 最终验证结果：clean verify 通过 common 15 项、worker 190 项单测和 5 项 UDP 相关集成测试，均无失败/跳过；`make e2e-transparent` 通过 36 个原有 Fake IP/TCP 场景和新增 UDP 场景。UDP E2E 的 fd 基线为 87，空闲后回到基线；最终汇总显示上下行各 3 包/9000 字节、`drop_fake_ip_miss=1`、`drop_route_unsupported=1`，会话各状态及回包 socket 均归零。netns 内核实验四组、部署规则专项与 Xray flow probe 全部通过，临时容器/命名空间/规则已清理。

完整 E2E 的 V1 flush 延迟设为 1 秒，仅修改隔离测试控制端配置。旧 access 采集已固定停用，故原审计断言迁到现有 V1 sessions/DNS；TCP 真实解析视图通过设备重写和 fixture 无上游查询交叉验证，未向 V1 session 添加旧 `dnsRouteId` 字段。

## Xray 二期前置验证

```bash
XRAY_BIN=/path/to/xray make e2e-xray-udp-flow
```

默认复用 `.it-cache/xray/v26.3.27/Xray-linux-64/xray`。脚本在临时目录生成测试证书，启动本地 Xray VLESS TLS 1.3 服务和 UDP echo，用真实 VLESS UDP 命令与两字节报文长度编码探测。

2026-10-29 使用 Xray 26.3.27，commit `d2758a0`：服务端账号 `flow=xtls-rprx-vision`，请求同 flow + UDP 被拒绝，日志为 `xtls-rprx-vision doesn't support UDP`；请求 flow 为空 + UDP 可以完整 echo。源码 [inbound.go L552–598](https://github.com/XTLS/Xray-core/blob/v26.3.27/proxy/vless/inbound/inbound.go#L552) 在账号 flow 校验之后明确拒绝 Vision UDP，而空 flow 分支仅拒绝 Vision 账号的 TCP 或非 XUDP Mux，允许普通 UDP。

因此二期可以让 UDP 请求 flow 留空，保留账号 Vision 配置，使用原生 UDP command + 长度封帧；不必因 Vision 账号而直接引入 XUDP。本实验用 TLS 1.3 隔离验证 VLESS flow/command 处理，未将现有 Reality connector 改为 UDP。二期仍须对实际部署的 Xray 版本与 REALITY 传输做端到端联调。

## HTTP/3 与回滚

当前环境 curl 未编译 HTTP/3，未做浏览器 h3/h2 人工验收。部署后将 `ENABLE_UDP_TPROXY=1 UDP_TPROXY_443=1`，访问直连且支持 HTTP/3 的站点，在 Chrome Network 的 Protocol 列检查 `h3`；支持 HTTP/3 的 curl 可用 `curl --http3-only`。代理路由域名应正常回落 TCP/h2；首次 QUIC 竞速可能增加延迟。

如需恢复原行为，设置 `ENABLE_UDP_TPROXY=0` 重跑脚本；如只需 QUIC 立即回落，保留 UDP 开关并设 `UDP_TPROXY_443=0`。本机 UDP 仍由 OUTPUT 拒绝规则处理，一期不接管。
