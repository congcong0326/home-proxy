# NasProxy - 轻量级多协议智能代理

在搭建家庭 NAS 的过程中，Nginx 常被用作反向代理，为所有后端服务提供统一入口。但在实际部署中，会遇到以下问题：

- **协议兼容性限制**：部分服务采用特殊认证协议，必须为 Netty 集成额外插件才能正常访问。
- **端口封锁问题**：虽然具备公网 IP，但运营商屏蔽了常用的 443 与 8443 端口，导致访问服务时必须手动指定端口。使用 Cloudflare 虽能绕过限制，但会显著拖慢访问速度。

为解决这些问题，我开发了一个 **基于 Netty 的轻量级代理工具**，作为 Nginx 的替代方案：

- 支持 HTTP、SOCKS5、Shadowsocks 代理协议，绕过协议兼容性限制
- SOCKS5 支持 over tls，更加安全
- 智能域名解析与路由（手动域名映射 + GeoIP 分流 + 负载策略），可将自定义域名直接转发至指定服务，从而规避端口封锁
- 双进程架构：数据转发（Proxy Core）+ 配置/看板（Management）
- 基础看板：数据流量、用户访问 TopN、应用访问 TopN、错误占比等
- 工程化：可配置、日志与审计、REST API、可扩展

---

## License
MIT

## Docker

Docker 部署命令与配置说明见 [docs/development/docker-deployment.md](docs/development/docker-deployment.md)。当前镜像策略是先运行 `make package` 生成产物，再用 Docker 运行 JAR。
