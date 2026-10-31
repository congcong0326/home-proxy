# Development Docs

This directory keeps design and implementation notes that are useful across multiple changes.

Use it for architecture decisions, protocol design notes, and multi-step feature design. Keep test strategy and coverage tracking in `docs/testing/`.

## Documents

- [代理网关日志模型与双轨演进设计](gateway-observability-log-model-design.md)
- [DIRECT 出站目标地址安全校验设计](direct-outbound-address-safety-design.md)
- [Fake IP DNS 与透明代理联动需求](fake-ip-requirements.md)
- [Fake IP 实施任务](fake-ip-implementation-tasks.md)
- [Fake IP 部署与验证](fake-ip-deployment.md)
- [生产多 Worker 升级操作手册](production-multi-worker-upgrade-runbook.md)
- [TLS SNI 四层 TCP 入站设计](tls-sni-tcp-inbound-design.md)
- [路由与出站配置模型演进规划](route-outbound-model-roadmap.md)
- [一期 路由与出站拆分、改写表设计](route-outbound-split-phase1-design.md)
- [二期 SELECT 出站组设计](outbound-select-group-phase2-design.md)
- [UDP 代理整体规划](udp-proxy-roadmap.md)
- [一期 UDP 透明代理转发内核设计](udp-transparent-relay-phase1-design.md)
- [Worker 遗留 HTTP 调用与旧日志链路清理设计](worker-legacy-http-removal-design.md)
- [控制端数据库裁剪研发计划](controller-sqlite-migration-plan.md)：四阶段顺序、任务与验收
- [控制端数据库裁剪设计：移除 MySQL 与 ClickHouse，改用 SQLite](controller-sqlite-migration-design.md)
- [Worker 快捷部署方案大纲](worker-quick-install-outline.md)
- [日志类型与字段精简设计](telemetry-field-slimming-design.md)：ClickHouse 清理脚本 `scripts/clickhouse/telemetry-slimming-cleanup.sql` 仅手工执行、不可回滚，需所有 Worker 升级后再运行。

- [网关观测阶段 A 实施与验证](../testing/gateway-observability-stage-a.md)：独立 V1 事实、内存缓冲/ACK 上报和保留数据 E2E，现有日志与页面继续走旧链路。
