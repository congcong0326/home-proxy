# proxy-worker 测试指南

本文档记录 proxy-worker 的测试策略和核心回归矩阵。

## 测试分层

- 单元测试：面向 handler、strategy、factory 和工具代码的内存内测试。优先使用 Netty `EmbeddedChannel`；不要绑定真实端口。
- 轻量集成测试：使用本地 Netty client/server 路径，绑定临时端口并验证协议握手。
- 端到端测试：面向真实部署关注点的系统级验证，例如 TProxy、iptables、上游 DNS、DoT 和真实 TLS 握手。

## 运行命令

- 单元测试：`make backend-test`。
- proxy-worker 集成测试：`make worker-it`。
- 透明代理特权 E2E：`make e2e-transparent`。
- Fake IP + 透明代理完整 E2E：`make e2e-fake-ip-transparent`，场景、断言与报告见 [测试说明](fake-ip-transparent-e2e.md)。
- DIRECT 出站地址安全 E2E：`make e2e-direct-outbound-safety`。
- 研发环境 worker 代理 E2E：`make e2e-worker-proxy`。
- proxy-worker 打包验证：`make worker-build`。

`make worker-it` 使用 Maven Failsafe 执行 `*IT.java`，普通 `make backend-test` 仍只执行 Surefire 单元测试。集成测试会按需下载固定版本 xray 到仓库根目录 `.it-cache/xray`，该目录不提交；如果本机已经有 xray，可用 `XRAY_BIN=/path/to/xray make worker-it` 指定可执行文件，也可用 `-Dxray.cache.dir=/path/to/cache` 改缓存目录。

`make e2e-transparent` 是独立的特权 Linux E2E，不会进入 `make backend-test` 或 `make worker-it`。它要求原生 Linux、Docker、`iproute2`、`iptables`、`curl`、`jq`、`openssl`，以及 root 或免密 `sudo`。测试会构建当前 control-manager/worker 镜像，启动独立 ClickHouse/control-manager 栈（控制端元数据使用栈内 SQLite）和 worker；随后创建 network namespace、veth、TPROXY 规则与策略路由。无论成功或失败，脚本都按精确资源名删除容器、Compose 卷、iptables 规则、策略路由、命名空间和 loopback 测试地址。用 `E2E_BUILD=0` 可跳过镜像构建，用 `E2E_KEEP_ARTIFACTS=1` 保留失败现场的临时配置文件。

`make e2e-direct-outbound-safety` 是独立的 Linux/Docker E2E，不需要 root、iptables 或现有控制面。它构建当前镜像后启动临时 ClickHouse、control-manager（SQLite） 和 host-network worker，再通过控制面发布 SOCKS5、HTTP CONNECT、Shadowsocks、TLS SNI TCP 入站。用例验证私网字面量的 SOCKS5 规则禁止状态、HTTP 403、Shadowsocks/TLS SNI 关闭、可信 `DESTINATION_OVERRIDE` 到本地目标的放行，以及 ClickHouse 中的 `PRIVATE_ADDRESS_BLOCKED` 审计记录；运行结束会清理容器、卷和临时凭据。Shadowsocks 客户端使用 Python `cryptography` 的 AES-GCM 实现；可用 `E2E_BUILD=0` 复用已构建镜像。

`make e2e-worker-proxy-setup` 只需在研发/测试控制面执行一次。它在固定的 `home-01` 节点上创建两条入站：`TP_PROXY` 和 `SOCKS5`，并绑定直连、阻断、SOCKS5 出站、目标重写四类路由；默认凭据是 `worker-e2e / worker-e2e-pass`，可通过 `E2E_*` 环境变量覆盖。常规发布前回归只执行 `make e2e-worker-proxy`，该命令复用正在运行的 control-manager 和 worker，不修改管理配置，只启动临时 HTTP 目标和 SOCKS5 上游并验证两条入站的请求结果。透明代理测试使用独立 network namespace、veth、TPROXY 和策略路由，结束后清理所有临时网络资源。

## 集成测试覆盖

| 领域 | 覆盖内容 | 测试类 |
| --- | --- | --- |
| 入站协议 | SOCKS5 入站、HTTP CONNECT 入站经 `DESTINATION_OVERRIDE` 转发到本地 HTTP echo；客户端环回 IP 被拒绝 | `WorkerDirectAndDnsIT` |
| HTTP 正向代理 | 绝对 URL／Host 解析、代理认证、移除代理凭据与逐跳头、2 MiB 请求体／100-continue、chunked 请求体、流水线隔离、直连地址安全及阻断 | `WorkerHttpForwardIT` |
| DNS 入站/出站 | DNS_SERVER 入站经 DNS_SERVER 出站转发到 fake UDP DNS upstream | `WorkerDirectAndDnsIT` |
| 出站代理 | SOCKS5、HTTP CONNECT、Shadowsocks 出站连接到 xray 入站桩 | `WorkerOutboundProxyIT` |
| VLESS REALITY 出站 | SOCKS5 入站经 VLESS + REALITY + `xtls-rprx-vision` 出站连接到本地 xray REALITY 服务端，并访问本地 HTTPS echo | `WorkerVlessRealityVisionIT` |
| 路由策略 | DOMAIN 命中、默认 DIRECT、BLOCK 失败、DNS_REWRITING | `WorkerRoutingIT` |
| xray 客户端兼容 | xray SOCKS 入站作为客户端 peer，分别通过 worker SOCKS5 和 HTTP CONNECT 入站 | `XrayClientCompatibilityIT` |

集成测试默认强制设置 `proxyworker.netty.epoll.enabled=false`，使 worker server 和 outbound connector 使用 NIO，避免普通用户态环境触发 TProxy/IP_TRANSPARENT 权限依赖。

## 核心回归矩阵

| 领域 | 风险 | 单元测试覆盖 | 状态 |
| --- | --- | --- | --- |
| SOCKS5 over TLS 管线 | TLS handler 被意外跳过，或被插入到协议 handler 之后 | `SocksServerInitializerTest` | 已覆盖 |
| SOCKS5 认证协商 | 客户端绕过认证，或收到错误的协商响应 | `SocksServerHandlerTest` | 已覆盖 |
| SOCKS5 CONNECT 规范化 | 路由前丢失目标 host/port 或用户上下文 | `SocksServerHandlerTest` | 已覆盖 |
| HTTP 正向代理与 CONNECT 共存 | 非 CONNECT 被错误拒绝、认证绕过、代理凭据泄露、路径／请求体改写错误、跨目标流水线误转发、CONNECT 首包丢失 | `HttpProxyHandlerTest`、`WorkerHttpForwardIT`、`WorkerDirectAndDnsIT` | 已覆盖 |
| DIRECT 目标地址安全 | 非透明入站的私网/环回/特殊 IP 字面量可绕过路由直连 | `DestinationAddressPolicyTest`、`RouterServiceTest`、`WorkerDirectAndDnsIT` | 已覆盖 |
| DIRECT 域名解析安全 | DNS 重绑定、混合公网/私网回答，或解析后再次按域名连接 | `PublicAddressResolverGroupTest` | 已覆盖 |
| DIRECT 启动级安全链路 | 控制面发布后各受保护入站未执行阻断、协议响应错误、目标重写误拦截或审计误记为网络失败 | `make e2e-direct-outbound-safety` | 已覆盖（隔离 Docker） |
| 透明代理 HTTP 嗅探 | HTTP Host 解析回归导致流量回退到 IP 转发 | `ProtocolDetectHandlerTest` | 已覆盖 |
| 透明代理 TLS SNI 嗅探 | TLS 流量丢失域名路由上下文 | `ProtocolDetectHandlerTest` | 已覆盖 |
| 透明代理未知载荷回退 | 非 HTTP/TLS 流量仍必须转发到原始 IP | `ProtocolDetectHandlerTest` | 已覆盖 |
| 透明代理隧道请求 | 设备 IP 到用户的映射，或首包保留逻辑损坏 | `TransparentServerHandlerTest` | 已覆盖 |
| 透明代理部署链路 | TPROXY、策略路由、`IP_TRANSPARENT`、控制面发布、HTTP Host/TLS SNI 路由、目标重写与阻断 | `make e2e-transparent` | 已覆盖（特权 Linux） |
| 研发环境 worker 代理链路 | `home-01` 的 TP_PROXY/SOCKS5 入站，直连、阻断、SOCKS5 出站、目标重写 | `make e2e-worker-proxy` | 已覆盖（持久化测试环境） |
| DNS UDP 查询规范化 | 丢失 DNS id、qName、qType、client 或 bytes-in 上下文 | `UdpDnsQueryHandlerTest` | 已覆盖 |
| DNS 应答 IP 提取 | DNS 日志丢失 A/AAAA 应答 IP，或错误修改 buffer | `DnsMessageUtilTest` | 已覆盖 |
| DNS 重写 | 无效重写配置或 AAAA 查询得到错误应答 | `DnsRewritingProtocolStrategyTest` | 已覆盖 |
| DNS 转发 id 映射 | 并发 DNS 响应返回给错误的客户端 id | `DnsForwardProtocolStrategyTest` | 已覆盖 |
| DNS question 校验 | 缓存或不匹配的上游响应导致请求混淆 | `DnsForwardProtocolStrategyTest` | 已覆盖 |
| DNS 名称兼容与 Fake IP 边界 | 把 DNS 名称当成严格主机名拒绝，或给下划线/根域名分配 Fake IP、抑制真实 AAAA；放宽后绕过 BLOCK/重写策略 | `DnsNamesTest`、`DnsQueryServiceTest`、`FakeIpRegistryTest` | 已覆盖 |
| DNS 请求异常隔离 | 下划线/根域名未转发到真实上游；超长名称/坏报文关闭所有客户端共享的 UDP 监听；Fake IP 开关关闭后仍受影响 | `DnsServerResilienceTest` | 已覆盖（本地 UDP） |
| DNS CNAME 名称兼容 | 上游 CNAME 目标含下划线或为根域名时解析失败；超长 CNAME 失败后遗留 pending/共享查询 | `DnsUpstreamPoolTest`、`DnsQueryServiceTest` | 已覆盖 |
| 真实 DNS 上游恢复 | 写失败或连续超时后复用失效 socket，遗留 pending 查询，后续查询无法恢复 | `DnsUpstreamPoolTest` | 已覆盖 |
| 监听存活状态 | DNS socket 已关闭但仍报告运行中，刷新配置时未释放旧事件循环 | `ProxyContextRuntimeRefreshTest` | 已覆盖 |

## 维护规则

- 修改 `protocol/socks`、`protocol/transparent` 或 `protocol/dns` 下的协议 handler 时，在同一变更中更新此矩阵。
- 修改 DNS pending-id 映射、question 校验、重写行为或应答日志时，先新增或更新单元测试，再修改生产代码。
- 修改 TLS 初始化时，保留 handler 顺序的单元测试覆盖；如果握手行为变化，补充轻量集成测试。
- 新增入站协议时，至少添加一个规范化测试，验证其生成的 `ProxyTunnelRequest` 包含用户、目标以及首包行为。
- 保持单元测试确定性：不依赖真实上游 DNS、真实代理端口或 iptables/TProxy。
- 修改 VLESS REALITY / Vision 出站时，保留 `WorkerVlessRealityVisionIT`，确保默认 TLS 1.3 HTTPS 目标能经过本地 xray REALITY Vision 服务端完成请求。
- 修改 `DIRECT` 出站时，保留地址策略与校验 resolver 的单元测试；本地 echo 链路必须使用 `DESTINATION_OVERRIDE`，不能依赖 `DIRECT 127.0.0.1`。

## HTTP 正向代理行为

`HTTPS_CONNECT` 入站同时支持普通 HTTP 请求与 CONNECT，共用 Basic 代理认证、用户路由和出站地址策略。普通 HTTP 优先使用绝对 URL 的目标；origin-form 请求使用唯一的 Host 头，默认端口为 80。转发前将请求行改为路径与查询串，移除 `Proxy-Authorization`、`Proxy-Connection` 和逐跳头，保留源站的 `Authorization`。

普通 HTTP 每条 TCP 连接只处理一个请求，向源站发送 `Connection: close`；浏览器后续请求重新连接。请求体采用流式转发，支持 Content-Length、chunked 和由源站处理的 `Expect: 100-continue`，不受 CONNECT 聚合器的 1 MiB 限制；出站连接完成前仍沿用既有有界首包缓存。后续流水线请求不会转给第一个目标。CONNECT 隧道不受此单请求策略影响。

定向回归：`mvn -f backend/pom.xml -pl proxy-worker -am verify -Pworker-it -DskipTests=false -Dit.test=WorkerHttpForwardIT,WorkerDirectAndDnsIT -Dfailsafe.failIfNoSpecifiedTests=false`。

2026-10-15 验证：common 15 项和 worker 175 项单测、10 项集成测试全部通过。网关现有版本与仓库有其他差异，因此现场补丁基于原 JAR，仅替换 HTTP 包的 8 个变更／新增 class，其他业务代码和依赖不变；该补丁直接通过 23 项 handler 用例和 10 项集成测试后部署。

现场实测 `192.168.10.1:8080`：普通代理访问 `http://my-home-page.com/` 返回 200 和完整的 54141 字节页面；无认证／错误认证返回 407；HTTP CONNECT 隧道和页面均返回 200；`https://s-s-admin.cn/` CONNECT 返回 200，跳过现有自签名证书校验后源站仍按原行为返回 401。未修改入站、用户和路由配置。

网关镜像为 `home-proxy-worker:http-forward-20261015-123105`，补丁构建上下文保留在 `/root/docker/patches/http-forward-20261015-123105`；原镜像保留为 `home-proxy-worker:before-http-forward-20261015-123105`。回滚时在网关 root 下执行：

```bash
docker tag home-proxy-worker:before-http-forward-20261015-123105 home-proxy-worker:latest
docker compose -f /root/docker/docker-compose.yml up -d --no-deps --no-build --force-recreate proxy-worker
```

## 集成测试待办

- SOCKS5 over TLS：绑定临时 worker 端口，完成真实 TLS 握手、认证并发起 CONNECT。
- DNS UDP 转发：绑定本地 UDP DNS worker 和假上游，然后验证并发查询 id 映射。
- DoT 上游：模拟 TLS DNS 上游，并验证带长度前缀的 DNS 请求/响应流程。

## UDP 透明转发一期

`RouteSelectorTest` 验证抽取后的规则顺序、DOMAIN/GEO/RULE_SET、AD_BLOCK 不匹配、兜底和 telemetry；`OutboundAddressQueryTest` 验证 A/AAAA 顺序和两阶段取消。`UdpSessionManagerTest` 使用假 connector/回包池覆盖缓冲、拒绝、容量、LRU、配置快照、条件删除、引用计数、写失败和 ByteBuf 释放。

`DirectUdpOutboundConnectorIT` 用真实 epoll 和本地 UDP echo 验证 IP/域名直连、3000 字节、DNS 池内污染拒绝与 idle 清理。`TransparentUdpServerIT` 验证同端口 TCP/UDP 启停、刷新、epoll 关闭和 UDP 绑定失败时 TCP 保持可用。透明 UDP 不做公网限制，与 TCP `TP_PROXY` 保持一致，因此这里允许 loopback DIRECT。

```bash
make e2e-udp-kernel       # root，独立 netns 验证 recipient、透明回包及 rp_filter
make e2e-udp-rules        # root，独立 netns 重复应用真实部署脚本并切换 UDP/QUIC
make e2e-transparent     # 独立控制端/worker，包含 UDP DIRECT、拒绝、大报文、fd 回收、ICMP 回滚
make e2e-xray-udp-flow   # 需要本地 Xray，核对二期 UDP flow 前置条件
```

详细断言、结果和 HTTP/3 手工检查见 [UDP 一期实施与验证](udp-transparent-relay-phase1.md)。原透明 E2E 的 TCP 审计使用 V1 sessions/DNS，避免依赖已停用采集的旧 access 日志；TCP 真实 DNS 视图通过 fixture 查询记录及设备重写验证，V1 session 没有旧 `dnsRouteId` 字段。
