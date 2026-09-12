# TLS SNI 四层 TCP 入站设计

## 1. 目标

新增一种入站协议 `TLS_SNI_TCP`。代理接收 TCP 连接，读取连接初期的 TLS ClientHello，解析 SNI，将 SNI 转换为统一请求模型中的目标域名，然后复用现有路由流程决定连接的目标。

该入站只做协议探测和 TCP 转发，不终止 TLS、不解密 HTTPS、不校验证书，也不需要配置证书。

## 2. 处理流程

```text
TCP accept
  ↓
读取有限的初始字节
  ↓
解析 TLS ClientHello/SNI
  ├─ 失败、无 SNI 或非 TLS：直接阻断
  └─ 成功：生成 ProxyTunnelRequest
              targetHost = SNI
              targetPort = 443
              ↓
          进入现有 RouterService
              ↓
          复用域名路由和路由策略
              ↓
          建立 upstream TCP 连接并双向 relay
```

解析完成并选择路由后，后续数据按普通 TCP 字节流转发，不再参与 TLS 处理。

## 3. 管理端配置

在现有“入站配置”页面增加协议选项“TLS SNI 四层分流”。继续使用现有入站字段：

- 配置名称
- 代理节点
- 监听 IP 和端口
- 状态
- 备注
- 路由用户

对于该协议：

- `tlsEnabled` 固定为 `false`；
- `sniffEnabled` 固定为 `true`；
- 不显示证书、TLS 终止或用户认证配置；
- 不新增 SNI 专用规则编辑器；
- 无 SNI、解析失败或非 TLS 连接直接阻断。

“路由用户”使用现有 `anonymousUserId` 字段。管理员可以单独创建一个特殊用户，例如 `sni-router`，并在入站配置中选择它。客户端不会使用该用户的密码进行认证，所有连接只映射到该用户，以便复用用户绑定的路由。

该协议启用时应要求选择一个启用状态的路由用户。用户被禁用时，入站配置应被视为无效并给出提示。

## 4. 路由复用

SNI 解析结果作为现有请求的 `targetHost`，目标端口固定为 `443`。路由继续使用现有 `RouteDTO`、`RouteRule`、`RouterService` 和 `DomainMatcher`。

例如：

```text
路由 1：api.example.com  → 目标重写 → 10.0.0.11:443
路由 2：*.example.com    → 目标重写 → 10.0.0.12:443
路由 3：*                → 阻断
```

现有域名匹配已经支持精确匹配、`*.example.com`、`.example.com` 和 `*`。目标重写策略已经提供目标地址和端口字段，因此不需要新增 SNI upstream 模型。

推荐为该入站选择专用用户，并只给该用户绑定 SNI 相关路由，避免影响其他代理入站。

## 5. 代码改动范围

1. 在 `common` 的 `ProtocolType` 增加 `TLS_SNI_TCP`。
2. 更新前端入站协议选项、表单校验和协议标签。
3. 在 worker 增加 ClientHello/SNI 探测 handler。
4. 将解析出的 SNI 转换为现有 `ProxyTunnelRequest` 的目标域名，目标端口设为 `443`。
5. 复用现有 `RouterService`、域名匹配和 `DESTINATION_OVERRIDE` 出站流程。
6. 将无 SNI、解析失败、非 TLS 和无匹配路由按阻断处理。
7. 确保聚合配置、配置 hash、worker 配置刷新链路能够传递新协议。
8. 为 SNI 解析、阻断、路由匹配和 TCP relay 增加必要测试。

## 6. 明确不包含的内容

- 不新增 SNI 规则表或独立 upstream 资源。
- 不修改现有 `RouteDTO` 和路由策略模型。
- 不终止 TLS，不解密 HTTPS，不校验证书。
- 不增加证书管理、健康检查、权重、故障转移或连接池配置。
- 不改变现有 SOCKS、HTTP CONNECT、Shadowsocks 等入站的行为。

## 7. 验收标准

- 能创建、编辑、启用和禁用 `TLS_SNI_TCP` 入站。
- ClientHello 中的 SNI 能被转换为现有路由请求的目标域名。
- 精确域名和通配符域名能命中已有路由。
- `DESTINATION_OVERRIDE` 能将连接转发到指定 host:port。
- 无 SNI、非法 ClientHello 和无匹配路由的连接会被阻断。
- TLS 加密内容保持原样双向转发。
- 配置发布后 worker 能正确启动、刷新和关闭该入站。
