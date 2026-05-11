# proxy-worker 集成测试搭建计划

## Summary

- 搭建用户态集成测试框架，覆盖 proxy-worker 的入站、出站、路由和 DNS 核心链路。
- 采用 `JUnit/Failsafe + Java 轻量测试桩 + xray 真实协议 peer` 的混合模式。
- xray 不提交到仓库；显式运行集成测试时按固定版本下载到本地缓存。
- 普通 `make backend-test` 继续只跑单元测试；新增 `make worker-it` 显式运行集成测试。

## Key Changes

- 新增计划文档：`docs/testing/proxy-worker-integration-test-plan.md`。
- 修改 `backend/pom.xml` 或 `backend/proxy-worker/pom.xml`：
  - 增加 `worker-it` profile。
  - 绑定 Maven Failsafe 执行 `*IT.java`。
  - 默认 Surefire 仍只执行单元测试。
- 修改 `Makefile`：
  - 新增 `worker-it` 目标。
  - 命令为：`mvn -f backend/pom.xml $(MAVEN_ARGS) -pl proxy-worker -am verify -Pworker-it -DskipTests=false`。
- 修改 `.gitignore`：
  - 忽略 `.it-cache/`。
  - 忽略 `backend/proxy-worker/target/it-xray/`。
- 修改 proxy-worker Netty server 启动逻辑：
  - 增加系统属性 `proxyworker.netty.epoll.enabled`。
  - 当 `-Dproxyworker.netty.epoll.enabled=false` 时强制使用 NIO，避免普通用户态测试触发 `IP_TRANSPARENT` 权限问题。
- 新增集成测试工具类：
  - `PortAllocator`：分配本地临时 TCP/UDP 端口。
  - `XrayBinaryManager`：按 OS/arch 下载并缓存 xray。
  - `XrayProcess`：生成临时配置、启动 xray、等待端口可用、关闭进程。
  - `TcpEchoServer` / `HttpEchoServer`：提供本地目标服务。
  - `FakeDnsServer`：提供确定性的 UDP DNS 上游。
  - `WorkerServerHarness`：用 `InboundConfig` 启动 worker 入站并在测试结束关闭 `ProxyContext`。
- xray 固定使用 `v26.3.27`；后续升级需显式改版本。

## 集成测试原理

整体思路是把 proxy-worker 当作真实进程内组件启动，把 xray 当作真实协议 peer 启动，再用 Java 写的轻量客户端和目标服务完成端到端断言。这样可以覆盖真实 TCP/UDP 端口、SOCKS5/HTTP CONNECT/DNS/Shadowsocks 握手、路由选择和出站代理链路，但不依赖 root 权限、iptables、真实公网服务或真实上游 DNS。

### xray 下载与缓存

- 集成测试第一次需要 xray 时，会通过 `XrayBinaryManager.resolve()` 获取二进制文件。
- 如果设置了 `XRAY_BIN=/path/to/xray`，测试直接使用这个可执行文件，并先检查它是否可执行。
- 如果没有设置 `XRAY_BIN`，测试按 `os.name` 和 `os.arch` 推导 GitHub release 资产名：
  - Linux x86_64：`Xray-linux-64.zip`
  - Linux/macOS arm64：`Xray-linux-arm64-v8a.zip` 或 `Xray-macos-arm64-v8a.zip`
  - Windows：`Xray-windows-64.zip` 或 arm64 对应 zip
- 下载地址形如 `https://github.com/XTLS/Xray-core/releases/download/v26.3.27/<asset>.zip`。
- 下载使用 Java `HttpClient`，开启普通重定向跟随，避免 GitHub release 的 302 跳转导致下载失败。
- zip 会解压到仓库根目录 `.it-cache/xray/v26.3.27/<asset-name>/`。Maven 在 `backend/proxy-worker` 模块目录下运行时，代码会向上识别仓库根目录，避免把缓存写到模块内部。
- 解压后非 Windows 系统会给 `xray` 文件设置可执行权限，然后运行 `xray version` 做一次可用性验证。
- `.it-cache/` 被 `.gitignore` 忽略，xray 二进制不会提交到仓库。
- 如需换缓存目录，可使用 `-Dxray.cache.dir=/path/to/cache`；如需升级 xray，可使用 `-Dxray.version=vX.Y.Z` 或修改默认版本。

### xray 启动方式

- 每个需要 xray 的用例通过 `XrayProcess.start(name, configJson, ports...)` 启动独立 xray 进程。
- 测试先把 JSON 配置写到 `backend/proxy-worker/target/it-xray/<name>-<nanoTime>/config.json`，同时准备 `stdout.log` 和 `stderr.log`。
- 启动命令等价于：

  ```bash
  xray run -config backend/proxy-worker/target/it-xray/<case>/config.json
  ```

- 启动后测试会轮询传入的本地 TCP 端口，直到 xray 监听成功；如果进程提前退出或端口超时不可用，会把 xray stdout/stderr 尾部内容带到异常里，便于定位配置错误。
- 用例使用 try-with-resources 持有 `XrayProcess`，测试结束时先 `destroy()`，5 秒内未退出再 `destroyForcibly()`。
- `backend/proxy-worker/target/it-xray/` 是构建输出目录，不提交。

### worker 与测试桩启动方式

- worker 不作为外部 jar 启动，而是在 JUnit 进程内通过 `WorkerServerHarness` 构造 `InboundConfig` 后调用 `ProxyContext.refresh(configs)`。
- `WorkerServerHarness` 会设置 `proxyworker.netty.epoll.enabled=false`，让 server 和 outbound connector 都使用 NIO channel，避免普通用户态测试触发 epoll/TProxy/IP_TRANSPARENT 权限要求。
- 目标服务由 Java 测试桩提供：
  - `HttpEchoServer`：本地 HTTP echo，用于验证 TCP 代理链路真的抵达目标服务。
  - `TcpEchoServer`：普通 TCP echo，后续可用于非 HTTP 场景。
  - `FakeDnsServer`：固定返回配置 IP 的 UDP DNS upstream。
- 客户端也由 Java 测试代码实现：
  - `Socks5TestClient`：执行 SOCKS5 协商、用户名密码认证、CONNECT，并发送 HTTP GET。
  - `HttpConnectTestClient`：执行 HTTP CONNECT 认证和隧道请求。
  - `DnsTestClient`：发送 A 记录 DNS 查询并解析返回 IP。
- 所有端口都由 `PortAllocator` 动态分配到 `127.0.0.1`，用例间不共享固定端口。

### 典型测试链路

- 入站直连：

  ```text
  Socks5TestClient/HttpConnectTestClient
    -> worker SOCKS5 或 HTTP CONNECT 入站
    -> DIRECT 出站
    -> HttpEchoServer
  ```

  断言客户端响应包含 echo 路径，并且 `HttpEchoServer.requestCount()` 符合预期。

- 出站代理：

  ```text
  Socks5TestClient
    -> worker SOCKS5 入站
    -> worker OUTBOUND_PROXY 出站
    -> xray SOCKS5/HTTP/Shadowsocks 入站
    -> HttpEchoServer
  ```

  xray 在这里充当上游代理服务端，用于验证 worker 的出站协议实现能和真实代理 peer 互通。

- VLESS REALITY Vision 出站：

  ```text
  Socks5TestClient
    -> worker SOCKS5 入站
    -> worker VLESS + REALITY + xtls-rprx-vision 出站
    -> xray VLESS REALITY Vision 入站
    -> HttpsEchoServer
  ```

  xray 在这里充当受控 REALITY Vision 服务端；目标服务使用本地 HTTPS echo，默认协商 TLS 1.3，覆盖 Vision padding/direct 切换、VLESS 响应头剥离和真实 HTTPS 请求返回。

- xray 客户端兼容：

  ```text
  Socks5TestClient
    -> xray local SOCKS 入站
    -> xray SOCKS/HTTP 出站
    -> worker SOCKS5 或 HTTP CONNECT 入站
    -> DIRECT 出站
    -> HttpEchoServer
  ```

  xray 在这里充当真实客户端 peer，用于验证 xray 作为下游客户端时能正确接入 worker。

- DNS 转发与重写：

  ```text
  DnsTestClient
    -> worker DNS_SERVER 入站
    -> DNS_SERVER 出站到 FakeDnsServer，或 DNS_REWRITING 直接返回配置 IP
  ```

  断言返回的 A 记录 IP 等于 fake upstream 或 rewrite 配置值。

### 为什么不是提交一个 xray 可执行文件

- xray 二进制体积较大且和 OS/CPU 架构绑定，提交到仓库会带来平台兼容和仓库膨胀问题。
- 按固定版本下载可以保证测试可复现，同时允许不同平台自动选择对应 release asset。
- `XRAY_BIN` 覆盖入口保留了离线或内网 CI 的能力：CI 可以预装 xray，再让测试直接使用本地路径。

## 当前已覆盖场景

| 领域 | 已覆盖场景 | 测试链路 | 核心断言 | 测试类 |
| --- | --- | --- | --- | --- |
| SOCKS5 入站直连 | 客户端通过 worker SOCKS5 入站访问本地 HTTP 目标服务 | `Socks5TestClient -> worker SOCKS5 -> DIRECT -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerDirectAndDnsIT.socks5InboundCanReachDirectHttpTarget` |
| HTTP CONNECT 入站直连 | 客户端通过 worker HTTP CONNECT 入站访问本地 HTTP 目标服务 | `HttpConnectTestClient -> worker HTTP CONNECT -> DIRECT -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerDirectAndDnsIT.httpConnectInboundCanReachDirectHttpTarget` |
| DNS 入站与 DNS 出站 | worker DNS_SERVER 入站把 UDP DNS 查询转发到配置的 fake upstream | `DnsTestClient -> worker DNS_SERVER -> DNS_SERVER outbound -> FakeDnsServer` | 返回 fake upstream 配置的 A 记录，upstream 收到 1 次查询 | `WorkerDirectAndDnsIT.dnsInboundForwardsUdpQueryToConfiguredUpstream` |
| SOCKS5 出站代理 | worker 通过 OUTBOUND_PROXY/SOCKS5 连接 xray SOCKS5 入站 | `Socks5TestClient -> worker SOCKS5 -> worker SOCKS5 outbound -> xray SOCKS5 inbound -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerOutboundProxyIT.socks5InboundCanUseXraySocksOutbound` |
| HTTP CONNECT 出站代理 | worker 通过 OUTBOUND_PROXY/HTTPS_CONNECT 连接 xray HTTP 入站 | `Socks5TestClient -> worker SOCKS5 -> worker HTTP CONNECT outbound -> xray HTTP inbound -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerOutboundProxyIT.socks5InboundCanUseXrayHttpConnectOutbound` |
| Shadowsocks 出站代理 | worker 通过 OUTBOUND_PROXY/SHADOW_SOCKS 连接 xray Shadowsocks 入站 | `Socks5TestClient -> worker SOCKS5 -> worker Shadowsocks outbound -> xray Shadowsocks inbound -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerOutboundProxyIT.socks5InboundCanUseXrayShadowsocksOutbound` |
| VLESS REALITY Vision 出站代理 | worker 通过 OUTBOUND_PROXY/VLESS_REALITY 连接 xray VLESS REALITY Vision 入站 | `Socks5TestClient -> worker SOCKS5 -> worker VLESS REALITY outbound -> xray VLESS REALITY inbound -> HttpsEchoServer` | HTTPS 响应包含 echo 路径，目标服务收到 1 次请求 | `WorkerVlessRealityVisionIT.socks5InboundCanUseXrayVlessRealityVisionOutboundForHttpsTarget` |
| DOMAIN 路由命中 | 域名规则命中后选择 OUTBOUND_PROXY，未命中流量走 fallback BLOCK | `Socks5TestClient -> worker SOCKS5 -> DOMAIN route -> xray SOCKS5 inbound -> HttpEchoServer` | `localhost` 请求成功，IP 请求连接失败，目标服务只收到 1 次请求 | `WorkerRoutingIT.domainRuleCanSelectOutboundProxyWhileFallbackBlocks` |
| BLOCK 路由 | 默认 BLOCK 策略拒绝连接且不触达目标服务 | `Socks5TestClient -> worker SOCKS5 -> BLOCK` | SOCKS5 CONNECT 失败，目标服务收到 0 次请求 | `WorkerRoutingIT.blockRouteRejectsConnectWithoutReachingTarget` |
| DNS_REWRITING 路由 | DNS_SERVER 入站使用 DNS_REWRITING 直接返回配置 IP | `DnsTestClient -> worker DNS_SERVER -> DNS_REWRITING` | A 记录返回配置值 `9.8.7.6` | `WorkerRoutingIT.dnsRewriteRouteReturnsConfiguredARecord` |
| xray 客户端接入 worker SOCKS5 | xray 作为下游客户端 peer，经 SOCKS 出站接入 worker SOCKS5 入站 | `Socks5TestClient -> xray local SOCKS -> xray SOCKS outbound -> worker SOCKS5 -> DIRECT -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `XrayClientCompatibilityIT.xraySocksClientCanUseWorkerSocksInbound` |
| xray 客户端接入 worker HTTP CONNECT | xray 作为下游客户端 peer，经 HTTP 出站接入 worker HTTP CONNECT 入站 | `Socks5TestClient -> xray local SOCKS -> xray HTTP outbound -> worker HTTP CONNECT -> DIRECT -> HttpEchoServer` | 响应包含 echo 路径，目标服务收到 1 次请求 | `XrayClientCompatibilityIT.xraySocksClientCanUseWorkerHttpConnectInbound` |

这些场景合计 12 个 `*IT.java` 用例，覆盖用户态环境下的入站协议、出站代理协议、VLESS REALITY Vision、路由策略、DNS 转发/重写和 xray 互通性。它们不覆盖需要系统权限或真实部署网络栈的场景，例如 TProxy/iptables、SOCKS5 over TLS、DoT 上游、TLS 入站和入站 Shadowsocks。

## Test Plan

- 入站链路：
  - SOCKS5 入站 -> DIRECT -> HTTP echo。
  - HTTP CONNECT 入站 -> DIRECT -> HTTP echo。
  - DNS_SERVER 入站 -> DNS_SERVER 出站 -> fake UDP DNS upstream。
- 出站代理链路：
  - SOCKS5 入站 -> OUTBOUND_PROXY/SOCKS5 -> xray SOCKS5 inbound -> HTTP echo。
  - SOCKS5 入站 -> OUTBOUND_PROXY/HTTPS_CONNECT -> xray HTTP inbound -> HTTP echo。
  - SOCKS5 入站 -> OUTBOUND_PROXY/SHADOW_SOCKS -> xray Shadowsocks inbound -> HTTP echo。
  - SOCKS5 入站 -> OUTBOUND_PROXY/VLESS_REALITY -> xray VLESS REALITY Vision inbound -> HTTPS echo。
- 路由策略：
  - DOMAIN 规则命中 OUTBOUND_PROXY，未命中走默认 DIRECT。
  - BLOCK 策略应让客户端连接失败，echo 服务不收到请求。
  - DNS_REWRITING 对 A 查询返回配置 IP。
- xray 客户端兼容：
  - 测试客户端 -> xray local SOCKS inbound -> xray outbound SOCKS -> worker SOCKS5 入站 -> echo。
  - 测试客户端 -> xray local SOCKS inbound -> xray outbound HTTP -> worker HTTP CONNECT 入站 -> echo。
- 验证命令：
  - 单测：`make backend-test`。
  - 集成测试：`make worker-it`。
  - worker 构建：`make worker-build`。

## Assumptions

- 首版只覆盖普通用户态测试，不覆盖 TProxy/iptables、真实 DoT、TLS 入站、入站 Shadowsocks。
- xray 同时作为上游代理桩和客户端兼容性 peer；最终目标服务和 DNS 断言仍由 Java 测试桩完成。
- 集成测试所有端口使用 `127.0.0.1` 动态端口，不依赖 root 权限、公网服务或真实上游 DNS。
- 每个 `*IT.java` 必须通过 try-with-resources 或 `@AfterEach` 关闭 xray、Java 桩和 `ProxyContext`，避免进程、端口和 Netty event loop 泄漏。
