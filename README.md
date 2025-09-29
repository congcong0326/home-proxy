# NasProxy - 轻量级多协议智能代理

在搭建家庭 NAS 的过程中，Nginx 常被用作反向代理，为所有后端服务提供统一入口。但在实际部署中，会遇到以下问题：

- **协议兼容性限制**：部分服务采用特殊认证协议，必须为 Netty 集成额外插件才能正常访问。
- **端口封锁问题**：虽然具备公网 IP，但运营商屏蔽了常用的 443 与 8443 端口，导致访问服务时必须手动指定端口。使用 Cloudflare 虽能绕过限制，但会显著拖慢访问速度。

为解决这些问题，我开发了一个 **基于 Netty 的轻量级代理工具**，作为 Nginx 的替代方案：

- 支持 HTTP、SOCKS5、Shadowsocks 代理协议，绕过协议兼容性限制
- SOCKS5 支持 over tls，更加安全
- 单端口协议嗅探（自动识别并转发）
- 智能域名解析与路由（手动域名映射 + GeoIP 分流 + 负载策略），可将自定义域名直接转发至指定服务，从而规避端口封锁
- 双进程架构：数据转发（Proxy Core）+ 配置/看板（Management）
- 基础看板：数据流量、用户访问 TopN、应用访问 TopN、错误占比等
- 工程化：可配置、日志与审计、REST API、可扩展

## 目录
- 项目亮点
- 架构概览
- 核心能力
- 快速开始
- 文档导航
- 性能目标与调优
- 项目结构
- 路线图
- License

--

## 项目亮
- 多协议 + 单端口嗅探：一个端口自动识别 HTTP/SOCKS5/SS，降低运维复杂度
- 智能路由：基于域名规则与地理位置的智能分流，可手动映射指定 IP 或代理
- 高性能实践：Netty 异步 IO、Pooled ByteBuf、零拷贝、背压控制、连接池复用
- 双进程工程化：转发进程专注性能，管理进程专注配置、看板与持久化
- 可视化看板：可量化指标与 TopN 展示，支持异常与失败比例洞察

---

## 架构概览
![](images\proxy架构设计.drawio.png)

- Proxy Core（数据转发进程）
  - ProtocolDetector：协议嗅探（HTTP/SOCKS5/SS）
  - SmartRouter：智能路由（域名规则 + GeoIP + 负载）
  - TrafficForwarder：转发与连接复用（零拷贝、背压）
  - MetricsCollector：实时指标采集（流量/连接/错误/延迟）
- Management（配置管理 + 看板）
  - ConfigService：规则/用户/出站配置（MySQL 持久化）
  - StatisticsService：TopN 聚合（域名/用户/应用）
  - DashboardController：看板接口与页面
  - LogService：日志与审计（关键操作留痕）

配套示意图：
- 架构图（SVG）：images/architecture.svg
- 嗅探状态机（SVG）：images/sniffing-state.svg
- 路由流程（SVG）：images/routing-flow.svg
- 看板原型（SVG）：images/dashboard-wireframe.svg

---

## 控制面 ⇄ 数据面接口与交互（摘要）
- 能力与约束
  - 支持 SOCKS5/HTTPS CONNECT（可选 over TLS）与 Shadowsocks；
  - 同端口仅支持非 TLS 的 SOCKS5 + HTTPS CONNECT 组合嗅探；启用 TLS 的端口建议单协议；
  - 采用内置证书签发，无需在配置中管理证书/私钥，仅保留 tls_enabled 开关。
- 生命周期闭环
  1) 控制面创建与编辑配置 → 2) 版本化与校验 → 3) 发布或数据面订阅热更 → 4) 数据面 ACK 回执
  → 5) 指标上报（流量/连接/错误/延迟/TopN） → 6) 审计留痕与回滚。
- 存储与接口策略
  - 存储采用“多表规范化”，以保障端口唯一/组合约束、查询统计与审计；
  - 对外提供“原子配置接口”：每次提交完整配置，服务端在事务内落库并生成快照；
  - 核心接口（摘要）：
    - GET /api/configs/latest | GET /api/configs/{version}
    - POST /api/configs/validate | POST /api/configs/publish | POST /api/configs/{version}/rollback
    - Agent 交互：GET /api/agents/{id}/subscribe | POST /api/agents/{id}/ack | POST /api/agents/{id}/heartbeat
    - 指标上报：POST /api/metrics/batch
    - 只读列表（界面用）：GET /api/inbounds | /api/ss-accounts | /api/outbounds | /api/outbound-groups | /api/route-rules
- 关键对象/表（节选）
  - 配置与发布：agents、config_snapshots、config_deployments
  - 入站与用户：inbounds、inbound_protocols、inbound_users
  - Shadowsocks：ss_accounts（每用户独立端口）
  - 出站与分组：outbounds、outbound_groups、outbound_group_members
  - 路由与审计：route_rules、metrics_rollup、audit_logs
  - 建表语句与字段说明详见 docs/控制面与数据面接口交互.md
- 发布事务（SQL 摘要）
  - 在一个事务内：写入 config_snapshots → 清空现有配置表（按外键顺序）→ 批量重建当前提交配置 → 为所选 Agent 生成 config_deployments 记录。
  - 端口/组合约束、TLS/嗅探互斥、外键引用完整性在发布前做 Dry-Run 校验（详见文档示例 SQL/伪代码）。
- 前端交互建议
  - 单页多分栏（入站、SS 账号、出站/组、路由规则），统一“保存并发布”，通过原子接口提交；
  - 入站表单“动态校验”：TLS 启用仅单协议且禁用嗅探；嗅探启用时强制非 TLS；
  - 路由规则构建器：域名/IP/Geo 条件 + 动作选择 + 优先级拖拽；
  - 发布面板：dry-run 校验结果预览、按 Agent 选择发布、ACK 反馈与失败回滚。

完整规范参见：docs/控制面与数据面接口交互.md

---

## 核心能力
- 多协议支持
  - HTTP：普通代理 + HTTPS CONNECT 隧道
  - SOCKS5：无认证优先，后续可扩展用户认证与 UDP
  - Shadowsocks：AEAD 建议（演示可先实现最小子集）
- 单端口协议嗅探（首字节/首包特征）
  - HTTP：GET/POST/CONNECT/TLS ClientHello（SNI）
  - SOCKS5：0x05 + 方法列表
  - SS：端口策略 + 报文结构特征
- 智能域名解析与路由
  - 手动域名规则：exact/prefix/regex → IP 或指定 Outbound
  - GeoIP 分流：按地区选择不同的 SOCKS5/SS 出站
  - 负载策略：权重 + 最小连接数；故障回退
- 看板与统计
  - 流量、连接、错误、延迟（p50/p95/p99）
  - 用户访问 TopN、应用 TopN、域名 TopN
  - 异常比例与趋势（嗅探失败、握手失败、对端重置）

---

## 快速开始
docker 

---

## 文档导航
- 架构设计：docs/architecture.md
- 协议与嗅探：docs/protocol-design.md
- 数据库设计：docs/database-design.md
- API 规范：docs/api-specification.md
- 日志存储与审计：docs/# 日志存储与审计.md
- 看板与监控：docs/monitoring.md
- 性能与优化：docs/performance.md
- 部署与运维：docs/deployment.md

---

## 性能目标与调优（展示用）
- 目标
  - 单机稳定支撑 10k 并发连接
  - HTTP 吞吐优于 naive 代理实现 30%+
  - 嗅探失败比例 < 0.5%，握手平均耗时可视化
- Netty 调优要点
  - EventLoop 线程数 ≈ CPU 核心数或其两倍（压测微调）
  - Pooled Direct ByteBuf，降低 GC 与拷贝
  - 零拷贝（CompositeByteBuf / FileRegion）；合理聚合
  - 背压控制：Channel.isWritable + 高水位阈值
  - TCP 参数：TCP_NODELAY、SO_REUSEADDR、缓冲适配
- Benchmark 建议
  - HTTP：wrk / bombardier（吞吐、延迟）
  - SOCKS5：并发连接/请求压测
  - 指标：req/s、bytes/s、p50/p95/p99、CPU、内存

---

## 项目结构（规划）
```
project-root
  ├── common/（DTO、协议枚举、工具类、配置镜像）
  ├── proxy-worker/（Netty 服务器、嗅探、转发、DNS、路由、指标埋点）
  ├── control-manager/（HTTP API、配置推送、指标聚合、仪表盘静态文件）
  ├── scripts/（一键启动、压测脚本、演示脚本）
  ├── docs/ images/（文档与图）
```

---

## 路线图（里程碑）
- M1 核心转发闭环：HTTP/HTTPS CONNECT + SOCKS5 + 单端口嗅探；直连出站；基础指标
- M2 智能出站：DNS Cache + hosts 映射 + Geo 路由 + 上游 socks5 管理（健康检查、权重）
- M3 控制面与热更：配置 API、订阅/推送、版本/回滚；仪表盘最小可用（3屏）
- M4 Shadowsocks 与安全：SS 基本支持（单端口/多用户）、控制通道 TLS、敏感信息加密
- M5 基准测试与文档完善：压测数据入 README，补齐图表与演示脚本

---

## License
MIT