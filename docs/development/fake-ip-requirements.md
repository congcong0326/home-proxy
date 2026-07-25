# 虚拟 IP（Fake IP）功能需求与设计草案

## 文档状态

- 状态：讨论稿
- 日期：2026-07-25
- 范围：自定义 DNS 与透明代理联动、Fake IP 分配与反查、路由后的真实目标解析
- 说明：本文记录当前已经达成一致的需求和设计约束；尚未确定的配置模型、GEO 路由和 IPv6 等内容统一列在“待讨论项”中。

## 背景

当前系统支持以下能力：

- 根据透明代理嗅探到的 HTTP Host 或 TLS SNI 进行域名分流。
- 将特定域名流量转发到配置的目标 IP 和端口。
- DNS 入站通过 UDP DNS 或 DoT 上游转发查询。
- 通过 DNS 重写返回配置的固定 IPv4 地址。

透明代理场景存在两个问题：

1. 客户端域名解析失败，或者透明代理无法从首包解析 Host/SNI 时，只能按原始目标 IP 路由，导致域名规则失效。
2. 为了让特定域名流量经过透明代理，当前可以通过自定义 DNS 返回固定外网 IP，但后续仍依赖 Host/SNI 恢复域名。对于无 Host、无 SNI、ECH 或非 HTTP/TLS 流量，域名上下文仍可能丢失。

## 目标

- DNS 服务能够针对匹配规则的域名返回 Fake IP。
- worker 保存 Fake IP 与原始域名的映射。
- 透明代理收到目标为 Fake IP 的连接时，优先通过映射恢复域名。
- Fake IP 反查失败时，继续使用现有 HTTP Host 或 TLS SNI 嗅探。
- 恢复出的域名继续使用现有用户、入站和路由配置完成分流。
- 上游代理场景只传递业务域名，不在本地解析业务目标地址。
- DIRECT 场景在确有需要时异步解析真实目标地址，并避免再次命中 Fake DNS。
- 梳理 `ProxyTunnelRequest` 的目标字段语义，同时保证现有入站、出站协议兼容。

## 非目标

- 第一阶段不要求多 worker 共享 Fake IP 映射。
- 第一阶段不要求 worker 重启后恢复内存中的 Fake IP 租约。
- 第一阶段不通过修改 HTTP Host 或 TLS SNI 实现域名恢复。
- Fake IP 映射不负责长期绑定域名当前解析得到的真实 IP。
- 第一阶段不引入通用递归 DNS 缓存服务器能力。

## 术语与目标语义

为避免当前 `targetHost`、`targetIp` 等字段语义混淆，请求模型至少需要区分以下概念。具体字段名可以在实现阶段结合现有代码调整，但语义不能再次合并。

| 概念 | 说明 |
| --- | --- |
| 原始目标地址 | 透明代理从 socket 取得的原始目标 IP 和端口，可能是真实 IP，也可能是 Fake IP |
| 逻辑目标域名 | 用于 DOMAIN、域名规则集和审计的业务域名 |
| 域名来源 | Fake IP 反查、HTTP Host、TLS SNI、显式入站请求或原始 IP |
| 路由结果 | DIRECT、目标重写、上游代理、阻断等策略 |
| 连接端点 | connector 最终实际建立 TCP/UDP socket 的 IP 和端口 |
| 真实解析结果 | DIRECT 等场景针对域名解析得到的真实 IP 候选列表 |

建议增加等价于以下枚举的来源标识：

```text
FAKE_IP
HTTP_HOST
TLS_SNI
INBOUND_REQUEST
ORIGINAL_IP
```

## 域名识别优先级

透明代理按以下顺序确定逻辑目标域名：

```text
Fake IP 反查 > TLS SNI / HTTP Host > 原始目标 IP
```

处理规则：

- 原始目标 IP 属于 Fake IP 地址池时，先查询 Fake IP 映射。
- Fake IP 反查命中后，映射域名是本次路由的权威域名，不依赖 Host/SNI 是否存在。
- Fake IP 命中后如 Host/SNI 与映射域名不一致，不覆盖映射域名，只记录审计信息。
- Fake IP 反查未命中时，继续执行现有 HTTP Host 或 TLS SNI 嗅探。
- Host/SNI 仍无法解析时，回退到原始目标 IP，保持现有四层转发能力。
- 原始目标端口默认保留；只有目标重写等明确配置了端口的路由才覆盖端口。

## 总体请求链路

### DNS 查询

```text
客户端查询 example.com
  -> DNS 规则决定使用 Fake IP
  -> FakeIpRegistry 查询或分配 Fake IP
  -> 保存 198.18.0.10 <-> example.com
  -> 返回 A 记录 198.18.0.10
```

### 透明代理连接

```text
客户端连接 198.18.0.10:443
  -> TProxy 取得原始目标 198.18.0.10:443
  -> FakeIpRegistry 反查得到 example.com
  -> 使用 example.com 进行路由选择
  -> 根据路由结果选择 connector
```

### 路由后的出站处理

```text
BLOCK
  -> 直接拒绝

DESTINATION_OVERRIDE
  -> 连接配置的目标地址和端口

OUTBOUND_PROXY
  -> 连接上游代理
  -> 将原始业务域名和端口写入代理协议握手

DIRECT
  -> 已有可用真实 IP：直接连接
  -> 只有域名：异步真实 DNS 解析后连接解析出的 IP
```

## Fake IP 地址池

### 地址范围

建议默认使用：

```text
198.18.0.0/15
```

该网段用于基准测试，通常比 RFC1918 地址更不容易和家庭网络冲突。地址池必须支持配置，并在启动或配置发布时校验：

- 不能和 LAN、VPN、容器网络、代理出口及本机地址段重叠。
- 系统路由和 TProxy 规则必须能够捕获整个 Fake IP 地址池。
- 地址池容量必须大于或等于允许的最大映射数量。

### 分配原则

- 同一规范化域名在有效租约内重复查询时返回相同 Fake IP。
- 域名规范化至少包括：转为小写、移除末尾 `.`、国际域名转为 ASCII。
- Fake IP 不表示域名当前的真实 A 记录，只表示域名身份。
- 未到安全回收时间的 Fake IP 不能分配给其他域名。
- 地址池耗尽时不能通过 LRU 淘汰有效租约。对明确要求 Fake IP 的查询返回 `SERVFAIL`，并记录告警和指标。

## Fake IP 映射与缓存

Fake IP 映射由 worker 进程级 `FakeIpRegistry` 管理，所有 DNS 入站和透明代理入站共享同一实例。

建议结构：

```text
FakeIpRegistry
  - Cache<fakeIp, FakeIpLease> ipToLease
  - ConcurrentMap<domain, fakeIp> domainToIp
  - FakeIpPoolAllocator allocator
```

两层索引指向同一份租约，不是两个具有独立过期语义的缓存。

`FakeIpLease` 至少包含：

```text
domain
fakeIp
issuedAt
safeReuseAt
```

### TTL 语义

必须区分：

- `answerTtl`：返回给客户端的 DNS TTL。
- `mappingTtl`：worker 内部保留 Fake IP 到域名映射的时间。

建议默认值：

```text
answerTtl       = 60 秒
mappingTtl      = 5 分钟
cleanupInterval = 30 秒
```

约束：

```text
mappingTtl >= 最大 answerTtl + 安全宽限时间
```

行为要求：

- 客户端在 `answerTtl` 到期后重新查询，只要内部租约仍有效，就继续返回相同 Fake IP。
- 每次向客户端重新返回 Fake IP 时刷新内部租约。
- 透明代理反查 Fake IP 不刷新租约。
- 连续一个 `mappingTtl` 周期没有再次签发该 DNS 记录后，映射才可以回收。
- 配置常规刷新不能清空现有 `FakeIpRegistry`。

### Guava Cache 使用约束

- 使用 `expireAfterWrite`，不使用 `expireAfterAccess`。
- `maximumSize` 只作为内存保护，正常分配逻辑不能依赖 `SIZE` 淘汰回收地址。
- 定时执行 `cleanUp()`，避免惰性过期导致地址无法及时归还。
- removal listener 负责条件清理正向索引并归还地址。
- 同一 Fake IP 续租产生的 `REPLACED` 事件不能释放仍在使用的地址。
- 分配和释放路径必须保证双向索引一致；常规反查应保持低开销、线程安全。

## 当前 DNS 中继行为

当前 worker 没有 DNS 响应结果缓存。每次客户端查询需要转发时，都会重新向配置的 UDP DNS 或 DoT 上游发送查询。

现有 Guava Cache 只用于在约 10 秒内关联：

```text
上游 DNS transaction ID <-> 客户端原始 DNS 请求
```

收到响应后该 pending 记录立即删除。上游 UDP/DoT channel 可以复用，但 channel 复用不等于 DNS 结果缓存。

后续如果增加真实 DNS 结果缓存，它必须和 Fake IP 租约分离，并按照上游 DNS 响应 TTL 过期，不能使用 `mappingTtl`。

## 路由与真实 DNS 解析

业务目标域名的真实 DNS 查询应发生在路由选择之后，而不是透明代理入站或通用路由匹配之前。

| 场景 | 是否解析业务目标域名 |
| --- | --- |
| DIRECT，原始目标已经是真实 IP | 否 |
| DIRECT，只有 Fake IP 恢复出的域名 | 是 |
| OUTBOUND_PROXY | 否，业务域名交给上游代理 |
| DESTINATION_OVERRIDE，目标为固定 IP | 否 |
| DESTINATION_OVERRIDE，目标配置为域名 | 是，解析的是配置目标 |
| BLOCK | 否 |

判断原则：

```text
谁是最终实际建立 socket 的 endpoint，谁是域名就解析谁；
只用于路由判断或代理协议握手的域名不在本地解析。
```

上游代理地址如果配置为域名，仍然需要解析代理服务器地址。这属于基础设施地址解析，不是业务目标域名解析，应该使用明确的 bootstrap resolver 或要求配置连接 IP。

## RealDnsResolver

### 职责

`RealDnsResolver` 或等价的 `DirectTargetResolver` 负责：

- 在 DIRECT 等确有需要的路由结果后解析真实目标地址。
- 显式选择真实 DNS 上游，不使用系统默认 DNS。
- 返回真实 IP 候选列表和对应 TTL 信息。
- 拒绝 Fake IP 地址池内的解析结果。
- 支持多个上游和多个结果地址的失败切换。

Fake IP 租约只保存：

```text
fakeIp <-> domain
```

真实解析结果如需缓存，使用独立结构：

```text
domain + qType + resolverProfile -> addresses + expiresAt
```

第一阶段可以不缓存真实解析结果；如果增加缓存，必须严格受上游 TTL 和配置的 TTL 上限控制。

### 非阻塞要求

真实 DNS 解析必须是 Netty 异步链路，不能阻塞 event loop。接口形态可以等价于：

```java
Future<List<InetAddress>> resolve(
        EventLoop eventLoop,
        String domain,
        ResolutionMode mode
);
```

禁止在 event loop 中调用：

```text
Future.get()
Future.sync()
Future.await()
InetAddress.getByName()
```

处理流程：

```text
调用 resolve 并立即得到 Future
  -> 异步发送 UDP DNS 或 DoT 请求
  -> DNS 响应完成 Future
  -> 校验候选 IP
  -> 异步 connect(InetSocketAddress)
  -> 完成“解析 + 连接”的组合 Future
```

DNS 超时、重试和多地址连接尝试均应通过 Future 和 event loop 定时任务完成，不能使用阻塞等待。

### 防止解析与连接打环

DIRECT 不能继续把域名字符串直接传给 `Bootstrap.connect`，否则 Netty/JVM 可能通过系统 DNS 解析，而系统 DNS 可能正是本机 Fake DNS。

必须同时满足：

- 真实 DNS 上游使用明确的 IP 地址。
- UDP DNS 直接连接配置的上游 IP 和端口。
- DoT 将连接 IP 与 TLS SNI/证书域名分开配置。
- 不使用 Java/Netty 默认 resolver 解析业务目标域名。
- 解析结果属于 Fake IP CIDR 时拒绝连接，尝试下一个上游；全部失败时终止连接。
- connector 最终使用已解析的 `InetSocketAddress` 建立连接。

即使网络劫持或配置错误导致真实 resolver 返回 Fake IP，结果校验也必须保证只产生解析失败，不能继续形成 TCP 转发循环。

## 不采用特殊域名绕过方案

讨论过通过类似以下特殊格式请求真实解析：

```text
example.com.__real.homeproxy.arpa
```

该方案只要不修改原始业务数据，就不会直接改变 HTTP Host 或 TLS SNI，但存在以下问题：

- 普通客户端可能利用特殊格式获取真实 IP，绕过 Fake IP 机制。
- 必须额外限制来源地址或增加内部专用监听端口。
- 上游查询使用原始域名，而客户端问题是特殊域名，DNS Question、Answer 和 CNAME 需要进行名称翻译。
- 如果交给系统 resolver，响应名称不一致可能被拒绝。
- 容易再次依赖本机或系统 DNS 路径。

当前方案选择进程内异步 `RealDnsResolver` 和解析模式参数，不通过修改业务域名表达 bypass 语义。

## HTTP、HTTPS 与首包兼容性

- Fake IP 只用于恢复逻辑目标域名，不修改客户端首包。
- HTTP 请求中的 `Host` 保持原样。
- TLS ClientHello 中的 SNI 保持原样。
- TLS 证书校验仍由客户端和真实目标端到端完成。
- 真实 DNS 查询使用的内部状态不能写回逻辑目标域名。
- 最终 connector 按 IP 连接时，转发的仍是客户端原始 HTTP/TLS 数据。

## 入站与出站协议兼容要求

### 入站协议

- `TP_PROXY`：增加 Fake IP 反查，并保留 Host/SNI 和原始 IP 回退。
- SOCKS5、HTTP CONNECT、Shadowsocks 等显式携带目标地址的入站：保持现有目标解析语义；字段改造后必须正确标记目标来源。
- DNS 入站：增加 Fake IP 规则处理，但不能破坏现有 UDP DNS、DoT 上游转发和 transaction ID 关联。
- 非透明代理入站如果显式请求 Fake IP，是否允许反查域名留待实现阶段根据一致性决定，第一阶段需求只要求透明代理联动。

### 出站协议

- DIRECT：最终连接端点为域名时显式异步解析，为 IP 时直接连接。
- SOCKS5、HTTP CONNECT、Shadowsocks、VLESS 等上游代理：业务目标域名作为协议目标传递，不执行本地业务 DNS 解析。
- 目标重写：连接配置目标；配置目标为域名时解析该配置域名。
- BLOCK：不解析、不连接。
- DNS 重写与 DNS 转发：保持现有行为，并与新增 Fake IP 动作清晰区分。

## 生命周期与部署约束

- 第一阶段假设 DNS 查询和后续透明代理连接落在同一个 worker 进程。
- `FakeIpRegistry` 生命周期独立于单个 DNS channel、透明代理 channel 和入站 server 实例。
- 常规聚合配置刷新不能清空 Fake IP 映射。
- worker 重启后内存映射丢失；旧 Fake IP 连接按“反查失败后 Host/SNI，再回退 IP”的规则处理。
- 多 worker、DNS 与透明代理分离部署时，需要共享映射、确定性分配或流量粘滞，不属于第一阶段范围。

## 可观测性要求

至少记录或暴露以下指标：

- Fake IP 当前租约数量和地址池使用率。
- 域名到 Fake IP 的复用次数和新分配次数。
- Fake IP 反查命中、未命中次数。
- 地址池耗尽次数。
- 真实 DNS 查询成功、失败、超时和缓存命中次数。
- 真实 resolver 返回 Fake IP 并被拒绝的次数。
- Fake IP 映射域名与 Host/SNI 不一致次数。
- DIRECT 多地址连接重试次数。

访问日志应区分：

- 原始目标 IP。
- 逻辑目标域名。
- 域名来源。
- 最终真实连接 IP。
- 使用的路由和 resolver profile。

## 验收标准

1. DNS 查询命中 Fake IP 规则后返回地址池中的 A 记录，并建立双向映射。
2. 同一域名在内部租约有效期内重复查询获得相同 Fake IP，且重新签发会续租。
3. 透明代理即使没有 Host/SNI，也能通过 Fake IP 恢复域名并命中 DOMAIN 路由。
4. Fake IP 映射与 Host/SNI 不一致时，路由仍以 Fake IP 映射为准。
5. Fake IP 映射不存在时，透明代理继续按 Host/SNI 和原始 IP 回退。
6. OUTBOUND_PROXY 场景不会在本地解析业务目标域名。
7. DIRECT + 普通原始真实 IP 不增加重复 DNS 查询。
8. DIRECT + Fake IP 域名通过显式异步 resolver 获取真实 IP 后连接。
9. `/etc/resolv.conf` 指向本机 Fake DNS 时，DIRECT 真实解析仍不使用系统 resolver。
10. 真实 resolver 返回 Fake IP 时不会建立连接，也不会产生循环。
11. DNS 解析、超时和多地址尝试不阻塞 Netty event loop。
12. 地址池容量达到上限时不提前复用有效地址，而是按策略返回失败并告警。
13. 常规 worker 配置刷新后，已有 Fake IP 映射继续有效。
14. SOCKS5、HTTP CONNECT、Shadowsocks、VLESS、DNS 转发和普通透明代理场景无行为回归。

## 待讨论项

- Fake IP 应作为 `DnsRuleAction`、独立 DNS 策略，还是复用现有路由策略模型。
- DNS 规则是否需要按入站、用户或设备作用域配置。
- Fake IP 查询是否需要先向真实上游验证域名存在；当前倾向于不预解析，按出站路由懒解析。
- `answerTtl`、`mappingTtl`、地址池大小和耗尽策略的最终默认值。
- A/AAAA 行为。第一阶段建议只支持 Fake IPv4，并对命中 Fake IP 规则的 AAAA 查询返回空答案，避免真实 IPv6 绕过。
- Fake IP 流量如何参与 GEO/IP 规则。若路由阶段必须按真实 IP 做 GEO 判断，需要引入路由阶段的异步按需解析。
- 真实 DNS resolver 是否在第一阶段增加结果缓存，以及 TTL 上限、负缓存和并发请求合并策略。
- 上游代理地址、DoT server name 和 bootstrap IP 的配置模型。
- Fake IP 地址池配置变更时，旧地址池租约如何平滑退出。
- worker 重启恢复、多 worker 共享和跨节点流量粘滞方案。
