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

- [网关观测阶段 A 实施与验证](../testing/gateway-observability-stage-a.md)：独立 V1 事实、内存缓冲/ACK 上报和保留数据 E2E，现有日志与页面继续走旧链路。
