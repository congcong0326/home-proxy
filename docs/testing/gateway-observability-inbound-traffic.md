# 入站本月流量迁移（C1）

2026-10-09 按用户要求，将入站配置的“本月流量(上/下行)”默认查询切换到新日志表。

## 查询与统计口径

- 新增 `GET /api/observability/v1/traffic/inbound/{inboundId}/month`，必填 `workerId`，可选 `month=yyyy-MM`、`timeZone`；前端传浏览器时区，默认月份按该时区计算。API 未传时区时默认 `Asia/Shanghai`。
- `InboundTrafficService` 直接查询 `telemetry.proxy_session_log_v1 FINAL`，按节点、入站和月初至下月初的半开区间聚合，不受日志审计分页上限影响。`occurred_at` 为汇总结束时间，跨月长连接的全部载荷计入结束月份，正在进行的连接尚未计入。
- 上传对应 `upload_bytes`，下载对应 `download_bytes`；不筛掉失败／异常连接中实际转发的载荷。不使用 DNS、事件或认证记录累计流量。
- 返回四分类的上／下行与总量；无样本分类为零，空分类归 `UNKNOWN`。聚合前提升为 UInt128，API 以十进制字符串返回，避免 UInt64 求和溢出和 JavaScript 大整数精度损失。页面使用近似换算单位，悬停同时提供精确总上／下行字节数。
- 列中保留上／下行总量，悬停展示内网目标重写、外网、其他内网、未知分类和总量。按用户要求移除最早保留时间和统计口径文案；API 的历史起点字段及查询范围保持原契约，不回填新日志启用前的数据。
- 查询关闭或失败返回 503，页面显示“查询失败”；无样本返回零和空历史起点，两种情况均不会自动查询旧表。

## 回退与影响范围

- 页面“流量统计”默认选择“新日志”，可显式切换“旧日志（回退）”，下一次进入页面仍默认新日志。
- 旧 `/api/logs/traffic/inbound/{inboundId}/month`、`LogService`、旧存储、旧采集均保留。回退时仅查询旧来源，不叠加新旧数据。
- 配置 CRUD、用户／路由绑定保持原数据源与行为。切换统计来源不重新加载配置；切换、刷新或翻页后丢弃已失效的统计响应，避免旧响应覆盖当前结果。
- 本批不迁移其他旧表消费者，不清理旧代码或数据。源码修改及前端构建完成，本记录不表示已更新运行中的容器。

## 验证

- 后端 `InboundTrafficServiceTest`、`InboundTrafficControllerTest`、`OverviewServiceTest`、`TelemetryStoreTest`、`TelemetryAuditTest`：25 项通过。覆盖节点／入站隔离、UTC 月份边界、夏令时月份、大于 UInt64 的累计数、四分类、空历史、无效输入、查询关闭和 HTTP 状态。
- 前端 `InboundManagement.test.tsx`：9 项通过。覆盖新来源默认查询、各节点参数、分类与历史详情、空样本不回填、失败提示、显式旧源回退及配置页面原有行为。
- `npm --prefix frontend run build` 通过；严格 `CI=true` 构建被已有的 `MailGateway.tsx` 与 `UserManagement.tsx` lint 警告阻断，本次入站修改没有新增 lint 警告。
- 对本地保留的 ClickHouse 日志进行只读核对：`home-01`、入站 `6`、上海时区 `2026-10`，以唯一存储键手工分组计算的结果与 `FINAL` 聚合一致。外网上／下行各 480 字节，内网重写各 432 字节，未知各 384 字节，总上／下行各 1296 字节，总量 2592 字节。未新增、删除或改写事实数据。

复验命令：

```sh
mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -pl control-manager -am test -DskipTests=false -Dtest=InboundTrafficServiceTest,InboundTrafficControllerTest,OverviewServiceTest,TelemetryStoreTest,TelemetryAuditTest -Dsurefire.failIfNoSpecifiedTests=false
CI=true npm --prefix frontend test -- --watch=false --runInBand InboundManagement.test.tsx
npm --prefix frontend run build
```
