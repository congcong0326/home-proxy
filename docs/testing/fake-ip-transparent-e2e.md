# Fake IP + 透明代理完整 E2E

使用隔离的 controller、worker、MySQL、ClickHouse、DNS 上游、HTTP/TLS 源站与客户端网络命名空间，覆盖配置 API → WSS 配置发布 → UDP DNS → Fake IP → Linux TPROXY → 真实解析/选路 → TCP relay → WSS 指标与日志 → ClickHouse → 日志查询 API。

## 运行

需要 Linux、Docker Compose v2、Maven/Java 17、Python 3、curl、jq、openssl、iproute2、iptables，以及 root 或免密 sudo。Docker daemon 和宿主机必须允许 NET_ADMIN、network namespace、TPROXY、策略路由；不能仅用普通 Netty 集成测试代替此测试。

```bash
# 默认打包当前工作区并重建隔离 E2E 镜像
make e2e-fake-ip-transparent E2E_KEEP_ARTIFACTS=1

# 当前代码已经构建到 :e2e 镜像时，可以复用镜像
make e2e-fake-ip-transparent E2E_BUILD=0 E2E_KEEP_ARTIFACTS=1
```

`make e2e-transparent` 执行相同完整测试，包含已有的真实 IP HTTP/TLS DIRECT、DESTINATION_OVERRIDE、BLOCK 六项回归。测试不使用现有 `home-01` 节点，结束后清理本次创建的容器、数据库卷、veth、namespace、TPROXY 规则、策略路由、测试地址及 fixture 进程。`E2E_KEEP_ARTIFACTS=1` 只保留报告和文件，不保留这些运行资源。需要保留管理台配置与服务供手工查看时，使用 [固定节点测试](fake-ip-persistent-e2e.md)。

默认控制端口 28443、worker 管理端口 28444、透明入口 29080、DNS 29553、真实 DNS 29554（同时监听 UDP/TCP）、HTTP 29090/29091、TLS 29490/29491、服务器先发数据 29092、二进制 echo 29093、SOCKS 上游 29094。端口通过脚本中的 `E2E_*_PORT` 环境变量覆盖；测试客户端默认 `192.0.2.2`，源站 `192.0.2.102`，重写源站 `192.0.2.103`。整个 `198.18.0.0/15` 的 TCP 都只从本次测试 veth 引流，以同时验证多协议和任意业务端口。

## 场景与证据

| 场景 | 核心断言 |
| --- | --- |
| 配置发布 | 修改入站后等到 worker online、applySuccess、两个运行入站，以及 fetched/applied hash 一致且已经变化 |
| DNS 报文 | 校验来源地址、随机查询 ID、QR/RD/RA、Question、类型、class、记录数量与 TTL |
| 域名映射 | 重复查询、大小写与末尾点返回同一 Fake IP；不同域名地址不同；AAAA 返回 NOERROR 空答案 |
| 发放阶段 | A/AAAA 只建立映射，上游 fixture 查询记录没有增长 |
| 无 Host HTTP | 连接 DNS 返回的地址，发送 HTTP/1.0 无 Host 请求；必须通过域名路由与真实 DNS 到达源站 |
| 身份优先级 | 映射命中时，故意使用被阻断域名作为 Host/SNI，仍以映射域名选路，HTTP 与 TLS 都到达正确源站 |
| 服务端先发数据 | 不发送客户端首包，在 Fake IP 上直接收到 greeting，证明不等待嗅探 |
| 二进制 relay | 双向传输 256 KiB 非 HTTP 数据，逐字节比对；真实 DNS TTL 过期后同一连接仍可传输；客户端关闭传递给源站 |
| 映射未命中 | 未发放地址通过分段 HTTP Host/TLS SNI 恢复域名并连接；日志来源分别为 HTTP_HOST/TLS_SNI |
| 嗅探失败 | 无 Host、二进制、完全不发首包、未完成且超过 64 KiB 的 HTTP 头均关闭；嗅探关闭不超过 5 秒 |
| TCP BLOCK | 先正常获取 Fake IP，再无 Host 访问；路由阻断并关闭，未查询真实 DNS |
| 目标重写 | Fake IP 域名命中 DESTINATION_OVERRIDE，抵达独立源站，核对原始目的地址和实际连接地址 |
| SOCKS 上游 | 收到 ATYP=DOMAIN、恢复域名和原始端口；业务域名无本地真实 DNS 查询 |
| DNS 规则优先级 | DNS BLOCK 返回 REFUSED；显式 IPv4 重写返回真实地址与 TTL=60，AAAA 为空；二者均不查询上游 |
| 非 A/AAAA | TXT 通过既有转发路径返回真实答案，不分配 Fake IP |
| 真实答案缓存 | 同域名连续连接只查询一次；真实 TTL 过期后新连接重新查询 |
| 并发 | 12 个并发 A 查询取得同一地址；12 个 TCP 并发未命中只发一次真实 DNS 查询，全部收到正确响应 |
| TTL=0 | 两次连接发两次真实查询，不能缓存 |
| CNAME | 使用链末端 A 记录连接；缓存按 CNAME 与 A 的最短 TTL 到期 |
| NXDOMAIN/NODATA | 连接明确失败；SOA 负缓存有效期内不重复查询，负 TTL 到期重新查询；NODATA 包含 A→AAAA 回退 |
| SERVFAIL/超时 | 请求明确失败，不缓存错误；连续请求重新查询，之后正常域名仍可访问 |
| 真实答案含 Fake IP | 上游故意返回池内地址，出站关闭且记录拒绝指标，下一次请求重新查询 |
| UDP 截断 | 上游 UDP 设置 TC；必须通过 TCP DNS 重试取得完整答案后再建立业务连接 |
| 客户端提前关闭 | 真实 DNS 尚未完成时断开；后续同域名连接重新查询并成功，防止遗留 pending/in-flight 或错误缓存 |
| 普通配置刷新 | Fake IP 地址保持；上一配置版本的真实答案缓存不再使用 |
| OUTBOUND_REAL 选路 | 客户端已经持有 Fake IP 时，将真实 DNS 策略改为 BLOCK/重写；TCP DIRECT 遵循新策略，不绕过 DNS 规则 |
| 设备身份 | 为测试客户端 IP 配置专属用户、TCP 路由与 DNS 重写；客户端 DNS 与旧 Fake IP 的 OUTBOUND_REAL 都按同一设备选路；审计核对 userId、TCP 路由和 DNS 路由 |
| API 配置校验 | 多 DNS、多透明入口、移除同 worker 透明入口、错误协议开关、GEO、无真实转发兜底、域名上游、池内上游、自指 DNS 均拒绝；运行快照与正常访问不受影响 |
| 日志落库 | 按客户端端口关联本轮成功/失败请求，查询 API 明细；校验状态、Fake IP/原始端口、恢复域名/来源、TCP 路由、真实 DNS 路由、最终 IP、配置版本及失败原因 |
| 心跳指标 | 观察分配、反查未命中、缓存命中、真实 DNS 超时、截断、池内地址拒绝计数 |
| 开关关闭 | DNS 恢复真实 A/AAAA；旧映射继续访问；未映射地址仍只能恢复 Host 或明确关闭 |
| worker 重启 | 开关保持关闭，重启隔离 worker，避免新发放掩盖映射丢失；旧地址无 Host/空首包关闭，Host/SNI 回退成功；重新开启后重新查询与访问成功 |

连接拒绝要求 EOF 或 reset，socket timeout 一律判为失败。缓存与并发断言使用 fixture 的真实 UDP/TCP 查询次数，不能仅根据客户端拿到 HTTP 200 判断缓存正确。真实 DNS 不依赖公网、宿主机系统 resolver 或外部服务。TCP BLOCK 沿用现有不记录访问日志的策略，其断言核对连接关闭与上游查询；失败审计核对真实解析失败的请求。

## 报告与排障

每个场景输出 `PASS` 或 `FAIL`，首次失败即终止，并输出 worker、controller 和 fixture 诊断。设置 `E2E_KEEP_ARTIFACTS=1` 后，末尾打印的临时目录包含：

- `fake-ip-report.json`：场景状态、耗时、失败信息、已核对请求 ID/配置版本，以及重启前的指标快照。
- `fixture-events.jsonl`：真实 DNS 的域名、问题类型、UDP/TCP，以及 SOCKS 目标和源站连接事件。
- `dns-upstream.log`：fixture 启动或报错记录。
- `fake-ip-config.json`：本轮临时端口、地址、节点和报告路径。

目录还包含权限为 0600 的临时 E2E token，排障后删除目录，不要提交或分享 token。失败之后网络与容器也会清理。默认启动等待上限由 `E2E_TIMEOUT_SECONDS` 控制；DNS 超时、嗅探超时是 worker 自身限制；Python 场景对日志/指标/配置一致性最多等待 90 秒。

## 覆盖边界

本套 E2E 验证 IPv4 Fake IP、UDP DNS、TCP 透明代理与 UDP 截断后的真实 TCP DNS 重试。DNS 监听端的 TCP、DoH、QUIC/UDP 透明转发、IPv6 Fake IP 不在首期范围。真实上游 DoT、多设备同时解析不同视图、完整 32,768 条 LRU 淘汰与负载压力需要另做对应专项；本套并发检查只验证 12 路同域名请求的正确性。
