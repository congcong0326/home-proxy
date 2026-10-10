# Java 紧凑代码排查与格式化

排查日期：2026-10-10。扫描后端 616 个 Java 源文件（含测试，排除 `target` 构建产物），实际格式化 72 个文件。

## 原因

`TlsSniHandler`、`TlsSniServerInitializer`、`TlsSniProxyServer` 在提交 `644a3a3`（2026-09-12，`fix: protect referenced proxy resources on deletion`）中新增时已经是紧凑写法，因此不是 Maven 构建时压缩了源码。提交记录无法证明当时采用这种写法的具体原因。当前 Maven POM 与 Makefile 未配置 Java 自动格式化或格式检查。

## 修改范围

- 三个 TLS SNI 类完整展开：拆分同一行的 import、构造方法、方法体和多条语句。
- 其余 69 个文件只格式化紧凑语句或方法体所在片段，以及格式化器需要调整的相邻表达式。
- 使用 google-java-format 1.24.0 的 AOSP 模式（4 空格块缩进），不排序／删除 import，不重写长字符串，不重排 Javadoc。
- 单行数组初始化、空 lambda、普通长字符串和普通长调用不作为紧凑代码处理。
- Java token 对比确认代码与字符串字面量一致；注释文字一致，仅部分注释换行改变。未改业务逻辑，也未新增构建插件。

## 文件清单

表中行号是格式化前的位置，文件链接指向格式化后的源码。

### common：测试（1 个）

| 文件 | 格式化前行号 |
| --- | --- |
| [telemetry/TrafficClassifierV1Test.java](../../backend/common/src/test/java/org/congcong/common/telemetry/TrafficClassifierV1Test.java) | 41 |

### control-manager：生产代码（25 个）

| 文件 | 格式化前行号 |
| --- | --- |
| [clickhouse/ClickHouseAccessLogWriter.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/clickhouse/ClickHouseAccessLogWriter.java) | 167, 168, 169 |
| [clickhouse/ClickHouseAuthLogWriter.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/clickhouse/ClickHouseAuthLogWriter.java) | 38, 39 |
| [controller/ProxyWorkerController.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/controller/ProxyWorkerController.java) | 34, 42, 56 |
| [logstore/ClickHouseAccessLogStore.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/logstore/ClickHouseAccessLogStore.java) | 69, 70, 240, 241, 242, 254, 257, 258, 259, 260, 261, 262, 263, 264, 270, 272, 273, 274, 275, 276, 277, 278, 279, 283, 299, 333, 334, 335 |
| [security/JwtAuthenticationFilter.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/security/JwtAuthenticationFilter.java) | 96 |
| [security/JwtService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/security/JwtService.java) | 18, 20 |
| [security/SecurityConfig.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/security/SecurityConfig.java) | 20 |
| [service/WorkerConfigDependencyResolver.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/WorkerConfigDependencyResolver.java) | 87, 88, 89, 126 |
| [service/WorkerManagementConnectionService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/WorkerManagementConnectionService.java) | 65, 191, 217, 230, 311 |
| [service/mail/InspectionMailHandler.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/mail/InspectionMailHandler.java) | 20 |
| [service/mail/inspection/AuthLocationInspectionService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/mail/inspection/AuthLocationInspectionService.java) | 108, 160, 163, 164, 165, 180 |
| [service/mail/inspection/InspectionProperties.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/mail/inspection/InspectionProperties.java) | 36, 37 |
| [service/mail/inspection/InspectionReportService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/mail/inspection/InspectionReportService.java) | 43, 44, 124 |
| [service/mail/inspection/NetworkInspectionService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/service/mail/inspection/NetworkInspectionService.java) | 180 |
| [telemetry/ConcurrencyTimeline.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/ConcurrencyTimeline.java) | 45, 46 |
| [telemetry/CredentialDistributionService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/CredentialDistributionService.java) | 39, 40, 54, 67, 82, 94, 103 |
| [telemetry/CredentialGeoAnalysis.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/CredentialGeoAnalysis.java) | 48, 97, 107, 108, 113 |
| [telemetry/DistributionController.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/DistributionController.java) | 19 |
| [telemetry/DistributionService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/DistributionService.java) | 73 |
| [telemetry/InboundTrafficController.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/InboundTrafficController.java) | 22 |
| [telemetry/InboundTrafficService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/InboundTrafficService.java) | 53, 54 |
| [telemetry/OverviewController.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/OverviewController.java) | 16, 27 |
| [telemetry/OverviewService.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/OverviewService.java) | 201, 213, 225, 231, 249, 271, 272, 273, 277 |
| [telemetry/TelemetryController.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/TelemetryController.java) | 33, 34, 35, 40, 41, 42, 50, 54, 55, 56 |
| [telemetry/TelemetryStore.java](../../backend/control-manager/src/main/java/org/congcong/controlmanager/telemetry/TelemetryStore.java) | 83, 194, 195, 200, 202, 203, 282, 283, 298, 389, 448 |

### control-manager：测试（15 个）

| 文件 | 格式化前行号 |
| --- | --- |
| [controller/MailPreviewControllerTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/controller/MailPreviewControllerTest.java) | 20, 22, 23 |
| [service/MailSendServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/MailSendServiceTest.java) | 17, 19, 20, 21, 23, 24 |
| [service/mail/InspectionMailHandlerTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/mail/InspectionMailHandlerTest.java) | 29, 33 |
| [service/mail/inspection/AuthLocationInspectionServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/mail/inspection/AuthLocationInspectionServiceTest.java) | 34, 35, 36 |
| [service/mail/inspection/DiskInspectionServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/mail/inspection/DiskInspectionServiceTest.java) | 23, 95 |
| [service/mail/inspection/InspectionReportServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/mail/inspection/InspectionReportServiceTest.java) | 23, 24, 25, 52, 53, 54 |
| [service/mail/inspection/InspectionSmartSnapshotTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/service/mail/inspection/InspectionSmartSnapshotTest.java) | 11, 23, 37, 77 |
| [telemetry/CredentialDistributionServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/CredentialDistributionServiceTest.java) | 19, 26, 27, 45, 48, 73, 75, 76, 77 |
| [telemetry/DistributionClickHouseIT.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/DistributionClickHouseIT.java) | 42, 65, 86 |
| [telemetry/DistributionServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/DistributionServiceTest.java) | 41, 42, 63, 65, 66, 72, 74, 76, 78 |
| [telemetry/OverviewControllerTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/OverviewControllerTest.java) | 15, 16 |
| [telemetry/OverviewServiceTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/OverviewServiceTest.java) | 23, 24, 35, 53, 54, 56, 66, 67, 69, 75, 78, 79, 80, 83 |
| [telemetry/TelemetryAuditTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/TelemetryAuditTest.java) | 42, 63, 78, 79, 80, 82, 83, 98, 107, 132, 147, 149, 161, 163, 165, 167 |
| [telemetry/TelemetryBufferClickHouseIT.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/TelemetryBufferClickHouseIT.java) | 52, 72, 73, 74, 79, 83, 87 |
| [telemetry/TelemetryStoreTest.java](../../backend/control-manager/src/test/java/org/congcong/controlmanager/telemetry/TelemetryStoreTest.java) | 49, 136, 137, 138, 164, 169, 170, 171, 172, 173, 174 |

### proxy-worker：生产代码（24 个）

| 文件 | 格式化前行号 |
| --- | --- |
| [config/ProxyWorkerConfig.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/config/ProxyWorkerConfig.java) | 248, 249, 250, 251, 252, 253, 254, 255 |
| [dns/DnsQueryService.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/dns/DnsQueryService.java) | 40, 115, 116, 117, 118, 119, 120, 121, 122, 123, 217 |
| [dns/DnsUpstreamPool.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/dns/DnsUpstreamPool.java) | 85, 86, 87, 130, 141 |
| [dns/FakeIpRegistry.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/dns/FakeIpRegistry.java) | 88, 89, 90 |
| [http/ControlPlaneHttpClientFactory.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/http/ControlPlaneHttpClientFactory.java) | 55 |
| [outbound/OutboundEndpointResolver.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/outbound/OutboundEndpointResolver.java) | 132, 133 |
| [outbound/direct/DirectOutboundConnector.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/outbound/direct/DirectOutboundConnector.java) | 179, 181 |
| [protocol/tlssni/TlsSniHandler.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/protocol/tlssni/TlsSniHandler.java) | 2, 5, 6 |
| [protocol/tlssni/TlsSniServerInitializer.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/protocol/tlssni/TlsSniServerInitializer.java) | 5 |
| [security/ProxySafetyCounters.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/security/ProxySafetyCounters.java) | 12, 13, 14, 15 |
| [server/ConfigApplyResult.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/server/ConfigApplyResult.java) | 6 |
| [server/impl/TlsSniProxyServer.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/server/impl/TlsSniProxyServer.java) | 7, 8 |
| [server/netty/tls/TlsClientContextManager.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/server/netty/tls/TlsClientContextManager.java) | 24, 25 |
| [service/AggregateConfigService.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/AggregateConfigService.java) | 157, 159 |
| [service/WorkerControlService.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WorkerControlService.java) | 214 |
| [service/WorkerManagementWebSocketServer.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/service/WorkerManagementWebSocketServer.java) | 199, 200 |
| [telemetry/DnsObservation.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/DnsObservation.java) | 20, 21, 22, 23, 26, 27, 28, 29, 36, 43 |
| [telemetry/EstablishedConnectionRegistry.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/EstablishedConnectionRegistry.java) | 17, 24 |
| [telemetry/OutboundTimingHandler.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/OutboundTimingHandler.java) | 41 |
| [telemetry/SessionObservation.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/SessionObservation.java) | 47, 48, 69, 75, 143 |
| [telemetry/TelemetryBuffer.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/TelemetryBuffer.java) | 52, 81, 82, 85 |
| [telemetry/TelemetryHooks.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/TelemetryHooks.java) | 46, 50, 51, 65, 68, 113, 125, 150, 151 |
| [telemetry/TelemetryPublisher.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/TelemetryPublisher.java) | 29, 31, 32, 33, 34, 43, 50, 60 |
| [telemetry/TimingAddressResolverGroup.java](../../backend/proxy-worker/src/main/java/org/congcong/proxyworker/telemetry/TimingAddressResolverGroup.java) | 29, 30, 31, 36 |

### proxy-worker：测试（7 个）

| 文件 | 格式化前行号 |
| --- | --- |
| [integration/WorkerSessionTimingIT.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/integration/WorkerSessionTimingIT.java) | 21, 22, 23, 37, 41 |
| [integration/support/TelemetryTimingAssertions.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/integration/support/TelemetryTimingAssertions.java) | 25, 29, 30 |
| [service/WorkerManagementWebSocketServerTest.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/service/WorkerManagementWebSocketServerTest.java) | 135 |
| [telemetry/EstablishedConnectionRegistryTest.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/telemetry/EstablishedConnectionRegistryTest.java) | 14, 18, 20, 27, 31, 32 |
| [telemetry/OutboundTimingTest.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/telemetry/OutboundTimingTest.java) | 30, 32, 34, 36, 38, 40, 69, 76, 78, 98, 123 |
| [telemetry/SessionObservationTest.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/telemetry/SessionObservationTest.java) | 130, 131, 133, 141, 144, 155, 166 |
| [telemetry/TelemetryBufferTest.java](../../backend/proxy-worker/src/test/java/org/congcong/proxyworker/telemetry/TelemetryBufferTest.java) | 30, 51, 53, 78, 86, 91, 94, 95, 96 |

## 验证

- Java token、字符串字面量与注释文字一致性检查通过。
- `git diff --check` 通过。
- `make backend-test` 通过：common 15、proxy-worker 152、control-manager 150，共 317 个测试，0 失败、0 错误、0 跳过。此命令验证单元测试，不包含 Failsafe 集成测试。
