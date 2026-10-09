# 新版连接记录：阶段耗时采集

2026-10-09 补充连接细分耗时。沿用 V1 独立日志链路，不改旧访问日志、协议响应、目标安全验证、路由规则或转发行为。新采集只影响更新 Worker 后的新连接；历史 null 不从整体耗时回填。

## 字段与边界

所有耗时使用单调时钟，单位 µs，JSON 使用十进制字符串；壁钟仅用于时间点。

| 字段 | 实际测量边界 | 适用范围 |
| --- | --- | --- |
| clientHandshakeDurationUs | 接收连接至解析完整代理请求；认证失败至失败，提前关闭至关闭 | SOCKS5、HTTP CONNECT、Shadowsocks，包含客户端发送等待、认证及入站 TLS（若启用） |
| targetRecoveryDurationUs | 接收连接至完成目标识别；提前关闭至关闭 | 透明代理 Fake-IP 反查、HTTP Host／TLS SNI 嗅探、原始 IP 回退；TLS SNI 入站 |
| routeSelectionDurationUs | RouterService 进入选择至完成或退出选择 | 实际进入选路的连接，包含规则匹配和默认路由选择 |
| dnsDurationUs | 本机出站域名解析至完成／失败，包含 A／AAAA 回退或系统解析 | 直连／目标重写的域名、上游代理节点域名；包括缓存路径，不测量上游代理自行解析最终目标的时间 |
| outboundTcpDurationUs | 地址解析结束后实际 socket connect 至成功／失败 | 直连、目标重写、HTTP／SOCKS5／Shadowsocks／VLESS REALITY 上游 |
| outboundTlsDurationUs | TCP 就绪至本机 REALITY TLS 握手完成／失败／关闭 | VLESS REALITY；HTTPS 隧道内部 TLS 不可观测，不使用总时长估算 |
| proxyHandshakeDurationUs | TCP 就绪至代理响应／本地发送完成；VLESS 从 REALITY TLS 完成起算 | HTTP／SOCKS5 至成功响应；Shadowsocks 至本地请求写入成功；VLESS 至本地请求发送完成；失败／关闭也结束计时 |
| uploadBackpressureDurationUs | 因出站不可写暂停客户端读取的实际区间之和 | 进入中继后开始监测；未发生为 0，重复通知不重复计数，关闭结束开放区间 |
| downloadBackpressureDurationUs | 因客户端不可写暂停出站读取的实际区间之和 | 同上，按下载方向累计 |

原有 sessionDurationUs 与 channelSetupDurationUs 保留。各阶段可能包含或重叠，尤其入站协议等待与目标嗅探，不得将所有耗时直接相加。DNS 耗时包括本机缓存与解析调度，TCP 排除 DNS。

代理握手结果：HTTP／SOCKS5 为 SUCCESS（收到上游成功响应）；Shadowsocks／VLESS 为 LOCAL_REQUEST_SENT（本地请求已发送，不代表上游确认目标连接）。实际握手失败为 FAILED，已开始握手但提前关闭为 CANCELLED。结果与耗时说明在详情字段旁可查看。

不适用或未执行阶段为 null，不能填 0。成功进入中继且未发生背压才写 0。阶段失败／取消保留实际经过的时长，不代表阶段成功。关闭时结束开放区间，结束汇总发布后忽略迟到的采集回调。

## 无执行来源字段清理与兼容

Worker 仅有限流配置模型，没有执行限流的 handler／等待队列。活动 DTO 和前端删除 rateLimitPolicyId、rateLimitWaitUs；不将背压或普通排队时间改名为限流等待。

不删除 ClickHouse 现存列或历史事实，新建表不再创建这两列。旧 Worker 发送的两个 null 字段由 DTO 定向忽略，查询旧原始 JSON 时也移除这两个 API 字段，V1 重发 hash 仍按旧版两个 null 槽位计算，避免升级期间重复记录冲突。新记录的原始 JSON 不含这两个字段；没有新增数据库字段或 Flyway 迁移。

可以先更新管理端和前端，再更新 Worker；旧 Worker 仍可上报旧采集范围。回退 Worker 后新连接恢复旧采集范围；已有新事实仍可查询。

## 验证

- SessionObservationTest：各阶段独立、微秒精度、重复背压通知／恢复不重复累计、关闭开放区间、失败／取消时长、迟到回调不修改已发布汇总。
- OutboundTimingTest：真实 socket 验证 IP 直连、系统 DNS 域名直连、HTTP CONNECT、SOCKS5、上游拒绝及 Shadowsocks 本地发送；验证 DNS 与 TCP 分离、握手确认边界。
- RelayTimingTest：双向可写性与自动读取暂停／恢复的对应方向，REALITY TLS 与代理发送计时边界。
- WorkerOutboundProxyIT／WorkerVlessRealityVisionIT：开启采集时追加对真实 Xray 上游的握手耗时、REALITY TLS 耗时及本地发送／远端确认边界的断言。
- WorkerSessionTimingIT：真实 SOCKS5／HTTP 入站、认证、选路、域名解析、TCP 与双向载荷转发；认证失败不产生出站耗时。运行时必须设置 -Dobservability.enabled=true。
- TelemetryStoreTest：旧 Worker 的 null 限流槽位可以读取，旧版 hash 重发得到 ACK 且不重新写入；独立表模型不再有活动限流列。
- ObservabilityLogAudit.test.tsx：字符串耗时展示、0／null 区分、不适用协议、真实握手确认结果及限流等待移除。

2026-10-09 验证结果：

- 后端全量单测通过：common 15、proxy-worker 145、control-manager 96，共 256 项，零失败／跳过。命令：`mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local=$PWD/.m2/repository -pl proxy-worker,control-manager -am test -DskipTests=false`。
- 实际入站／上游协议集成验证通过：WorkerSessionTimingIT 3 项、WorkerOutboundProxyIT 3 项、WorkerVlessRealityVisionIT 1 项，共 7 项。使用缓存的 Xray v26.3.27；证明 HTTP／SOCKS5 上游成功响应、Shadowsocks 本地发送、REALITY TLS 与 VLESS 本地发送边界均能采集。测试消费者按批次 ACK 取出连接汇总，遵守稳定重发队列约定。命令：`mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local=$PWD/.m2/repository -pl proxy-worker -am verify -Pworker-it -DskipTests=false -Dtest=SessionObservationTest,RelayTimingTest,OutboundTimingTest -Dit.test=WorkerSessionTimingIT,WorkerOutboundProxyIT,WorkerVlessRealityVisionIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dobservability.enabled=true`。
- 前端审计页 8 项测试通过：`CI=true npm --prefix frontend test -- --watch=false --runInBand ObservabilityLogAudit.test.tsx`。
- `npm --prefix frontend run build` 通过；仅有原 MailGateway／UserManagement 的 lint 提示、浏览器数据陈旧及包体积提示，无本次页面新增警告。
- `git diff --check` 通过。上述代码验证阶段尚未更新运行中的部署；后续本地部署与验收见下节。全程未执行数据库清理。

## 本地部署、E2E 与人工验收（2026-10-09）

已运行 `make docker-build`，更新本地 `home-proxy-control-manager`（包含前端）和 `home-proxy-worker-home-01`。核对运行容器 JAR 与本次构建一致；保留原环境、网络、挂载和历史数据，MySQL／ClickHouse 容器未重建。Worker 新实例为 `44473466-d690-476d-95b2-5c7ab226b82a`。

保留旧镜像标签 `home-proxy-control-manager:before-session-timing-20261009T083347Z` 和 `home-proxy-worker:before-session-timing-20261009T083347Z`，可用于回退应用容器。回退无需清理数据库，已有日志仍可查询。

实际运行结果：

- 阶段 A E2E 通过，产生 11 条连接、11 条认证、2 条 DNS 和 15 条事件；认证失败、拒绝、提前关闭与协议重发／冲突边界通过。
- 持久 Fake-IP E2E 通过，产生 2 条透明代理连接，验证无 Host 的 HTTP 与服务端先发送数据；原透明代理历史记录保留。
- 高级耗时 E2E 通过，产生 8 条连接和 10 条事件：HTTP、SOCKS5、Shadowsocks、VLESS REALITY、上游拒绝、双向 16 MiB 背压及 TLS SNI 目标恢复。
- 独立审核部署后 25 条新连接（包含 4 条前置诊断样本）：UInt64 字符串、时间顺序、各阶段边界、失败路径无虚构阶段、无重复 ID、详情 API 与事实一致、旧表记录数未减少。审核时采集队列为 0，丢弃与序列化失败均为 0。
- B1 只读 API 与浏览器回归通过；新耗时浏览器审核核对 7 个真实详情，筛选得到完整 8 条验收记录，页面值与审核事实一致，浏览器错误为 0。

原有 6 个活动入站保持启用；本次临时测试入站已禁用，测试配置与日志保留，审核时活动连接为 0。所有证书和 REALITY 私钥只在临时目录内生成，测试结束后删除。

### 已审核的实际数值

| 场景 | 实测值 | 审核含义 |
| --- | --- | --- |
| VLESS REALITY | TLS 2.938 ms，代理请求发送 0.238 ms | TLS 与 VLESS 本地发送独立计时；结果为“本地请求已发送” |
| HTTP 上游 | TCP 0.106 ms，代理握手 0.341 ms | 收到上游成功响应；双向各 30 B |
| SOCKS5 上游 | TCP 0.234 ms，代理握手 0.347 ms | 收到上游成功响应；双向各 30 B |
| Shadowsocks | TCP 0.086 ms，代理发送 0.278 ms | 本地请求已发送；双向各 30 B |
| Fake-IP HTTP | 目标恢复 1.312 ms，DNS 39.505 ms，TCP 0.134 ms | 恢复域名后执行本机解析，DNS 未混入 TCP |
| 上传背压 | 上传 16,777,223 B，背压 1,003.337 ms | 16 MiB 载荷及控制头；下载方向背压为 0 |
| 下载背压 | 下载 16,777,216 B，背压 1,078.405 ms | 16 MiB 载荷；上传方向背压为 0 |
| HTTP 上游拒绝 | 握手 0.742 ms，业务转发 0 B | 失败也保留耗时；未进入中继的背压为 null |
| TLS SNI 入站 | 目标恢复 0.540 ms | 客户端握手不适用；隧道内目标 TLS 不填入本机出站 TLS |

### 验收入口

打开本地站点 <https://127.0.0.1:18443>，进入“网关观测 → 新版日志审计 → 连接记录”：

1. Worker 选择 `home-01`。
2. 按北京时间筛选 **2026-10-09 16:43:00 至 16:46:00**（UTC 08:43:00 至 08:46:00）。
3. 用户填写 `home-01-timing-e2e`，应有 **8 条**已审核记录。依次查看 REALITY、上游拒绝及上传／下载样本的“时间与耗时”。
4. 查看 Fake-IP DNS 时，清空用户，时间扩大至北京时间 16:33:00 至 16:47:00，目标填写 `fake-direct.persistent.home-proxy.test`。

证据文件：[独立审核 JSON](assets/session-timing/review.json)、[高级 E2E 事实](assets/session-timing/e2e.json)、[浏览器审核结果](assets/session-timing/browser-review.json)。

截图：[8 条验收连接](assets/session-timing/timing-sessions.png)、[REALITY 耗时](assets/session-timing/timing-reality.png)、[HTTP 握手](assets/session-timing/timing-http.png)、[Fake-IP 与 DNS](assets/session-timing/timing-fake-ip-dns.png)、[上传背压](assets/session-timing/timing-upload-backpressure.png)、[下载背压](assets/session-timing/timing-download-backpressure.png)、[上游失败](assets/session-timing/timing-upstream-failure.png)、[SNI 恢复](assets/session-timing/timing-sni-recovery.png)。

### TLS EOF 兼容性观察

前置 Python HTTPS 探针经过 REALITY 隧道，已收到完整的 Content-Length 响应体，继续读取 TLS EOF 时遇到 `DECRYPTION_FAILED_OR_BAD_RECORD_MAC`。保留该诊断记录；本次验收测试按 Content-Length 核对完整 HTTP 响应体后主动关闭客户端，测试通过。该现象尚未解决，作为独立的 TLS EOF 兼容性问题记录；本次未修改生产转发／协议代码来处理它。日志的 NORMAL_END 仅说明本地连接收尾，不能据此认定远端 TLS 正常关闭或业务成功。

### 重复运行

本地已部署环境可运行 `make e2e-observability-session-timing`。需要缓存的 Xray v26.3.27（`.it-cache/xray/v26.3.27/Xray-linux-64/xray`）、Python `cryptography`、`openssl` 和可访问的本地管理端／Worker；脚本使用命名测试配置，结束时禁用临时入站，不清空日志或卷。新运行的时间窗口写入 `/tmp/gateway-observability-session-timing-e2e.json`。

只读浏览器复核入口为 `scripts/e2e/gateway-observability-session-timing-browser.cjs`，需要 Playwright 与 Chromium，以及 E2E 事实、独立审核事实 JSON。复核本次保留样本的命令如下；其中 `NODE_PATH` 使用本地现有 Playwright 安装，可按环境调整：

```sh
NODE_PATH=/tmp/home-proxy-b1-browser/node_modules \
E2E_TIMING_REPORT=docs/testing/assets/session-timing/e2e.json \
E2E_TIMING_REVIEW=docs/testing/assets/session-timing/review.json \
node scripts/e2e/gateway-observability-session-timing-browser.cjs
```

浏览器复核只查询，不生成新连接记录。若重新运行流量 E2E，应使用该次对应的审核事实，避免比较不同批次。
