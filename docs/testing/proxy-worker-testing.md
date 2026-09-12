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
- 研发环境 worker 代理 E2E：`make e2e-worker-proxy`。
- proxy-worker 打包验证：`make worker-build`。

`make worker-it` 使用 Maven Failsafe 执行 `*IT.java`，普通 `make backend-test` 仍只执行 Surefire 单元测试。集成测试会按需下载固定版本 xray 到仓库根目录 `.it-cache/xray`，该目录不提交；如果本机已经有 xray，可用 `XRAY_BIN=/path/to/xray make worker-it` 指定可执行文件，也可用 `-Dxray.cache.dir=/path/to/cache` 改缓存目录。

`make e2e-transparent` 是独立的特权 Linux E2E，不会进入 `make backend-test` 或 `make worker-it`。它要求原生 Linux、Docker、`iproute2`、`iptables`、`curl`、`jq`、`openssl`，以及 root 或免密 `sudo`。测试会构建当前 control-manager/worker 镜像，启动独立 MySQL/ClickHouse/control-manager 栈和 worker；随后创建 network namespace、veth、TPROXY 规则与策略路由。无论成功或失败，脚本都按精确资源名删除容器、Compose 卷、iptables 规则、策略路由、命名空间和 loopback 测试地址。用 `E2E_BUILD=0` 可跳过镜像构建，用 `E2E_KEEP_ARTIFACTS=1` 保留失败现场的临时配置文件。

`make e2e-worker-proxy-setup` 只需在研发/测试控制面执行一次。它在固定的 `home-01` 节点上创建两条入站：`TP_PROXY` 和 `SOCKS5`，并绑定直连、阻断、SOCKS5 出站、目标重写四类路由；默认凭据是 `worker-e2e / worker-e2e-pass`，可通过 `E2E_*` 环境变量覆盖。常规发布前回归只执行 `make e2e-worker-proxy`，该命令复用正在运行的 control-manager 和 worker，不修改管理配置，只启动临时 HTTP 目标和 SOCKS5 上游并验证两条入站的请求结果。透明代理测试使用独立 network namespace、veth、TPROXY 和策略路由，结束后清理所有临时网络资源。

## 集成测试覆盖

| 领域 | 覆盖内容 | 测试类 |
| --- | --- | --- |
| 入站协议 | SOCKS5 入站、HTTP CONNECT 入站转发到本地 HTTP echo | `WorkerDirectAndDnsIT` |
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

## 维护规则

- 修改 `protocol/socks`、`protocol/transparent` 或 `protocol/dns` 下的协议 handler 时，在同一变更中更新此矩阵。
- 修改 DNS pending-id 映射、question 校验、重写行为或应答日志时，先新增或更新单元测试，再修改生产代码。
- 修改 TLS 初始化时，保留 handler 顺序的单元测试覆盖；如果握手行为变化，补充轻量集成测试。
- 新增入站协议时，至少添加一个规范化测试，验证其生成的 `ProxyTunnelRequest` 包含用户、目标以及首包行为。
- 保持单元测试确定性：不依赖真实上游 DNS、真实代理端口或 iptables/TProxy。
- 修改 VLESS REALITY / Vision 出站时，保留 `WorkerVlessRealityVisionIT`，确保默认 TLS 1.3 HTTPS 目标能经过本地 xray REALITY Vision 服务端完成请求。

## 集成测试待办

- SOCKS5 over TLS：绑定临时 worker 端口，完成真实 TLS 握手、认证并发起 CONNECT。
- DNS UDP 转发：绑定本地 UDP DNS worker 和假上游，然后验证并发查询 id 映射。
- DoT 上游：模拟 TLS DNS 上游，并验证带长度前缀的 DNS 请求/响应流程。
