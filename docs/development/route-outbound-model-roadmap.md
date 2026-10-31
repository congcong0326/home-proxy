# 路由与出站配置模型演进规划

## 1. 背景

当前每条路由（`routes` 表、`RouteDTO`）同时包含三部分：

- 匹配条件：`rules_json`，多个条件之间是"或"关系，命中任意一条即命中该路由。
- 动作：`policy`，取值为 `DIRECT`、`BLOCK`、`OUTBOUND_PROXY`、`DESTINATION_OVERRIDE`、`DNS_REWRITING`。
- 出站参数：`outbound_proxy_type/host/port/username/password/enc_algo/config_json`。

入站通过 `inbound_route_bindings` 给每组用户配置一个有序的路由 ID 列表。worker 按顺序匹配，首个命中即停；都不命中时使用 `fallbackRouteId`。

这种模型带来两个问题：

1. 出站参数跟着路由走，同一个上游被多条路由使用时，要重复填写多次。
2. 目标重写和 DNS 改写需要"每个域名一条路由"。给 NAS 服务发布域名时，路由数量会迅速膨胀，入站的路由顺序也随之变长，难以维护。

## 2. 参考

主流代理软件都把配置拆成"入口 → 有序规则 → 出站（组）"几层：

| 软件 | 结构 | 借鉴点 |
|---|---|---|
| Clash / Mihomo | proxies、proxy-groups、rules、rule-providers | 规则只指向出站或出站组；`select` 组手动切换；`hosts` 维护域名映射 |
| sing-box | outbounds（含 selector、urltest）、route.rules、rule_set | 规则动作与出站分离；`selector` 组 |
| Xray | outbounds、routing.rules、balancers | 出站以 tag 复用，规则引用 tag |

我们与它们的区别：它们多为单机客户端，我们是多用户认证、多节点集中管控的家庭网关。所以入站与用户的绑定、集中下发、遥测都保留，只借鉴"路由与出站分离"和"手动切换组"两点。

## 3. 目标模型

```
Outbound（出站）      内置 直连 / 阻断；上游代理 HTTP / SOCKS5 / SS / VLESS Reality；DNS 上游 DNS / DoT
                      二期增加 SELECT 组（成员为若干出站，记录当前选中的成员）
RewriteTable（改写表） 有序条目：域名模式 -> host:port，按服务分组建多张
Route（路由）         条件 + 动作
                        转发：引用一个出站
                        目标改写：引用改写表，或填写固定目标
                        DNS 改写：固定目标
Inbound binding       不变：用户组 -> 有序路由 ID 列表
```

家庭典型配置可以收敛为四条路由：NAS 改写（改写表）、广告阻断（规则集 → 阻断）、科学上网（规则集 → 上游出站）、兜底（直连）。

## 4. 关键决策

| 决策 | 结论与理由 |
|---|---|
| 编译位置 | 一期、二期的新模型只存在于控制端。`AggregateConfigService` 把新模型编译回现有的 `RouteDTO` 数组下发，worker 协议和代码都不改，老 worker 可以继续使用。 |
| 改写表的定位 | 改写表既不是路由也不是出站，是由路由引用的独立资源。表中的域名就是匹配条件，所以引用改写表的路由不再另配条件。worker 只支持"或"语义，无法表达"条件且命中表"。 |
| 改写表的编译 | 每个条目展开为一条合成路由，按条目顺序占据原路由在绑定列表中的位置。合成路由 ID 使用独立号段，保证稳定、不与真实路由冲突。 |
| 出站组策略 | 家庭场景只做 SELECT（手动切换）与后续的 FALLBACK（故障切换）。轮询、hash 会让同一站点在多个出口 IP 之间跳转，容易触发风控，价值低，不做。 |
| SELECT 的生效方式 | 切换选中成员只是控制端状态变化，编译后配置哈希改变，worker 通过 `CONFIG_POLL`（默认 2 秒）拿到新配置并原子替换快照，不重启监听，已有连接不受影响。 |
| fallback 的范围 | 三期 fallback 只在同一条路由的候选出站之间发生，不能落到下一条路由。否则走代理的流量可能落到兜底直连，造成泄漏。 |

## 5. 分期

### 一期：路由与出站拆分、改写表

设计文档：[一期 路由与出站拆分、改写表设计](route-outbound-split-phase1-design.md)。

- 新增出站管理，路由改为引用出站 ID。
- 新增改写表（按服务分组建多张，保留按用户的访问隔离），只用于连接目标改写；DNS 改写保留为固定目标路由。
- 迁移现有数据，编译后的下发配置与迁移前语义等价。
- 提供"把多条改写路由合并为改写表"的工具。
- worker 不改。

### 二期：SELECT 出站组

设计文档：[二期 SELECT 出站组设计](outbound-select-group-phase2-design.md)。

- 出站新增 SELECT 组，路由可以引用组。
- 管理台一键切换组的当前成员，例如 Steam 下载在"直连"与"科学上网"之间切换。
- 可选：定时恢复、切换历史。
- worker 不改。

### 三期：路由多出站 fallback（待讨论健康检查后再设计）

方向已确定，细节待定：

- 路由的出站从单个改为有序列表（主出站 + 备用出站）。
- `RouteDTO` 新增 `fallbackOutbounds` 字段。worker 的 mapper 已设置 `FAIL_ON_UNKNOWN_PROPERTIES=false`，老 worker 会忽略该字段，只用主出站。
- worker 只改出站连接阶段，路由匹配不变：候选出站依次尝试，失败后换下一个。
- 被动熔断：出站连续失败后，在冷却期内直接跳过，冷却期结束后放少量连接重试。

开工前需要讨论清楚：

- 失败判定口径：只有出站节点本身失败（TCP 连接、TLS、认证握手失败）才触发回退，目标站点失败不触发。SOCKS5、HTTP 上游的错误应答需要区分；Shadowsocks 没有握手应答，只能识别 TCP 不通。
- 熔断参数：连续失败阈值、冷却时长、半开探测数量。
- 嗅探到的首包（`initialPayload`）在重试期间要保留，不能提前释放。
- 直连作为最后一个备选时的提示方式。
- 遥测如何记录尝试过的出站与最终使用的出站。
- UDP 不在范围内。

## 6. 不做

- 轮询、一致性 hash 等负载均衡策略。
- 按用户分别选择 SELECT 组的成员。
- 切换组时断开已有连接（需要 worker 新增管理指令，有需要再做）。
- 链式代理、订阅导入，作为独立需求另行评估。
