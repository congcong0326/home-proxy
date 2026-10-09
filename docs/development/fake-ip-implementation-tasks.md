# Fake IP 实施任务

依据 [需求](fake-ip-requirements.md) 的第 3–9 节，按依赖顺序实施。每项均有独立的交付与验收边界；全局验收以需求第 9 节的 15 条为准。

| 顺序 | 任务 | 独立验收 |
| --- | --- | --- |
| 1 | 配置契约：DNS 入站 `fakeIpEnabled` 字段贯通数据库、API、配置 hash、worker 与前端；控制端保存、发布及 worker 应用校验唯一 DNS/透明入站、Fake IP 启用条件和 DNS 上游 IP 字面量 | 迁移可执行；配置序列化和 hash 随开关变化；非法组合明确失败且旧运行配置继续服务 |
| 2 | 进程级 Fake IP 注册表：域名规范化、固定地址池、双向原子 LRU、保留旧映射、池内地址识别和指标 | 分配、复用、淘汰、反查、配置刷新和并发测试 |
| 3 | 共享 DNS 选路：从 UDP 入站抽取身份识别与域名路由，统一阻断、重写、转发和兜底优先级 | UDP 与内部查询对同一客户端和域名选出相同路由；不同设备隔离 |
| 4 | 异步真实 DNS 上游：独立查询 Future、ID/Question 关联、超时/取消/关闭、故障切换及截断处理 | UDP/DoT 上游故障注入测试，无悬挂查询或不完整结果 |
| 5 | 真实答案缓存：A/AAAA、CNAME/负缓存 TTL、300 秒上限、解析视图键和并发合并 | TTL、过期、配置变化、并发与多路由测试 |
| 6 | DNS 客户端应答：先选路再对普通 A 发放 Fake IP，适用域名 AAAA 返回空答案，保留查询 ID/Question 和 TTL=1 | DNS 报文及本地 UDP DNS 测试；关闭开关回归 |
| 7 | 透明代理域名恢复与目标语义：池内地址优先反查；命中后不等首包；未命中限时嗅探；失败拒绝；区分原始、逻辑和连接目标 | TCP 无 Host/SNI、服务端先发送、重启或淘汰后的回退测试 |
| 8 | 出站真实解析：DIRECT、代理端点和目标重写端点使用共享 DNS 异步解析；保留公网目的地址校验与已有路由语义 | SOCKS5/HTTP CONNECT/Shadowsocks、阻断、重写、代理、上游分流测试 |
| 9 | 发布顺序、指标、审计与部署文档：先验证/启动透明监听再开放 Fake DNS；补齐计数、日志和网络前提 | 配置失败回滚测试；指标与访问日志检查；部署步骤可复现 |
| 10 | 全面验收：本地启动 worker 和配套服务，执行透明代理加 DNS 联动场景及其他 E2E 脚本 | 需求第 9 节逐项证据；`make build`、后端/前端测试及现有 E2E 通过 |

实施中每项完成后记录实际测试结果；未完成项不视为验收通过。

## 2026-09-27 实施与验收记录

- 配置契约和发布顺序：V35/V36 已由透明代理 E2E 的 MySQL/Flyway 启动验证；`FakeIpConfigValidatorTest` 覆盖唯一入站、透明代理依赖和上游 IP 字面量；`ProxyContextRuntimeRefreshTest` 覆盖无效配置保留旧监听、端口绑定失败回滚及映射保留。
- Fake IP 注册表、客户端应答与透明代理：`FakeIpRegistryTest`、`FakeIpDnsAnswerHandlerTest`、`FakeIpTransparentHandlerTest` 覆盖规范化、LRU、AAAA 空答案、无首包反查和池内未命中拒绝。`make e2e-transparent` 验证了实际 UDP DNS A/AAAA、TTL=1、重复地址、无 Host TCP、关闭开关后真实 DNS 与旧映射继续工作。
- 真实 DNS：`DnsRouteSelectorTest`、`DnsQueryServiceTest` 和 `DnsUpstreamPoolTest` 覆盖设备选路、缓存视图隔离、并发合并、TTL、CNAME TTL、DoT、UDP 截断转 TCP、超时、取消和上游通道关闭。真实 DNS 查询全程使用 Netty 异步通道和 IP 字面量上游。
- 出站和审计：透明代理 E2E 验证了 Fake IP 恢复后 `DIRECT` 访问真实源站、ClickHouse 中的原始地址、域名来源、DNS 路由、最终连接 IP 与配置版本，以及 worker 状态中的 Fake IP 和真实 DNS 指标。`make e2e-worker-proxy` 和 `make e2e-direct-outbound-safety` 验证了现有代理协议、目标重写、阻断与目的地址防护。
- 构建和测试：`make build`、`make backend-test`、前端 Jest、三套 E2E 均已通过。持久化环境的旧 `home-01` worker 不认识控制端的 `taskPayload` 字段；重启控制端并临时换入当前 worker jar 后，`make e2e-worker-proxy` 通过，随后已恢复原 worker jar。该持久化控制端仍不认识当前 worker 新增的指标字段，长期联动需同步升级两端；隔离式 E2E 已验证新版本两端的配置和指标通信。前端构建存在原有 ESLint 提示；Jest 使用 `--forceExit` 退出已有开放句柄。

部署前按 [Fake IP 部署与验证](fake-ip-deployment.md) 检查 DNS/透明 TCP 引流、地址池与本地网络不重叠、设备源 IP 一致性和 IPv6 限制。
