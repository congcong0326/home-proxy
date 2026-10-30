# 控制端数据库裁剪设计：移除 MySQL 与 ClickHouse，改用 SQLite

> 状态：设计阶段，尚未实施。本文是控制端"轻量部署"的第一项工作；Worker 侧快捷部署见《[Worker 快捷部署方案大纲](worker-quick-install-outline.md)》，两者互不阻塞。
>
> **实施顺序与范围以《[控制端数据库裁剪研发计划](controller-sqlite-migration-plan.md)》为准**：先清扫历史无用代码，再做 MySQL → SQLite（元数据）与备份数据转换，ClickHouse（本文 §5）延后到最后阶段；空表和遗留表（`http_*`、`worker_metric_samples`、`agg_*` 等）经确认后不进入新基线，不迁移。本文 §8 的阶段划分已被研发计划取代，其余设计内容仍然有效。
>
> 依据：2026-10-30 线上 MySQL 备份（`.tmp/home-proxy-mysql-20261030-082738.sql.gz`，位于被 `.gitignore` 忽略的目录，内含密码哈希、凭据和令牌，**不得提交、不得外传**）以及当前 `control-manager` 源码。凡标注"待原型确认"的结论，必须在 §8 阶段 1 的原型中验证后才能作为定论。

## 1. 背景与目标

### 1.1 背景

控制端目前由 `mysql:8.4`、`clickhouse-server:24.8`、`control-manager` 三个容器组成（`deploy/docker/docker-compose.control.yml`），启动顺序依赖两个数据库的健康检查。实际数据规模很小：

- 访问日志约每天 1 万条，一年约 365 万条。
- MySQL 备份中业务数据合计仅几十 KB 的结构化数据，体量最大的是 `disk_monitor_sample`（约 5,200 行，含 `raw_smart_output` 长文本）和 `rule_set_payload`（19 行，含大 JSON 规则项）。

这个量级用不到列式分析引擎，也不需要独立数据库服务。项目最初用过集合接口的嵌入式库，因为没有 SQL、聚合分析难写才重构；问题在于"没有 SQL"，不在于"嵌入式"。SQLite 提供完整 SQL（窗口函数、CTE、`COUNT(DISTINCT)`），不会重复那个问题。

### 1.2 目标

1. 控制端只剩 `control-manager` 一个容器，一个数据卷，无外部数据库依赖。
2. 移除 MySQL 与 ClickHouse 的运行时依赖、驱动、配置、compose 服务和健康检查链。
3. 以线上备份为准，重写一份 SQLite 基线 schema，废弃历史 Flyway 增量脚本。
4. 提供一次性 MySQL → SQLite 导入脚本，保证配置类数据无损迁移，可在备份上反复演练。
5. 日志与遥测（会话、DNS、认证）改为写入 SQLite，既有查询接口行为保持不变。

### 1.3 非目标

- **不迁移 ClickHouse 历史日志**。切换后日志从零开始累积；旧 ClickHouse 数据卷按保留策略自行处理。
- 不改变 Worker 与控制端之间的 WSS 协议、遥测 V1 记录格式和 ACK 语义。
- 不引入多控制端实例或高可用；SQLite 单进程单写者是明确的前提。
- 不改动前端页面和 API 契约（响应字段、分页、筛选语义保持一致）。

## 2. 现状盘点

### 2.1 MySQL（元数据）

持久层为 Spring Data JPA + Hibernate，`ddl-auto=none`，由 Flyway 管理结构（`V1`、`V13`–`V37`，共 26 个脚本，`flyway-mysql`），方言 `MySQL8Dialect`。32 个 `@Entity`，主键全部 `GenerationType.IDENTITY`。查询绝大多数为 JPQL；原生 SQL 里未发现 `ON DUPLICATE KEY`、`GROUP_CONCAT`、`DATE_FORMAT`、`JSON_*` 等 MySQL 专有函数，仅有少量 `CONCAT(`、`INTERVAL` 和 6 处窗口函数 `OVER (`。

备份中的 37 张业务表（另有 `flyway_schema_history`）：

| 分组 | 表 | 备份中有数据 |
|---|---|---|
| 管理员认证 | `admin_user`、`admin_login_history`、`admin_token_blacklist` | 是 |
| 代理配置 | `users`、`routes`、`rule_set`、`rule_set_payload`、`inbound_configs` | 是 |
| 代理配置 | `rate_limits`、`dns_rule` | 否（空） |
| Worker | `proxy_worker`、`worker_status`、`worker_tasks` | 是 |
| Worker | `worker_metric_samples` | 否（空） |
| HTTP 网关 | `http_gateways`、`http_virtual_hosts`、`http_tls_certificates`、`http_tls_certificate_versions` | 否（空） |
| 运维 | `disk_monitor_host`、`disk_monitor_sample`、`disk_monitor_setting`、`mail_gateway`、`mail_target`、`mail_send_log`、`scheduled_task`、`wol_configs` | 是 |
| 聚合（旧） | `agg_minute_traffic_stats` 及 `agg_day_*`、`agg_month_*` 共 11 张 | 否（空） |

要点：

- 备份中 11 张 `agg_*` 聚合表全部为空，说明聚合已不在 MySQL 里累积。但 `ClickHouseAccessLogStore`、`LogService`、`MinuteTrafficStatsCleanupTask` 仍引用这些实体和仓库。**这批表与代码是否还有真实使用，需要在阶段 0 查清**：确实无用则连同实体、仓库、清理任务一并删除，不进入新基线；仍有用则保留。
- 管理员账号不是迁移脚本种子，而是首次访问时由 `AdminAuthService` 在 `admin_user` 为空时通过初始化接口创建。导入后 `admin_user` 有数据，因此该初始化入口自然关闭。
- 种子数据来源有两处：`DataInitializer`（启动时经 service 层创建匿名用户、兜底路由、默认规则集，与数据库种类无关）和迁移脚本 `V21`（`INSERT` 了 `default` worker，并从 `worker_status` 回填 `proxy_worker`）。新基线必须处理后者：`default` worker 要么写入基线，要么挪到 `DataInitializer`，二选一，避免导入后与种子冲突。

### 2.2 备份中的 MySQL 类型特征

| MySQL 类型 | 数量 | 说明 |
|---|---|---|
| `json` | 12 列 | 另有实体中 `columnDefinition = "JSON"` 约 8 处、`@JdbcTypeCode(SqlTypes.JSON)` 5 处 |
| `tinyint(1)` | 19 列 | 布尔 |
| `datetime` / `datetime(6)` | 大量 | JDBC 连接串 `serverTimezone=UTC`，库内按 UTC 无时区存储 |
| `binary(32)` / `varbinary(12)` | 各 1 列 | 令牌哈希等二进制 |
| `longtext` / `mediumtext` | 各 1 列 | `raw_smart_output` 等 |
| `timestamp` | 1 列 | |
| 外键 | 多处，含 `ON DELETE CASCADE` / `RESTRICT` | |
| 字符集排序规则 | `utf8mb4_unicode_ci`、`utf8mb4_0900_ai_ci` 混用 | **见 §4.6**，大小写不敏感的唯一键会改变行为 |

### 2.3 ClickHouse（日志与遥测）

- 遥测 V1 三张表由 `TelemetrySchema` 反射实体字段生成：`proxy_session_log_v1`、`dns_query_log_v1`、`credential_auth_log_v1`。
- 旧表 `access_log`、`auth_log` 由 `AccessLogTableInitializer` 创建，`/api/logs/access` 等接口仍在使用（e2e 脚本有调用）。**旧表是否仍被写入，需在阶段 0 确认**；若 Worker 已不再上报旧日志，则只迁移读取路径或直接删除。
- 查询层：`logstore/*`、`telemetry/*`（`OverviewService`、`DistributionService`、`InboundTrafficService`、`CredentialDistributionService`、`ConcurrencyTimeline`、`TelemetryStore`），以及 `resources/clickhouse/overview-concurrency.sql`（窗口函数 + `Int128` 的历史并发聚合）。
- 用到的 ClickHouse 特性：`FINAL`（17 处，去重合并语义）、`multiIf`、`countIf`/`sumIf`、`intDiv`、`toStartOfMinute`、`toUnixTimestamp`、`any`、`unique`、`LowCardinality`（20 处，仅建表）、`MergeTree`/`PARTITION BY`/`TTL`（仅建表）。未发现分位数、数组函数、`argMax` 一类难以替代的特性。
- 写入侧：Worker 经 WSS 上报，控制端内存缓冲（`observability.buffer-*`，批量阈值 1000 条或最长 120 秒）后批量落库，并向 Worker 回 ACK。**ACK 语义依赖"落库成功后才确认"**，迁移后必须保持。

### 2.4 启动依赖与外围

- `docker-compose.control.yml`：`control-manager` `depends_on` MySQL 与 ClickHouse 的 `service_healthy`。
- `scripts/e2e/` 与 `docs/testing/` 里的两个临时栈 compose（`transparent-proxy`、`direct-outbound-address-safety`）各自起了 MySQL + ClickHouse；后者的断言直接调用 `clickhouse-client` 查审计记录。
- 读取 ClickHouse 事实的验证：`gateway-observability-overview/concurrency/…` 系列 e2e，以及单元/集成测试 `DistributionClickHouseIT`、`TelemetryBufferClickHouseIT`、`ClickHouseAccessLogStoreTest`。
- `control-manager` 默认 `skipTests=true`，仓库层几乎没有自动化回归保护。这是本次改造最大的质量风险（见 §10）。

## 3. 总体设计

### 3.1 两个 SQLite 文件

| 文件 | 内容 | 访问方式 | 特性 |
|---|---|---|---|
| `home-proxy.db` | §2.1 全部配置与运维元数据 | JPA/Hibernate + Flyway | 体量小、数据珍贵、必须备份 |
| `telemetry.db` | 会话、DNS、认证遥测（及旧访问日志，若保留） | 直接 JDBC（沿用现有 `ClickHouseJdbcClient` 的使用方式） | 体量随时间增长、可按保留期清理、丢失可接受 |

分开的理由：

1. 备份与恢复策略不同：元数据库要定期备份，日志库丢了只是丢历史。
2. 写入模式不同：日志是批量突发写入，配置是零星小事务；分开后互不争抢 SQLite 的单写者锁。
3. 清理与压缩（`DELETE` + `VACUUM`）只作用于日志库，不会锁住配置库。
4. 现状本来就是两套访问层（JPA 与 JDBC），拆分最贴合现有代码结构，改动边界清晰。

代价是两个文件之间没有事务一致性，也不能跨库 JOIN。现状中 ClickHouse 与 MySQL 本来就是两个独立的库，推断遥测查询里需要用户名、入站名等元数据的地方已经在 Java 层分别查询后合并，因此不会引入新的限制；**此推断尚未逐处核实**，阶段 0 需确认，若存在必须跨库的场景再重新评估。

### 3.2 数据目录与配置

统一数据目录 `/data`（容器内）：

```
/data/home-proxy.db        元数据
/data/telemetry.db         遥测
/data/backups/             元数据在线备份
```

配置项（环境变量可覆盖，具体命名在实现时与现有风格对齐）：数据目录、遥测保留天数、备份保留份数与周期。移除 `clickhouse.*`、`spring.datasource.*`（MySQL）、`logs.persistence`。

### 3.3 SQLite 运行参数

每个连接必须设置，并在原型中验证生效：

- `journal_mode=WAL`、`synchronous=NORMAL`：读写互不阻塞，写入吞吐与崩溃安全的常用平衡点。
- `foreign_keys=ON`：SQLite 默认关闭外键，必须每连接显式开启，否则 `ON DELETE CASCADE/RESTRICT` 全部失效。
- `busy_timeout`：写冲突时等待而不是立即报 `SQLITE_BUSY`。
- 写事务使用 `BEGIN IMMEDIATE`：避免"先读后升级为写"在 WAL 下的死锁式失败。
- 连接池：写连接收敛为 1（或极小值），读连接可适度放开。具体配置待原型确认。

### 3.4 时间与数值的存储约定

- **元数据库时间**：建表类型统一为 `TEXT`（`yyyy-MM-dd HH:mm:ss.SSSSSS`，UTC，与现状 `serverTimezone=UTC` 一致），按字典序即时间序，便于范围查询与人工查看。Hibernate 对 `LocalDateTime` 的绑定格式是否与此一致，**待原型确认**；不一致则统一改用 `AttributeConverter`。
- **遥测库时间**：存 UTC 微秒级 `INTEGER`（epoch 微秒）。现有概览并发查询以 UTC 微秒作为参数传递且要求微秒精度，整数存储天然对应，范围比较和分桶（整除）最简单。64 位整数足够：31 天窗口 × 约 1 万并发的"并发 × 时长"积分上界约 2.7e16 微秒，低于 9.2e18，不需要 `Int128`。
- **大数**：现有 e2e 验证过 9,007,199,254,740,993 字节（超出 JS 安全整数）的无损传输；SQLite `INTEGER` 为有符号 64 位，字节计数足够。
- **布尔**：`INTEGER`（0/1）。
- **JSON 列**：存 `TEXT`。Hibernate 的 `SqlTypes.JSON` 在 SQLite 方言下能否开箱即用**待原型确认**；不行就对约 12–20 个字段统一改为 `TEXT` + `AttributeConverter`，改动机械但数量不少，需在原型阶段估出准确数字。
- **二进制**：`BLOB`。

## 4. 元数据库设计

### 4.1 基线 schema

- 新建单个 `db/migration/V1__baseline.sql`（SQLite 方言），**删除**旧的 `V1`、`V13`–`V37`。`flyway-mysql` 依赖换为 SQLite 对应支持，确认所用 Flyway 版本支持 SQLite（`flyway-core` 内置，待原型确认）。
- 来源以"实体类 + 备份中的真实 DDL"交叉核对：以备份 DDL 为准确定列、索引、默认值；以实体注解确定 JPA 的实际期望。两者不一致的列逐项列出，由人确认后再落定。
- `flyway_schema_history` 不迁移，由新 Flyway 在新库自行创建。
- 表与列名、索引名保持原样，使 JPQL 与原生 SQL 无需随名称改动。

### 4.2 类型映射规则

| MySQL | SQLite | 备注 |
|---|---|---|
| `bigint NOT NULL AUTO_INCREMENT` 主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | 保留 `AUTOINCREMENT`，防止删除后主键复用 |
| `int`、`smallint`、`bigint`、`tinyint` | `INTEGER` | |
| `tinyint(1)` | `INTEGER` | 布尔，可加 `CHECK (x IN (0,1))` |
| `varchar(n)`、`text`、`mediumtext`、`longtext` | `TEXT` | 长度约束丢失，由应用层校验保持 |
| `datetime`、`datetime(6)`、`timestamp` | `TEXT` | 见 §3.4 |
| `json` | `TEXT` | 可选 `CHECK (json_valid(x))` |
| `binary(n)`、`varbinary(n)` | `BLOB` | |
| `DEFAULT CURRENT_TIMESTAMP(6)` | `DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now'))` | 精度到毫秒，需确认业务是否依赖微秒；多数情况由实体显式赋值 |
| `ON UPDATE CURRENT_TIMESTAMP` | 去除，由应用层 `@PreUpdate` 或显式赋值 | 备份中出现 2 处，需核对实体是否已自行维护 |
| `ENGINE=`、`CHARSET`、`COLLATE` | 删除 | |
| 外键 | 建表时内联声明 | SQLite 不能在已有表上 `ADD/DROP CONSTRAINT`，基线一次写对 |

### 4.3 外键

基线内联全部外键并保留原有 `ON DELETE` 行为（备份里可见 `CASCADE` 与 `RESTRICT`）。必须配合 §3.3 的 `foreign_keys=ON`。导入脚本结束时执行 `PRAGMA foreign_key_check` 作为验收项之一。

### 4.4 种子数据

- `DataInitializer` 保持不变。
- `V21` 里的 `default` worker：判断它是否仍有意义（备份里 `proxy_worker` 的实际内容决定）。有意义则在 `DataInitializer` 里以幂等方式补齐（`INSERT … ON CONFLICT DO NOTHING` 语义，经 JPA 实现为"不存在才创建"），不再放进 SQL 脚本，避免导入时与种子冲突。
- 新环境的管理员账号保持现状：首次访问初始化接口创建。

### 4.5 JPA/Hibernate 适配

- 方言改为 Hibernate 社区维护的 SQLite 方言（`hibernate-community-dialects`），版本须与当前 Hibernate 版本匹配，**待原型核对 Spring Boot/Hibernate 版本**。
- JDBC 驱动选用 `org.xerial:sqlite-jdbc`（内含各平台原生库，适合容器镜像）。
- 逐个排查原生 SQL 与 JPQL 中的非可移植写法（`limit` 写在 JPQL 末尾的用法，如 `MinuteTrafficStatsRepository` 中的 `... DESC limit 10`，需确认在新方言下的行为；`CONCAT`、`INTERVAL`、窗口函数 6 处）。
- 事务边界：长事务会占住写锁。扫描现有 `@Transactional` 方法，重点看定时任务与批量清理，必要时拆小。

### 4.6 大小写敏感性（行为变化，需决策）

MySQL 的 `utf8mb4_unicode_ci`、`utf8mb4_0900_ai_ci` 排序规则大小写与重音不敏感，因此 `uk_users_username`、`uk_admin_user_username`、`uk_users_ip_address` 等唯一约束在 MySQL 里实际是"不区分大小写唯一"，`findByUsername("Admin")` 也能命中 `admin`。SQLite 默认 `BINARY` 排序规则区分大小写，换库后：

- 用户名 `Alice` 与 `alice` 可以同时存在；
- 以不同大小写登录将找不到账号。

处理原则：对依赖该行为的列（至少 `users.username`、`admin_user.username`、`proxy_worker.worker_id` 等标识类列）在基线中声明 `COLLATE NOCASE`，使唯一约束与等值查询保持原有语义；逐列核对由阶段 2 完成，列清单在基线评审时确认。`NOCASE` 仅覆盖 ASCII，对本项目的标识符足够。导入时需检查是否存在仅大小写不同的重复值（理论上 MySQL 唯一键已阻止，导入前仍做校验）。

## 5. 遥测库设计

### 5.1 表结构

沿用 `TelemetrySchema` 的"实体字段反射生成列"思路，改为生成 SQLite 类型：

| ClickHouse 类型 | SQLite |
|---|---|
| `String`、`LowCardinality(String)`、`Nullable(...)` | `TEXT`（可空对应 `NULL`） |
| `Int*`、`UInt*` | `INTEGER` |
| `DateTime64(6)` 等时间类型 | `INTEGER`（epoch 微秒） |
| `Array(...)`（`List.class` 字段） | `TEXT`（JSON 数组） |

- 必填列（`record_id`、`worker_id`、`worker_instance_id`、`occurred_at`、`schema_version`）声明 `NOT NULL`。
- 排序键对应 SQLite 的索引：以时间 `occurred_at` 为首列，配合 `worker_id`、`inbound_id`、`user_id` 等常用过滤列建索引（旧表 `AccessLogTableInitializer` 的排序键为 `ORDER BY (ts, worker_id, inbound_id, user_id)`，可作参考；V1 三张表的实际排序键以 `TelemetrySchema` 为准，阶段 0 核对）。具体索引集合依据 §5.3 的查询清单确定，避免盲目多建拖慢写入。
- 建表由 Java 初始化器 `CREATE TABLE IF NOT EXISTS` 完成，结构版本记录在 `PRAGMA user_version`，后续演进以递增版本号 + 显式迁移步骤处理。遥测库丢失可重建，不需要 Flyway。

### 5.2 去重语义（`FINAL`）

现状依赖 ClickHouse 合并表的去重：同一 `record_id` 重发只保留一条，首次 `received_at` 不变，同 ID 内容冲突则拒绝（见《[网关观测阶段 A](../testing/gateway-observability-stage-a.md)》的 E2E 结论）。新实现：

- `record_id`（或现有的唯一存储键，需核对）作主键/唯一索引；
- 写入使用 `INSERT … ON CONFLICT DO NOTHING`；
- 冲突时比较内容哈希：相同视为重复重发（幂等成功，仍向 Worker 回 ACK），不同则按现有"冲突拒绝"路径处理；
- 所有 `FINAL` 查询去掉 `FINAL`，因为写入时已经保证唯一。

17 处 `FINAL` 的具体用途需在阶段 0 逐处标注，确认没有依赖"多版本取最新"的 `ReplacingMergeTree` 语义（那种情况需要改成 upsert 而非忽略）。

### 5.3 查询改写清单

| 现有 | 改写 |
|---|---|
| `multiIf(...)` | `CASE WHEN … END` |
| `countIf(c)`、`sumIf(x, c)` | `SUM(CASE WHEN c THEN … ELSE 0 END)` |
| `intDiv(a, b)` | `a / b`（整数相除） |
| `toStartOfMinute(ts)` | 对 epoch 微秒做整除取整分桶 |
| `toUnixTimestamp`、`DateTime64` 参数 | 直接使用 epoch 微秒整数 |
| `any(x)` | `MAX(x)` 或 `MIN(x)`（按语义取） |
| `unique` / `uniq` | `COUNT(DISTINCT x)` |
| `FINAL` | 去除（见 §5.2） |
| `overview-concurrency.sql`（窗口函数 + `Int128`） | 保留算法（事件差分 → 窗口累加），改用 64 位整数与 SQLite 窗口函数；这是改写中最硬的一块，须与现有独立算法（`gateway-observability-concurrency-e2e.py` 中的区间交集对照算法）逐场景比对 |

查询参数目前通过 `TrafficScopeSql`、`TelemetryQueryRequest` 等拼装，改写时同时检查 SQL 注入面（保持参数化，不拼接用户输入）。

### 5.4 写入路径

保持内存缓冲 + 批量落库 + 落库成功后 ACK 的现有流程，仅替换最后一步的存储实现：

- 一次批量用一个事务（`BEGIN IMMEDIATE` … `COMMIT`），1000 条一批对 SQLite 毫无压力。
- 落库失败（含 `SQLITE_BUSY` 超过超时）不得 ACK，沿用现有失败重试与背压逻辑。
- `TelemetryBufferClickHouseIT` 的核心断言（持久 ACK、重发唯一、首次 `received_at` 不变、冲突拒绝、认证连接绑定 Worker、大数字无损）全部迁移为 SQLite 版本，作为遥测实现的验收测试。

### 5.5 保留期与空间回收

- 配置遥测保留天数（建议默认 90 天，可配置）。
- 定时任务按 `occurred_at` 批量 `DELETE`（分批，避免长事务），之后执行 `PRAGMA incremental_vacuum` 或低频 `VACUUM`。需在创建遥测库时就设置 `auto_vacuum=INCREMENTAL`（建库后再改需要 `VACUUM`）。
- 旧的 `scripts/clickhouse/telemetry-slimming-cleanup.sql` 随 ClickHouse 一并废弃。

## 6. 数据导入脚本

### 6.1 定位

一次性、可重复演练的离线工具，`scripts/migration/mysql-to-sqlite.py`（路径待定）。**永不就地修改**：输入是 MySQL 备份，输出是一个全新的 `home-proxy.db`，失败就删掉重来。

### 6.2 流程

1. 用临时 `mysql:8.4` 容器还原备份（`zcat backup.sql.gz | mysql …`），容器与数据卷在结束时销毁。选择"还原再读取"而不是直接解析 `.sql` 文本，是为了让 MySQL 自己处理转义、字符集、`json` 和二进制的十六进制字面量，避免手写解析器出错。
2. 脚本连接临时 MySQL，用 `INFORMATION_SCHEMA` 取表清单与列类型。
3. 创建空的 SQLite 文件，执行 `V1__baseline.sql`（与应用使用的是同一份脚本，避免两处 schema 漂移）。
4. 按外键依赖顺序逐表复制，单个大事务，`executemany` 批量插入；导入期间关闭外键检查，结束后统一校验。
5. 校验（见 §6.4），全部通过后输出报告并退出码为 0。

### 6.3 转换规则

- `datetime`/`datetime(6)`/`timestamp` → `yyyy-MM-dd HH:mm:ss.SSSSSS` 文本，**按 UTC 原样输出**（备份头部为 `TIME_ZONE='+00:00'`，应用连接也是 `serverTimezone=UTC`，两者一致）。
- `json` → 取 MySQL 返回的规范化 JSON 文本（注意 MySQL 会重排键序，内容等价即可）。
- `tinyint(1)` → 整数 0/1。
- `binary`/`varbinary`/`blob` → 原始字节。
- `NULL` 保持 `NULL`，空字符串保持空字符串，二者不互转。
- 主键：保留原值；导入完成后，把每张 `AUTOINCREMENT` 表在 `sqlite_sequence` 中的值设为 `max(id)`，防止新插入的主键与已有记录冲突。
- 排除：`flyway_schema_history`（新库自有）。`admin_token_blacklist` 只导入未过期记录（或全部导入由校验决定，倾向全部导入以保持行数对账简单，过期令牌由清理任务回收）。
- `default` worker 等种子冲突：脚本不做特殊处理，冲突由 §4.4 的基线设计消除；若仍出现唯一键冲突，视为设计缺陷，让脚本直接失败并报告。

### 6.4 校验清单（全部满足才算通过）

1. 每张表的行数与 MySQL 一致（对账表打印）。
2. 对每张表的主键列做 `COUNT`/`MIN`/`MAX`，并对关键列（如 `users.username`、`routes.name`、`inbound_configs` 的端口与协议）做 `GROUP_CONCAT` 校验和。
3. `PRAGMA foreign_key_check` 无输出。
4. `PRAGMA integrity_check` 返回 `ok`。
5. JSON 列抽样与全量 `json_valid()` 检查。
6. `AUTOINCREMENT` 序列值检查。
7. 大小写重复检查（§4.6 列出的 `NOCASE` 列）。
8. 端到端：用生成的库启动新版 `control-manager`，登录管理员、打开用户/路由/入站/规则集/Worker 页面，调用 `/api/workers/{id}/effective-config`，确认 `configHash` 与切换前线上一致（配置内容未变则哈希应相同，这是"配置无损"最强的证据）。

### 6.5 依赖

脚本依赖 Python 3 与 MySQL 驱动（如 `PyMySQL`）。为不污染系统环境，放在虚拟环境或一个一次性容器中运行；脚本本身不进入控制端镜像。

## 7. 部署与运维变更

### 7.1 docker-compose

`docker-compose.control.yml` 收敛为单服务：

- 移除 `mysql`、`clickhouse` 服务及其数据卷、健康检查、`depends_on`。
- `control-manager` 挂载单个数据卷到 `/data`，环境变量只保留 JWT 密钥、端口、证书、保留期等。
- 启动时间缩短：不再等待两个数据库就绪。

### 7.2 备份

- 元数据库：定时任务调用 SQLite 在线备份（`VACUUM INTO '/data/backups/…db'`）或 JDBC 备份 API，保留最近 N 份；**禁止直接复制正在写入的 `.db`/`-wal` 文件**。
- 遥测库：默认不备份；需要时同样用在线备份。
- README 增加"备份与恢复"一节：恢复 = 停服务 → 替换 `home-proxy.db` → 启动。

### 7.3 镜像与构建

- `pom.xml`：移除 `mysql-connector-j`、`flyway-mysql`、`clickhouse-jdbc`，加入 `sqlite-jdbc`、`hibernate-community-dialects` 及 SQLite 所需 Flyway 支持。
- `Dockerfile.control-manager` 基础镜像须确认 `sqlite-jdbc` 的原生库可加载（glibc 发行版开箱可用；若使用 musl 系基础镜像需单独验证）。
- `make docker-build` / `make package` 等入口保持不变。

## 8. 实施阶段

每个阶段设验收门槛，未通过不得进入下一阶段。全部在新分支进行，不动 `master`。

### 阶段 0：盘点（只读）

- 逐处标注 17 处 `FINAL` 与 `ReplacingMergeTree` 语义；
- 核实 `agg_*` 表及相关实体/仓库/清理任务是否仍在使用；
- 核实旧表 `access_log`、`auth_log` 与 `/api/logs/access` 的实际使用情况；
- 列出所有 `@Transactional` 长事务、`@Column(columnDefinition…)` 清单、跨库 JOIN 场景；
- 核对备份 DDL 与实体注解的差异清单；
- 记录 Spring Boot/Hibernate/Flyway 版本。

**验收**：产出清单文档（可附在本文末尾），§10 中的"待确认项"全部有结论。

### 阶段 1：原型验证（门槛）

在最小范围内验证以下问题：

- SQLite 方言 + `sqlite-jdbc` 能让 `control-manager` 启动，Flyway 能跑 SQLite 基线；
- `LocalDateTime`、JSON 列、`BLOB`、布尔、`IDENTITY` 的映射行为；
- `PRAGMA` 与连接池配置生效（外键、WAL、`busy_timeout`）；
- 并发场景：配置读写与批量写入同时进行不出现 `SQLITE_BUSY`。

**验收**：核心接口（登录、用户/路由/入站 CRUD、配置发布）可用；不通过则回到 §3.4/§4.5 调整方案，或终止本项目、保持现状。

### 阶段 2：基线 schema 与导入脚本

- 编写 `V1__baseline.sql`，删除旧迁移；
- 编写并反复演练导入脚本；
- 完成 §4.6 的大小写决策。

**验收**：§6.4 全部校验通过；用导入后的库启动新版控制端，`effective-config` 的 `configHash` 与线上一致。

### 阶段 3：遥测与日志改写

- 遥测库初始化、写入、去重、保留期；
- 按 §5.3 逐类改写查询；每改一类查询，对应单元测试和 SQLite 版集成测试同步通过；
- 删除 `clickhouse` 包、依赖、`LOGS_PERSISTENCE`。

**验收**：`make backend-test`（含 `-DskipTests=false` 的 `control-manager`）通过；并发聚合与现有独立对照算法逐场景一致。

### 阶段 4：部署收敛与文档

- compose、README、`docs/code-index.md`、相关 `docs/` 更新；
- 备份任务与恢复文档；
- 临时栈 e2e 的 compose 去掉两个数据库，断言改走查询 API 或读 SQLite。

**验收**：全新环境按 README 一条命令启动，只有一个容器。

### 阶段 5：切换与回归

按 §9 的切换步骤操作，之后跑前端页面的人工回归与持久环境 e2e（重跑 `worker-proxy-e2e.sh --provision` 校对夹具）。

## 9. 切换与回滚方案

### 9.1 切换步骤

1. 在测试环境用最新备份完整演练一遍（导入 → 启动 → 页面与 e2e 回归）。
2. 选择低峰期，通知相关使用者。
3. 停止旧 `control-manager`（此时 Worker 的 WSS 断开，遥测事实在 Worker 侧缓冲等待 ACK；缓冲容量和超限后的行为需在阶段 0 对照 Worker 代码确认，停机窗口尽量控制在分钟级，超出缓冲能力的遥测可能丢失，属可接受损失）。
4. 对 MySQL 做最后一次 `mysqldump`，**保留旧 MySQL/ClickHouse 容器与数据卷不动**。
5. 运行导入脚本生成 `home-proxy.db`，核对 §6.4。
6. 把 `home-proxy.db` 放入新数据卷，启动新版控制端。
7. 控制端自动重连各 Worker，核对每个 Worker 的 `configHash` 与状态；观察遥测写入与 ACK。

### 9.2 回滚

在运行新版本的头一段观察期内（建议至少一周）保留旧 MySQL/ClickHouse 容器与数据卷，回滚 = 停新控制端 → 用旧 compose 与旧镜像启动旧栈 → Worker 自动重连旧控制端。新版本期间产生的配置变更不会回流旧库，因此观察期内**应冻结配置变更**；若已发生变更，需手工补回。观察期结束并确认稳定后，再手工清理旧容器和数据卷。

## 10. 风险与待确认项

| 风险 | 影响 | 对策 |
|---|---|---|
| 大小写语义变化（§4.6） | 登录与唯一约束行为改变 | 基线 `COLLATE NOCASE`，导入前检查重复 |
| JSON/时间类型在 Hibernate+SQLite 下的映射 | 需要批量改约 12–20 个字段 | 阶段 1 原型验证并计数 |
| 控制端缺少自动化回归保护 | 改完无人发现问题 | 补仓库层与遥测存储的集成测试；阶段 5 人工走查 |
| `FINAL` 去重语义被误读 | 遥测重复或丢失 | 阶段 0 逐处标注，用 §5.4 的验收测试固化 |
| 并发聚合改写错误 | 概览趋势数据不准 | 与现有独立对照算法逐场景比对 |
| SQLite 单写者锁 | 偶发 `SQLITE_BUSY` | WAL + `busy_timeout` + `BEGIN IMMEDIATE` + 双库拆分 + 批量写入 |
| 切换期间遥测丢失 | 少量历史缺口 | 控制停机窗口；接受并在变更记录中说明 |
| 备份文件含敏感数据 | 泄露风险 | 仅留在被忽略目录；演练结束后删除本地副本与临时容器 |
| 误判 `agg_*` 无用而删错 | 功能缺失 | 阶段 0 用代码引用 + 接口调用双重确认 |

待确认清单（均在阶段 0/1 关闭）：

1. `agg_*` 表及相关代码是否仍有用；
2. 旧 `access_log`/`auth_log` 与 `/api/logs/access` 是否仍有写入；
3. `default` worker 的处理方式；
4. Hibernate 对 SQLite 的 JSON/时间映射可行性与改动量；
5. Spring Boot/Hibernate/Flyway 版本是否支持所选 SQLite 方言；
6. 是否存在必须跨库的遥测查询。

## 11. 影响的文件范围（参考）

- `backend/control-manager`：`pom.xml`、`application*.properties`、`db/migration/*`、`clickhouse/*`（删除）、`logstore/*`、`telemetry/*`、`resources/clickhouse/*`（删除或改写）、相关实体与仓库、`task/*`、`DataInitializer`。
- `deploy/docker/`：`docker-compose.control.yml`、`Dockerfile.control-manager`。
- `scripts/`：新增 `migration/`；`scripts/clickhouse/` 废弃；`scripts/e2e/` 两个临时栈 compose 与相关脚本。
- 文档：`README.md`、`docs/code-index.md`、`docs/testing/*` 中涉及 ClickHouse/MySQL 的段落、`docs/development/README.md` 索引。
- 跨模块契约核对：按 `docs/code-index.md` "常见改动定位"检查 `common`、`proxy-worker`、`frontend`；本次预期这三处无需改动，仍需在阶段 3 结束时确认。
