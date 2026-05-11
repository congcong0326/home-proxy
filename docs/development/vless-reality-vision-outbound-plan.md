# VLESS + REALITY + Vision Outbound Plan

## Summary

第一阶段落地 `proxy-worker` 的 VLESS + REALITY + `xtls-rprx-vision` 出站能力，复用当前项目已有的 HTTP CONNECT、SOCKS5、透明代理入站与路由链路。兼容目标限定为受控 Xray REALITY Vision 服务端，不追完整 Xray/uTLS parity。

控制面配置字段按可扩展协议配置设计：不新增 `reality_config_json` 专用字段，改为通用 `outbound_proxy_config_json`，后续 VLESS、Trojan、Hysteria 等协议可以复用同一字段。

## Key Changes

- `common` 新增 `ProtocolType.VLESS_REALITY`。
- `RouteDTO` 增加 `outboundProxyConfig`，用于承载协议专属 JSON 配置。
- `control-manager` 新增 Flyway 脚本，为 `routes` 增加 `outbound_proxy_config_json JSON NULL`。
- `control-manager` 的 route create/update/get/aggregate/hash 同步 `outboundProxyConfig`。
- `proxy-worker` 新增 `VlessRealityOutboundConnector`，在 `RoutePolicy.OUTBOUND_PROXY + ProtocolType.VLESS_REALITY` 时启用。
- `frontend` 路由类型和表单增加 `VLESS_REALITY` 配置输入，提交到 `outboundProxyConfig`。

## Config Shape

通用字段：

- `outboundProxyHost`：REALITY/Xray 服务端地址。
- `outboundProxyPort`：REALITY/Xray 服务端端口。
- `outboundProxyType`：`VLESS_REALITY`。
- `outboundProxyConfig`：协议专属 JSON。

`VLESS_REALITY` 的 `outboundProxyConfig`：

```json
{
  "serverName": "example.com",
  "publicKey": "base64url-public-key",
  "shortId": "hex-short-id",
  "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "flow": "xtls-rprx-vision",
  "connectTimeoutMillis": 10000
}
```

必填字段为 `serverName`、`publicKey`、`shortId`、`uuid`。`flow` 默认 `xtls-rprx-vision`，`connectTimeoutMillis` 默认 `10000`。

## Worker Implementation

- 从 `.refenrence/proxy` 迁移 REALITY/TLS 1.3、VLESS、Vision 协议内核到 `proxy-worker` 的出站包内。
- 不迁移参考 MVP 的本地 HTTP CONNECT 入站 bootstrap；当前项目已有入站和 `ProxyTunnelRequest` 抽象。
- connector 建立到 REALITY 服务端的 Netty channel，完成 REALITY 握手、VLESS request、Vision 初始化后再通知当前入站协议连接成功。
- 后续上下行流量由 REALITY outbound handler 编码/解码 Vision/TLS/direct-mode，不使用裸 TCP relay 直接透传外层 channel。
- 失败时走现有 `relayPromise` 失败路径，并避免在日志中泄露 uuid、publicKey、shortId 等敏感配置。

## Test Plan

- worker 单测：
  - VLESS request 编码覆盖 domain、IPv4、IPv6、`xtls-rprx-vision` flow。
  - Vision codec/direct-mode 行为迁移参考 MVP 测试。
  - VLESS REALITY 配置校验覆盖缺失字段、非法 uuid、非法 shortId、公钥长度。
  - `OutboundConnectorFactory` 能为 `VLESS_REALITY` 分发新 connector。
- worker 集成测试：
  - 存在 `REALITY_*` 环境变量时连接受控 Xray 服务端。
  - 环境变量不存在时跳过。
- control-manager 测试：
  - Flyway migration。
  - route create/update/get 能保存和返回 `outboundProxyConfig`。
  - aggregate hash 包含 `outboundProxyConfig`。
- frontend 测试：
  - `RouteForm` 对 `VLESS_REALITY` 条件渲染。
  - 创建和编辑时提交/回显 `outboundProxyConfig`。

## Current Progress

- 已完成通用 `outbound_proxy_config_json` 契约、control-manager Flyway 迁移、route 聚合发布、frontend 表单和类型接入。
- 已完成 `proxy-worker` VLESS + REALITY + `xtls-rprx-vision` 出站实现，并接入 `OutboundConnectorFactory`。
- 已补充本机 xray 集成测试 `WorkerVlessRealityVisionIT`，链路为 `SOCKS5 入站 -> worker VLESS REALITY Vision 出站 -> xray VLESS REALITY Vision 入站 -> HTTPS echo`。
- 集成测试使用本地受控 xray 与 Java HTTPS echo，不依赖公网、root 权限或真实部署网络栈。
