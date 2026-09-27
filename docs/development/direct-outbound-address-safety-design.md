# DIRECT 出站目标地址安全校验设计

## 1. 文档状态

- 状态：待评审
- 范围：`proxy-worker`
- 目标版本：待定
- 背景：已确认认证用户可以通过 HTTP CONNECT 直接提交内网 IP，并由 `DIRECT` 路由连接到内网服务。

## 2. 背景与问题

当前 SOCKS5、HTTP CONNECT、Shadowsocks 等入站会把客户端提交的目标地址规范化为 `ProxyTunnelRequest`。路由选择为 `DIRECT` 后，`DirectOutboundConnector` 直接调用：

```java
b.connect(request.getFinalTargetHost(), request.getFinalTargetPort());
```

因此，只要请求命中 `DIRECT` 路由，认证用户就可以提交 `192.168.10.145:22` 等内网目标并由 Worker 发起连接。域名目标同样存在风险：攻击者可以控制一个域名，使其解析到内网、环回或链路本地地址。

本项目的 DNS 服务同时承担正常内网解析，不能全局过滤内网 A/AAAA 应答，否则会影响 NAS、PVE、打印机等正常内部服务。因此，安全边界放在代理的 `DIRECT` 出站连接处，而不是 DNS 服务。

## 3. 设计目标

1. 非透明代理通过 `DIRECT` 策略连接客户端可控目标时，只允许连接公网可路由地址。
2. 客户端直接提交 IP 字面量时不执行 DNS 查询，立即完成地址校验。
3. 客户端提交域名时，只执行连接原本就需要的一次解析；校验解析结果后，使用同一个已解析地址建立连接，避免重复 DNS 和 DNS 重绑定窗口。
4. 管理员通过 `DESTINATION_OVERRIDE` 明确配置的目标属于可信目标，允许重写到内网 IP 和端口。
5. 不修改 DNS 应答，不新增数据库字段、控制面 API 或前端配置。
6. 阻断行为应有明确的协议失败响应和审计记录。

## 4. 非目标

- 本阶段不实现通用 IP/CIDR 规则集。
- 不改变透明代理 `TP_PROXY` 的内网访问行为。
- 不过滤项目 DNS 服务器的内网应答。
- 不改变管理员配置的 `DESTINATION_OVERRIDE` 语义。
- 不保证上游 HTTP/SOCKS5/Shadowsocks/VLESS 代理解析的最终目标一定为公网地址；上游代理需要实施同等策略，详见第 11 节。
- 不提供允许客户端直连特定内网 CIDR 的例外配置。需要访问内网服务时，应使用域名规则加 `DESTINATION_OVERRIDE`。

## 5. 策略语义

### 5.1 受保护的入站协议

以下非透明入站纳入保护：

- `SOCKS5`
- `HTTPS_CONNECT`
- `SHADOW_SOCKS`
- `TLS_SNI_TCP`

以下入站不纳入本策略：

- `TP_PROXY`：透明代理需要保留现有局域网转发能力。
- `DNS_SERVER`：DNS 查询不是 TCP 隧道目标，且本设计不修改 DNS 应答。

### 5.2 路由策略处理矩阵

| 入站/路由场景 | 处理方式 |
| --- | --- |
| 受保护入站 + 客户端提交非公网 IP 字面量 | 立即阻断，不进入连接阶段 |
| 受保护入站 + `DIRECT` + 域名 | 系统解析一次；解析结果通过校验后固定 IP 并连接 |
| 受保护入站 + `DESTINATION_OVERRIDE` | 信任管理员配置，允许目标为内网地址 |
| 受保护入站 + `BLOCK` | 保持现有阻断行为 |
| 受保护入站 + `OUTBOUND_PROXY` | IP 字面量仍在入口阻断；域名的最终解析结果由上游代理负责 |
| `TP_PROXY` | 保持现状，不执行本策略 |
| `DNS_SERVER` | 保持现状，不执行本策略 |

客户端原始目标与最终连接目标必须分开处理：

- `targetHost/targetPort` 表示客户端声明的原始目标；
- `DESTINATION_OVERRIDE` 的 `outboundProxyHost/outboundProxyPort` 表示管理员配置的可信目标；
- 不能对所有 `getFinalTargetHost()` 统一阻断内网地址，否则合法的目标重写也会被拦截。

## 6. 地址判定标准

本设计采用“仅允许公网单播地址”的正向语义，而不是只检查 RFC 1918。至少阻断以下地址类别：

### 6.1 IPv4

- 未指定/当前网络：`0.0.0.0/8`
- 私网：`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`
- CGNAT：`100.64.0.0/10`
- 环回：`127.0.0.0/8`
- 链路本地：`169.254.0.0/16`
- 协议保留、文档和基准测试网段，例如 `192.0.0.0/24`、`192.0.2.0/24`、`198.18.0.0/15`、`198.51.100.0/24`、`203.0.113.0/24`
- 组播与保留地址：`224.0.0.0/4`、`240.0.0.0/4`

### 6.2 IPv6

- 未指定与环回：`::/128`、`::1/128`
- IPv4 映射地址：先还原 IPv4 后按 IPv4 规则判断
- ULA：`fc00::/7`
- 链路本地：`fe80::/10`
- 已废弃站点本地：`fec0::/10`
- 文档地址：`2001:db8::/32`
- 组播：`ff00::/8`
- 其他 IANA 明确标记为不可公网转发的特殊用途地址

地址判定必须基于解析后的原始字节或 `InetAddress`，不能依赖字符串前缀。IP 字面量解析也不得调用会触发 DNS 的 API。

建议新增统一工具类，例如：

```text
security/DestinationAddressPolicy
  parseLiteral(host) -> Optional<InetAddress>
  isPubliclyRoutable(address) -> boolean
  validateAll(addresses) -> ValidationResult
```

## 7. 处理流程

### 7.1 客户端提交 IP 字面量

```text
协议 handler 生成 ProxyTunnelRequest
  ↓
统一安全检查识别受保护入站
  ↓
targetHost 是 IP 字面量？
  ├─ 否：继续路由
  ├─ 是且为公网地址：继续路由
  └─ 是且为非公网地址：策略阻断
```

这一层阻止 `CONNECT 192.168.10.145:22`、SOCKS5 IPv4/IPv6 地址请求和 Shadowsocks IP 地址请求。检查应放在统一请求链路中，不分别复制到各协议 handler。

### 7.2 `DIRECT` 域名目标

```text
RouterService 选中 DIRECT
  ↓
DirectOutboundConnector 创建 Bootstrap
  ↓
系统 resolver 执行一次 resolveAll(host:port)
  ↓
校验全部解析结果
  ├─ 解析失败/无结果：按网络失败处理
  ├─ 任一结果为非公网地址：按安全策略阻断
  └─ 全部为公网地址：选择首个结果
                        ↓
                 connect(resolved InetSocketAddress)
```

首版采用严格策略：同一域名只要返回任意一个非公网地址，就拒绝整个连接，不从混合结果中挑选公网地址继续连接。这样更容易审计，也避免解析顺序变化导致行为不一致。

### 7.3 单次解析与地址固定

不能采用以下流程：

```text
resolve(host) -> 校验 -> connect(host)
```

因为 `connect(host)` 可能再次解析，既产生额外查询，也存在第二次结果变为内网地址的窗口。

推荐为 `DIRECT` Bootstrap 配置一个校验型 `AddressResolverGroup`：

1. 委托 Netty 默认系统 resolver 调用 `resolveAll`；
2. 对全部 `InetSocketAddress#getAddress()` 执行地址策略校验；
3. 通过后返回已经 resolved 的 `InetSocketAddress`；
4. Bootstrap 使用该 resolved 地址连接，不触发第二次解析。

该 resolver 应为可复用的单例，并允许在测试中注入假的底层 resolver。不得自行增加永久 DNS 缓存，继续沿用系统/Netty现有缓存与 TTL 行为。

## 8. 建议代码改动

### 8.1 新增组件

- `proxy-worker/security/DestinationAddressPolicy`
  - 无 DNS 的 IP 字面量识别；
  - IPv4/IPv6 规范化；
  - 公网可路由地址判定；
  - 统一阻断原因。
- `proxy-worker/outbound/direct/PublicAddressResolverGroup`
  - 包装 Netty 默认系统 resolver；
  - 执行单次 `resolveAll`、校验和结果固定；
  - 对混合公网/非公网答案失败关闭。
- `PrivateDestinationBlockedException`（名称可在实现时调整）
  - 与普通 DNS/连接异常区分；
  - 供协议响应和审计识别。

### 8.2 修改现有组件

- `RouterService` 或其后的统一安全 handler
  - 对受保护入站的客户端 IP 字面量执行入口校验；
  - 不检查 `DESTINATION_OVERRIDE` 的管理员目标。
- `DirectOutboundConnector`
  - 仅在受保护入站且路由策略为 `DIRECT` 时使用校验型 resolver；
  - 使用校验后已解析的地址连接；
  - `TP_PROXY` 和可信目标重写保持原有解析/连接行为。
- `OutboundConnectorFactory`
  - 为 `DESTINATION_OVERRIDE` 增加明确分支；
  - 删除“未知策略默认直连”的失败开放行为，未知策略应阻断或抛出配置错误。
- `ProxyTunnelConnectorHandler`
  - 将安全策略阻断记录为 `POLICY_BLOCK`/`PRIVATE_ADDRESS_BLOCKED`，而不是 `NETWORK_ERROR`；
  - 记录原始目标、解析结果、入站协议、用户和路由，但不得记录凭据。
- 各 `ProtocolStrategy`
  - HTTP CONNECT：建议返回 `403 Forbidden` 并关闭连接；
  - SOCKS5：建议返回 `CONNECTION_NOT_ALLOWED_BY_RULESET`；
  - Shadowsocks、TLS SNI：无标准错误响应时关闭连接；
  - 普通 DNS 或网络失败继续沿用现有失败响应。

## 9. 对正常使用的影响

| 场景 | 预期影响 |
| --- | --- |
| 公网 IP 直连 | 无影响 |
| 公网域名 `DIRECT` | 保持一次系统 DNS 解析；增加纯内存地址校验 |
| 客户端直接访问内网 IP | 按设计阻断 |
| 普通域名解析到内网 IP | 按设计阻断 |
| 精确域名命中 `DESTINATION_OVERRIDE` 到内网 | 允许，不受影响 |
| 客户端先在本地解析目标重写域名，再把内网 IP 交给代理 | 被阻断；客户端必须将域名交给代理 |
| 透明代理访问内网 | 不受影响 |
| 内网 DNS 查询和内部域名解析 | 不受影响 |

本变更会主动终止此前依赖 SOCKS5、HTTP CONNECT 或 Shadowsocks `DIRECT` 访问内网 IP/域名的用法。此类访问需要迁移为显式域名规则加 `DESTINATION_OVERRIDE`。

## 10. 测试方案

### 10.1 地址策略单元测试

- IPv4 公网地址允许。
- RFC 1918、环回、链路本地、CGNAT、组播、保留地址阻断。
- IPv6 公网地址允许。
- IPv6 ULA、环回、链路本地、组播阻断。
- IPv4 映射 IPv6 能正确还原并阻断内网 IPv4。
- IPv6 方括号和规范化文本能正确识别。
- 非 IP 字符串不会在字面量检查阶段触发 DNS。

### 10.2 resolver 单元测试

使用可注入的假 resolver，不依赖真实 DNS：

- 单个公网结果允许并返回 resolved 地址。
- 单个内网结果返回 `PrivateDestinationBlockedException`。
- 公网与内网混合结果整体阻断。
- 空结果和解析异常按网络失败处理。
- 校验通过后只使用已解析地址，断言不会发生第二次解析。
- 多个公网 A/AAAA 结果保留 resolver 顺序。

### 10.3 路由与协议测试

- HTTP CONNECT 直接请求 `192.168.10.145:22` 被拒绝。
- SOCKS5 IPv4、IPv6 内网目标被拒绝。
- Shadowsocks 内网目标被拒绝并关闭连接。
- TLS SNI 的 `DIRECT` 域名解析到内网时被拒绝。
- `DESTINATION_OVERRIDE` 到 `192.168.10.145:22` 仍能成功。
- `TP_PROXY` 到内网目标保持现有行为。
- 策略阻断产生单条正确审计日志，不记录为普通网络错误。

### 10.4 现有测试调整

现有集成测试大量使用 `127.0.0.1` 上的 echo 服务验证 `DIRECT`，启用策略后这些用例会按设计失败。需要：

- 将验证本地 relay 能力但不验证 `DIRECT` 安全性的用例改为 `DESTINATION_OVERRIDE`；
- 使用假 resolver 和 EmbeddedChannel 覆盖 `DIRECT` 公网/内网判定；
- 保留一个真实协议链路，验证 HTTP CONNECT 和 SOCKS5 的策略失败响应；
- 更新 `docs/testing/proxy-worker-testing.md` 的核心回归矩阵。

### 10.5 研发环境验收

使用现有测试代理执行：

1. `CONNECT 192.168.10.145:22`：拒绝，不得收到 SSH banner。
2. `CONNECT my-pve-dev-server.com:22`：命中目标重写，允许收到 SSH banner。
3. 测试域名经系统 DNS 解析到内网地址并命中 `DIRECT`：拒绝。
4. 公网域名经 `DIRECT`：正常连接。
5. 审计日志能够区分策略阻断、DNS 失败和网络失败。

## 11. 上游代理的剩余风险

HTTP CONNECT、SOCKS5、Shadowsocks 和 VLESS 等上游出站通常把客户端原始域名交给上游解析。本机只连接上游服务器，无法观察上游最终解析到的目标 IP。

因此，本阶段只能保证：

- 客户端直接提交的非公网 IP 字面量在本 Worker 被阻断；
- 本机 `DIRECT` 域名解析结果受到校验；
- 管理员目标重写继续可用。

若上游代理能访问需要保护的内网，上游节点也必须部署同等安全策略。另一种方案是本机解析、校验后把固定 IP 交给上游，但会改变上游 DNS、地域解析和负载均衡语义，不纳入本阶段。

## 12. 发布与回滚

### 12.1 发布前

- 盘点所有非透明入站绑定的 `DIRECT` 路由。
- 从访问日志识别当前是否存在合法的内网 IP/域名直连。
- 为合法内部服务补充精确域名 `DESTINATION_OVERRIDE`。
- 确认目标重写域名由客户端以域名形式提交，而不是先本地解析成 IP。

### 12.2 发布顺序

1. 完成单元测试和集成测试调整。
2. 在单个 Worker 灰度发布。
3. 验证第 10.5 节场景并观察策略阻断日志。
4. 确认无合法流量误拦截后再扩大发布。

### 12.3 回滚

- 代码不涉及数据库迁移和配置格式变更，可直接回滚 Worker 版本。
- 回滚不会要求控制面或前端同步降级。
- 不建议提供默认关闭的长期安全开关，避免部署后因误配置重新开放内网直连。

## 13. 验收标准

- 非透明入站不能通过 `DIRECT` 连接任何非公网 IPv4/IPv6 地址。
- IP 字面量校验不触发 DNS。
- 域名 `DIRECT` 只解析一次，并使用通过校验的同一个地址连接。
- DNS 返回任意非公网地址时连接失败关闭。
- `DESTINATION_OVERRIDE` 可以继续连接管理员配置的内网目标。
- `TP_PROXY` 和 DNS 服务行为不变。
- HTTP CONNECT、SOCKS5、Shadowsocks、TLS SNI 均有回归覆盖。
- 策略阻断能够在审计日志中单独识别。
- 未知或不完整的路由策略不会默认回退到直连。

## 14. 待评审决策

1. 是否确认将 `TLS_SNI_TCP` 纳入与 SOCKS5、HTTP CONNECT、Shadowsocks 相同的保护范围。建议：纳入。
2. 混合 DNS 结果（同时含公网和内网）是整体阻断还是仅丢弃内网结果。建议：整体阻断。
3. HTTP CONNECT 策略阻断返回 `403`，还是保持现有统一 `502`。建议：返回 `403`，便于区分授权策略与网络故障。
4. 是否确认本阶段不处理上游代理对域名的最终解析。建议：明确记录为剩余风险，由上游节点执行同等策略。
5. 是否接受安全策略默认启用且不提供控制面开关。建议：默认启用，通过 Worker 版本回滚处理紧急兼容问题。
