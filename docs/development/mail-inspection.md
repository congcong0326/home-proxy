# 每日巡检邮件

业务 Key：`ops.inspection`。沿用邮件网关、业务投递目标和 `mail_biz` 定时任务；已有 `ops.alert`、`ops.traffic_report` 保留。无需新增数据库迁移或 Worker 配置。

## 配置与使用

1. 在邮件网关页面选择“每日巡检”，先预览实际报告。
2. 创建 `ops.inspection` 投递目标，填写收件人并选择现有 SMTP 网关。
3. 创建 `mail_biz` 任务，业务 Key 选择 `ops.inspection`，按需要设置每天的发送时间。例如 `0 0 9 * * ?` 表示服务端时区每天 09:00。

预览使用 `POST /api/mail/preview`，请求为 `{"bizKey":"ops.inspection"}`，需要管理员权限。它生成 HTML 和纯文本，不发送邮件、不要求已有收件目标。实际投递继续使用 `POST /api/mail/send` 或定时任务；同一封巡检邮件包含 HTML / 纯文本替代正文。

每次执行以报告生成开始时间为 `to`，`from = to - 24 小时`，统一半开区间 `[from,to)`。最新状态注明采样时间。所有历史汇总保持同一窗口，跨夏令时仍是实际 24 小时；Cron 触发时区仍使用现有调度器的服务端时区。

部署配置可通过 Spring Boot 属性或对应环境变量调整：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `mail.inspection.primary-worker-id` | `default` | 外网总览的主网关，其他启用节点分别列出 |
| `mail.inspection.time-zone` | JVM 默认时区 | 正文显示时区；不改变 Cron 的触发时区 |
| `mail.inspection.auth-baseline-days` | 30 | 本窗口之前的认证历史参考长度 |
| `mail.inspection.auth-minimum-history-days` | 7 | 同账号至少有多少个可定位的历史成功认证日期才比较位置变化 |
| `mail.inspection.disk-sample-minutes` | 10 | 磁盘预期采样间隔，连续超过三倍间隔视为缺报 |
| `mail.inspection.hdd-temperature-threshold` | 50 | HDD 超温采样参考阈值，单位 ℃ |
| `mail.inspection.ssd-temperature-threshold` | 70 | SSD / NVMe 超温采样参考阈值，单位 ℃ |

例如 `MAIL_INSPECTION_TIME_ZONE=Asia/Shanghai`、`MAIL_INSPECTION_PRIMARY_WORKER_ID=default`。温度阈值应按设备规格调整。历史最小日期数应不超过基线窗口天数。

## 报告内容及统计口径

- **磁盘与采样**：所有已有采集主机与当前保留的磁盘。设备按主机 / 路径定位，以 SMART 序列号验证历史是否属于同一块物理磁盘。列最新健康、当前 / 区间最高温度、超温采样时段、采样覆盖和累计读写增量。
- **ATA 温度来源**：读取 194 号 `Temperature_Celsius` 的原始值，不让同名的 231 号厂商属性覆盖温度。没有可信温度字段时显示未提供；194 或 NVMe 实测字段的真实 0℃仍保留。
- **HDD**：重映射、待处理、不可修复扇区及 CRC 错误的累计值和新增值。Power-Off Retract 不冒充异常断电次数。
- **SSD / NVMe**：适用的寿命百分比、媒体错误；NVMe 补读 Critical Warning 和明确的 Unsafe Shutdowns。SATA 仅接受含明确语义的属性名，例如 `SSD_Life_Left`、`Lifetime_Writes_GiB`，不根据属性 ID 猜含义。缺失值不填 0。
- **网关当前状态**：启用节点的在线、最后心跳、最近启动、配置应用结果、入站数量和日志开关。没有全天心跳历史，不报告全天离线时长、重启次数或历次配置失败。
- **外网总览**：新版唯一结束汇总中 `EXTERNAL` 的成功转发上 / 下行载荷。以结束时间入账，未结束长连接不提前补算。UInt128 聚合、Java BigInteger 格式化，保留准确原始字节。主网关单独作为总览，其余节点不相加，避免多级代理重复观测。
- **连接质量**：建连成功 / 失败、策略阻断 / 客户端取消、成功建连后的传输错误。失败率为 `FAILED / (CONNECTED + FAILED)`。建连耗时 P95 仅使用已建立连接的有效测量，附样本数。主要失败分组最多列 10 组，总量来自全量聚合。
- **客户端 DNS**：按 CLIENT 及应答模式分组，列查询量、应答 / 服务错误、服务错误率及处理耗时 P95。正常 NXDOMAIN、预期 AAAA 空回答不算故障；阻断 / 取消不进入错误率分母。不推测未采集的精确日超时数或异常上游端点。
- **认证成功位置**：只使用 SUCCESS，优先按稳定用户 ID，其次已验证账号识别；内网 / 未知位置单列。将本窗口与它之前的历史比较，以同一 GeoIP 查询组件重新定位当前与历史 IP，避免旧地理分析版本产生假变化。国家 / 地区新增重点列出，城市变化仅供参考；IP 变化本身不算位置变化。历史不足显示“基线不足”。同窗口多个国家 / 地区也列出，不能据此断言账号被盗。
- **数据覆盖**：列新日志历史起点、窗口历史不足、当前 Worker 上报积压 / 累计丢弃及管理端待落库队列。零记录、查询失败、缺失测量各自说明，查询失败不伪造零值。

## 边界

磁盘增量基于窗口内可比较的累计计数，边界之前的样本仅用于辅助判断，不把跨越开始边界的增量计入当天。缺少边界、换盘、计数重置或长时间采样中断时标注不完整；无法比较则标注未知。正常报告仍受采样间隔限制，结束前最后一次采样之后的变化尚未包含。SMART 推算读写量不是实时磁盘吞吐，HDD `Total_LBAs` 按 512 B 换算。

日志只包含已经落库的事实，正常攒批也可能延迟约两分钟。Worker 与管理端都是内存缓冲，当前丢弃计数不能证明全天没有采集缺口。某一巡检项查询失败时其余项继续生成，并在该项写明无法判断。报告不包含主动连通性探测或文件系统剩余空间。

## 实现入口

- `service/mail/InspectionMailHandler`：业务 Key 注册、主题与双正文。
- `service/mail/inspection`：统一窗口、SMART 解析、独立 V1 汇总、成功认证位置比较与正文渲染。
- `InternalMailController`：管理员预览 API；`MailSendService`：MIME 替代正文。
- `frontend/src/pages/MailGateway.tsx`：巡检卡片、投递目标、预览和现有定时配置。

## 验证与回退

验证包括统计窗口 / 夏令时、缺失与零值、换盘 / 计数重置、超 UInt64 流量、失败率分母、独立 DNS 应答模式、账号历史边界、位置未知 / 基线不足、HTML 转义、双 MIME 正文、预览不发送与旧定时任务执行路径。

2026-10-10 开发验证结果：

- `common` 15 项、`control-manager` 146 项后端测试通过，失败 / 错误均为 0。
- `MailGateway.test.tsx` 3 项前端测试通过，覆盖无收件目标预览、关闭后迟到响应、失败状态；未调用发送接口。
- `make package` 通过；磁盘边界修正后再次运行 `make backend-build` 通过。构建保留已有 ESLint / Maven shading 警告。
- 对 `192.168.10.85` 的 MySQL / ClickHouse 执行只读查询，使用新巡检服务生成含 5 块磁盘、2 个节点的 HTML / 纯文本报告。SMART 历史与新 SQL 已验证；实际服务器的管理端运行时队列未接入本地验证，按未知展示。没有调用 SMTP。
- Playwright 检查桌面 1440 px、手机 390 px：七个巡检章节、纯文本标签、沙箱预览、卡片 / 正文无横向溢出均通过，页面异常与发送请求均为 0。浏览器 API 使用响应夹具，预览正文来自前述真实数据报告；这不等同于服务器已部署新预览 API。截图和检查记录保存在 `/tmp/home-proxy-inspection-browser/`。

首次开发验证的真实数据报告保存在开发环境 `/tmp/home-proxy-inspection-real-data/report.html` 和 `report.txt`，包含设备 / 来源信息，不入库或提交仓库。上述验证当时未部署，也未自动创建或启用定时任务。该首次报告中的 HDD 温度存在下述解析错误，不应作为温度判断依据。

随后收到巡检测试邮件中 `nas/sda`、`nas/sdb` 温度为 0℃的反馈。服务器最新原始 SMART 中，194 号 `Temperature_Celsius` 均为 35，而后续同名的 231 号属性均为 0；原巡检解析按属性名存储，导致后者覆盖前者。采集数据和旧磁盘详情温度正常，错误同时影响巡检当前温度、区间最高温度和超温统计。

修正后新增 4 项回归测试，覆盖重复名称 / 反序排列、仅有厂商属性时缺失、真实零温度，以及报告当前 / 最高 / 超温 / 覆盖率。`common` 15 项、`control-manager` 150 项测试全部通过。另以服务器只读获取的两块 HDD 各 144 条窗口采样和 1 条边界采样验证新服务，温度与已有 `detail_json` 逐项统计结果一致：两盘当前 35℃、最高 37℃、超温 0 个时段、覆盖 144/144。修正后的磁盘报告位于 `/tmp/home-proxy-temperature-verification/report.html` 和 `report.txt`。此次排查未修改服务器数据、未替换容器、未发送邮件；更新管理端后，保留的原始 SMART 历史会自动用于正确的巡检统计。

上线可只更新管理端（含前端），Worker 无需更新。现有邮件目标和任务不自动迁移，也不自动启用巡检发送。回退可停用 `ops.inspection` 定时任务并恢复管理端旧版本，无需删除日志或磁盘历史。
