# 控制端数据库裁剪研发计划

> 状态：阶段 1 代码清扫已完成（见 §2.6）；阶段 2 代码与文档已完成（见 §3.3），待人工确认其中的差异清单与线上无关的验收项；阶段 3 的导入脚本与本机演练已完成（见 §4.3），线上切换未做；阶段 4 代码、测试与 E2E 已完成（见 §5.1），界面查询待人工验证。设计细节（SQLite 运行参数、类型映射、导入脚本校验、切换回滚）见《[控制端数据库裁剪设计](controller-sqlite-migration-design.md)》，本文只回答"按什么顺序做什么、怎样算做完"。
>
> **范围调整**（相对设计文档）：
> 1. 先做 MySQL → SQLite（元数据）；ClickHouse 日志与遥测**延后**为独立的第 4 阶段，期间 ClickHouse 容器与代码保持不变。
> 2. 历史遗留表和代码不迁移，先清扫，再建基线。
> 3. 旧 ClickHouse 日志表与历史数据不迁移（设计文档 §1.3 已定）。

## 1. 总体顺序

| 阶段 | 内容 | 可独立发布 | 完成后控制端形态 |
|---|---|---|---|
| 1 | 历史无用代码清扫 | 是（仍跑 MySQL） | MySQL + ClickHouse，代码更干净 |
| 2 | 元数据库改造：MySQL → SQLite | 是 | SQLite + ClickHouse（两个容器） |
| 3 | 备份数据转换与线上切换 | 随阶段 2 一起上线 | 同上，线上数据已迁入 SQLite |
| 4（延后） | ClickHouse → SQLite 遥测 | 是 | 单容器 |

每个阶段单独成分支、单独验收、单独合入；阶段 1 完成后即可按普通版本在 MySQL 上发布，不与后续耦合。任何阶段验收不过，都可以停在当前阶段，不影响线上。

## 2. 阶段 1：历史无用代码清扫

### 2.1 目标

在换库之前，把"确认无用"的表、实体、接口、任务和配置删掉，让阶段 2 的基线 schema 和适配工作量最小。**只删除，不改行为**。

### 2.2 候选清单与初步证据

以下证据来自对源码和 2026-10-30 备份的初步扫描；"待确认"项必须在动手前补齐确认，不能仅凭"表是空的"就删除。

| 候选 | 证据 | 初步结论 | 待确认 |
|---|---|---|---|
| `http_gateways`、`http_virtual_hosts`、`http_tls_certificates`、`http_tls_certificate_versions`（迁移 V24、V25、V28–V31） | 只出现在迁移脚本里，控制端、Worker、前端、`common` 均无任何实体或引用；`static_site*`（V26）在备份中已不存在 | **删除**：不进入新基线，无代码可清 | ✅ 已确认：全仓库除迁移脚本和文档外无任何引用 |
| `worker_metric_samples`（V20） | 只出现在迁移脚本，无实体 | **删除**：不进入新基线 | ✅ 同上 |
| `agg_*` 共 11 张表及 `entity/agg`、`repository/agg`、`MinuteTrafficStatsCleanupTask` | 备份中全为空；仍被 `AccessLogStore`、`ClickHouseAccessLogStore`、`LogService`、清理任务引用 | **倾向删除** | 确认是否有写入方（若无则只剩读取空表）；这些接口方法耦合 ClickHouse 路径，需要在不动 ClickHouse 行为的前提下收窄接口；确认 `UserTrafficStatsController`/`UserTrafficStatsService` 实际数据来源 |
| `dns_rule`（`DnsRuleEntity`、`DnsRuleController`、`DnsRuleCreateRequest`/`UpdateRequest`；`common` 里的 `DnsRuleDTO`、`DnsRuleAction`、`DnsMatchType`、`DnsRecordType`，及 `AggregateConfigResponse.dnsRules` 字段） | 未完成功能的骨架：设想是按域名规则做本地应答 / 转发 / 拦截。`DnsRuleController` 是空类（无任何接口），没有 Repository 和 Service，`dnsRules` 字段全仓库无人赋值也无人读取，前端与 Worker 均无引用，也不在 `configHash` 快照里；备份中为空 | **删除**（实体、请求 DTO、控制器、`common` 中的 DTO 与枚举、`AggregateConfigResponse.dnsRules`） | 删字段会让下发的 JSON 少一个恒为 `null` 的键，不影响 `configHash`；确认 Worker 反序列化配置时对该键没有严格要求 |
| `rate_limits`（`RateLimit` 实体、仓库、`RateLimitService`、`RateLimitController`、两个请求 DTO；`common` 的 `RateLimitDTO`、`RateLimitScopeType`；Worker 的 `RateLimitConfig`；前端 `types/ratelimit.ts` 与 `api.ts` 中的导入；`AggregateConfigService.filterRateLimits`；`CanonicalConfigHasher` 的 `rateLimits`） | 只有配置通路，**从未实现限流**：Worker 只把数量打印到日志，没有执行限流的处理器；前端只有类型和 API 声明，没有页面；`docs/testing/gateway-observability-session-timing.md` 也记录过"Worker 仅有限流配置模型"；已确认不会再做；备份中为空 | **删除**整条通路 | 删除会改变下发配置的哈希（见 §2.4）；与 `admin.auth.login.rateLimitPerMinute`（管理员登录限频）无关，必须保留；遥测里剔除 `rateLimitPolicyId`/`rateLimitWaitUs` 的逻辑属于 ClickHouse 部分，留到阶段 4 |
| `MysqlBackup*`（服务、控制器、命令执行器、前端 `DatabaseBackup` 页、测试） | 依赖 `mysqldump`，换库后失效 | **不在本阶段删除**，阶段 2 替换为 SQLite 备份 | — |
| 旧 ClickHouse 表 `access_log`/`auth_log`、`/api/logs/access`、`LogController` | 仍有接口在用 | **不在本阶段处理**，放到阶段 4 | — |

除上表外，另做一轮通用扫描：

- 无引用的类（控制器、service、DTO、枚举），以及无调用方的 `@Scheduled`/`ApplicationRunner`；
- 配置项（`application*.properties`、compose 环境变量、README 变量表）中已无人读取的条目；
- `docs/`、`scripts/` 中引用已删除功能的内容。

发现的新候选逐项加入上表并标注证据，**不批量删除**。

### 2.3 做法

1. 对每个候选，用 `grep` 查控制端、`common`、`proxy-worker`、`frontend/src` 以及 `docs/code-index.md` 的引用，结论写回 §2.2。
2. 有跨模块契约的项（如 `dns_rule`、`rate_limits`，涉及 `common` 的下发配置结构），按 `docs/code-index.md` 的"常见改动定位"检查 `common`、`control-manager`、`proxy-worker`、`frontend` 是否同步修改。
3. **不新增 MySQL 迁移去 `DROP` 遗留表**：MySQL 将整体退役，历史迁移脚本也不再修改。遗留表在线上 MySQL 里继续躺着，不影响任何事。
4. 每删一类，运行 `mvn -pl backend/control-manager test -DskipTests=false`、前端 `npm test -- --watch=false`、`make build`，保证编译与现有测试通过。

### 2.4 `configHash` 基线

线上 `GET /api/workers/{id}/effective-config` 返回的 `configHash` 是后续"配置无损迁移"的核心证据（设计文档 §6.4）。清扫会让哈希变化一次：`CanonicalConfigHasher` 的快照里包含 `rateLimits` 键，删除限流通路后快照少一个键，所有 Worker 的哈希都会变；`dnsRules` 不在快照里，删除它不影响哈希。因此：

- 清扫开始前，记录每个 Worker 的当前 `configHash`（A）；
- 清扫发布到线上后，各 Worker 会因哈希变化收到一次配置重新下发（内容等价，属预期行为），确认各 Worker 应用成功后，再记录一次（B）；
- 阶段 3 的"导入后哈希一致"以 B 为准；
- 为证明清扫没有改变配置内容：在本地用同一份数据，对比"仅去掉 `rateLimits` 键后重新计算的快照哈希"与清扫后的实际哈希，两者应当一致。

### 2.5 验收

- 候选清单每一项都有结论（删除 / 保留 / 推迟）及依据；
- 编译、后端测试、前端测试、`make build` 全部通过；
- 在 MySQL 版本上完成一次预发布验证，主要页面和 Worker 配置下发正常；
- `configHash` 已记录 A、B，并解释差异（若有）。

### 2.6 阶段 1 执行结果

| 候选 | 结论 | 说明 |
|---|---|---|
| `http_*`、`worker_metric_samples` | 删除（不进入新基线） | 无代码引用，无需改代码 |
| `agg_*` 与 `entity/agg`、`repository/agg` | **已删除** | 实体与仓库在全仓库无任何外部引用（无写入也无读取）；`UserTrafficStatsService` 直接读 ClickHouse |
| `MinuteTrafficStatsCleanupTask` 及 `AccessLogStore`/`ClickHouseAccessLogStore`/`LogService` 中的 `cleanupExpiredMinuteTrafficStats`、`countExpiredMinuteTrafficStats` | **已删除** | ClickHouse 实现恒返回 0，任务是空操作；`/traffic/minute/*` 趋势接口仍走 ClickHouse，未改动 |
| `dns_rule` 全套 | **已删除** | 含 `common` 的 `DnsRuleDTO`、三个枚举与 `AggregateConfigResponse.dnsRules`。Worker 中的 `DnsRecordType` 是 Netty 自带类型，不受影响 |
| `rate_limits` 全套 | **已删除** | 含后端实体/仓库/服务/控制器/DTO、`common` DTO 与枚举、`AggregateConfigResponse.rateLimits`、Worker `RateLimitConfig` 与日志行、前端 `types/ratelimit.ts` 与 `api.ts` 中的接口、`CanonicalConfigHasher` 的 `rateLimits` 键。`admin.auth.login.rateLimitPerMinute` 保留 |
| `MysqlBackup*`、旧 ClickHouse 日志接口 | 推迟 | 分别在阶段 2、阶段 4 处理 |

验证：`mvn test -DskipTests=false`（全部模块）、前端 `tsc --noEmit` 与 `npm test` 通过。Worker 反序列化已关闭 `FAIL_ON_UNKNOWN_PROPERTIES`，新旧 Controller/Worker 混跑不受 `rateLimits` 字段消失影响。

**尚未完成**（需要线上环境或人工）：§2.4 的 `configHash` A/B 记录、MySQL 预发布验证；通用扫描（无引用类、无人读取的配置项）仅覆盖了上述候选，未做全仓库扫描。

## 3. 阶段 2：元数据库改造（MySQL → SQLite）

ClickHouse 保持原样，本阶段控制端同时依赖 SQLite（元数据）与 ClickHouse（日志）。

### 3.1 任务

1. **原型验证（门槛）**：SQLite 驱动与 Hibernate 社区方言能让控制端启动；Flyway 能跑 SQLite 基线；`LocalDateTime`、JSON 列、`BLOB`、布尔、`IDENTITY` 映射正确；`foreign_keys`、WAL、`busy_timeout` 生效；配置读写与 ClickHouse 日志写入并行时不出现 `SQLITE_BUSY`。**不通过则回到设计文档 §3.4/§4.5 调整，或终止并保持现状**。
2. **基线 schema**：新建 `V1__baseline.sql`，删除旧 `V1`、`V13`–`V37`；以备份中的真实 DDL 与实体注解交叉核对，差异逐项列出由人确认；不包含阶段 1 已确认删除的表；`flyway-mysql` 换为 SQLite 所需支持。
3. **代码适配**：JSON/时间/二进制/布尔映射（原型结论决定改动量）；`COLLATE NOCASE` 决策（设计文档 §4.6，逐列确认）；`ON UPDATE CURRENT_TIMESTAMP` 的 2 处由应用层维护；`default` worker 种子（原 `V21`）挪到 `DataInitializer` 做幂等创建；排查 JPQL/原生 SQL 里 `limit`、`CONCAT`、`INTERVAL`、窗口函数在新方言下的行为；扫描长事务。
4. **替换数据库备份功能**：把 `MysqlBackupService`/`Controller`/`ProcessMysqlCommandRunner` 与前端 `DatabaseBackup` 页改为 SQLite 在线备份（`VACUUM INTO` 或 JDBC 备份 API），并保留周期性备份与保留份数；更新 `production-multi-worker-upgrade-runbook.md` 中涉及 MySQL 备份的步骤。
5. **补测试**：为核心仓库层（用户、路由、规则集、入站、Worker、管理员）和备份功能补 SQLite 版集成测试，让以后有回归保护（`control-manager` 默认 `skipTests=true`，需在文档中写明运行方式）。
6. **部署**：`docker-compose.control.yml` 去掉 `mysql` 服务、数据卷、健康检查与 `depends_on`，`control-manager` 挂载 `/data` 保存 `home-proxy.db`；ClickHouse 服务保持不变。`scripts/e2e` 两个临时栈 compose 同步去掉 MySQL。
7. **文档**：`README.md`、`docs/code-index.md`、`docs/testing/*` 中涉及 MySQL 的描述。

### 3.2 验收

- 全新环境一条命令启动，只剩 `control-manager` 与 ClickHouse 两个容器，不再依赖 MySQL；
- `mvn -pl backend/control-manager test -DskipTests=false`、`make build` 通过；
- 空库启动后：首次初始化管理员 → 创建用户、路由、入站、规则集 → 发布到 Worker → Worker 收到配置，端到端走通（可用 `scripts/e2e/worker-proxy-e2e.sh --provision`）；
- 数据库备份页面可创建并下载备份，备份文件可用于恢复。

### 3.3 阶段 2 执行结果

**原型结论**（门槛通过）：`sqlite-jdbc 3.50.3.0` + Hibernate 6.6.29 自带社区方言 `hibernate-community-dialects`（版本由 Spring Boot BOM 管理）+ `flyway-core` 11.7.2 内置的 SQLite 支持，控制端可启动并跑通基线。

| 项 | 结论 |
|---|---|
| JSON 列（`@JdbcTypeCode(SqlTypes.JSON)`，4 处）与 `columnDefinition="json"` 的 `String` 列 | 开箱可用，存 `TEXT`，无需 `AttributeConverter` |
| 时间 | 新增 `persistence/SqliteLocalDateTimeConverter`、`SqliteInstantConverter`（`autoApply`），统一存 `yyyy-MM-dd HH:mm:ss.SSSSSS`。`LocalDateTime` 按原样字面存储（与 MySQL 时代语义一致，依赖 JVM 时区，容器内为 UTC）；`Instant` 存 UTC。磁盘监控的 `Instant` 比较、`MAX` 子查询已有测试覆盖 |
| `BLOB`、布尔、`IDENTITY` | 正常；`AUTOINCREMENT` 防主键复用有测试 |
| `@Lob String`（`WorkerTask.payloadJson`） | 去掉 `@Lob`（SQLite 驱动不支持 CLOB），改 `columnDefinition="TEXT"` |
| PRAGMA | `DataSourceConfig` 为每个连接设置 `foreign_keys=ON`、`journal_mode=WAL`、`synchronous=NORMAL`、`busy_timeout`（默认 10s）、`BEGIN IMMEDIATE`；连接池默认 4。测试验证已生效 |
| 并发 | 6 线程 × 25 次"先读后写"事务并发无 `SQLITE_BUSY`（`SqliteRepositoryTest`） |
| JPQL / 原生 SQL | 元数据库侧未发现 `limit`/`INTERVAL`/窗口函数（均在 ClickHouse 查询里，留到阶段 4）；`LOWER(CONCAT(...)) LIKE`、子查询 `MAX`、`LIKE %:x%` 在 SQLite 下行为正确。`@Transactional` 方法扫描未发现事务内网络/睡眠调用 |
| 异常翻译 | **行为差异**：约束冲突在 SQLite 下抛 `JpaSystemException`（`SQLITE_CONSTRAINT_*`），不再是 `DataIntegrityViolationException`。当前没有代码捕获该类型，无影响；将来依赖时需补翻译器 |

**基线 schema**（`V1__baseline.sql`，替换旧 `V1`、`V13`–`V37`）。表、列、索引、外键名与备份保持一致；以备份 DDL 与实体注解交叉核对后，以下差异**需要你确认**：

1. **丢弃列**：`worker_status.http_gateway_client_connection_count`、`http_gateway_upstream_active_connection_count`、`http_gateway_upstream_idle_connection_count`——备份中存在，但 `WorkerStatus` 实体未映射、无任何代码读写（V30 遗留）；基线不含这三列，阶段 3 导入时忽略。
2. **保留的遗留列**：`proxy_worker.token_hash`（实体注释标明已不使用，但仍映射，且有唯一约束与 `findByTokenHash`），原样保留。
3. **`COLLATE NOCASE`**（对应 MySQL `*_ci` 排序规则的大小写不敏感行为）：`admin_user.username`、`admin_login_history.username`、`users.username`/`ip_address`、`routes.name`、`rule_set.rule_key`、`mail_target.biz_key`、`mail_send_log.biz_key`、`scheduled_task.task_key`/`biz_key`、`wol_configs.name`/`ip_address`/`mac_address`/`worker_id`、`disk_monitor_host.host_id`、`disk_monitor_sample.host_id`/`device`、`disk_monitor_setting.setting_key`，以及所有 `worker_id` 列（`proxy_worker`、`inbound_configs`、`worker_status`、`worker_tasks`）。其余文本列（如 `inbound_configs.name`、`rule_set.name`）按 `BINARY` 区分大小写；如需与 MySQL 完全一致可逐列追加。
4. **`ON UPDATE CURRENT_TIMESTAMP`**：`wol_configs.updated_at` 在备份里有，实体已有 `@PreUpdate` 自行维护，基线仅保留 `DEFAULT`（毫秒精度、微秒位补零）。
5. **长度限制**：`varchar(n)` 全部变 `TEXT`，长度由应用/校验层保持，无数据库级约束。
6. `flyway.baseline-on-migrate` 已从配置中移除：对"非空但无 `flyway_schema_history`"的库，Flyway 会拒绝启动而不是静默基线到 V1。**阶段 3 的导入脚本必须通过 Flyway（或写入等价的 `flyway_schema_history` 记录）建库**，否则新版本启动会失败。
7. `default` Worker 种子（原 V21）：线上 `default` 是真实在用的主 Worker（带管理地址和令牌），故保留，改由 `DataInitializer.ensureDefaultWorkerExists()` 在不存在时幂等创建，导入后不会冲突。

**备份功能**：`MysqlBackup*`、`ProcessMysqlCommandRunner`、`MysqlBackupServiceTest` 已删除，新增 `SqliteBackupService`（`VACUUM INTO` 在线快照）、`DatabaseBackupController`（`/api/system-ops/database-backup/export|restore`）与前端页面改造。定时备份默认每天 03:30、保留 7 份、写入 `<数据目录>/backups`（`home-proxy.backup.*`）。**恢复采用"校验后暂存、重启生效"**：不在运行中的进程里替换正在使用的库；暂存文件为 `home-proxy.db.restore-pending`，启动时在连接池打开前换入，原库保存为 `backups/pre-restore-<时间>.db`。确认短语由 `RESTORE MYSQL` 改为 `RESTORE DATABASE`。

**部署与文档**：`docker-compose.control.yml` 去掉 `mysql`，`control-manager` 挂载 `control-data:/data`（`HOME_PROXY_DATA_DIR`）；`Dockerfile.control-manager` 去掉 `default-mysql-client`，声明 `VOLUME /data`；`scripts/e2e` 两个临时栈 compose 及脚本同步去掉 MySQL；`Makefile` 去掉 `MYSQL_IMAGE`；`README.md`（新增"数据库备份与恢复"）、`docs/code-index.md`、`docs/development/docker-deployment.md`、`dockerization-design.md`、`production-multi-worker-upgrade-runbook.md`（加适用范围说明）、`docs/testing/*` 当前描述已更新（历史测试记录里的 MySQL 字样保持原样）。

**测试**：新增 `SqliteRepositoryTest`（真实 SQLite 文件 + Flyway 基线，覆盖用户/管理员/路由/规则集/入站/Worker/任务/WOL/磁盘/邮件仓库、大小写、外键 `RESTRICT`/`CASCADE`/`SET NULL`、JSON/时间/BLOB 往返、并发写）与 `SqliteBackupServiceTest`（导出、保留份数、非法文件、暂存恢复）；`DataInitializerTest` 补 `default` Worker 断言。`src/test/resources/application.properties` 改为临时 SQLite，不再需要外部 MySQL。运行：`make backend-test` 或 `mvn -pl backend/control-manager test -DskipTests=false`（均为 `*Test`，默认随 Surefire 运行）。

**验证**：`mvn test -DskipTests=false`（全部模块）通过，control-manager 179 项（3 项原有跳过）；前端 `npm test` 87 项与 `tsc --noEmit` 通过。另用本机 ClickHouse 容器启动打包后的 jar 做了冒烟：空库启动 → 初始化管理员 → 大小写不同的用户名登录 → 创建用户 → 导出备份（`integrity_check` 为 ok）→ 上传恢复 → 重启后数据回到备份时刻且原库被保留。

**E2E 验证（2026-10-30）**：`make e2e-transparent`（36 个 Fake IP + TPROXY 场景）与 `make e2e-direct-outbound-safety` 两个隔离 compose 栈（SQLite + ClickHouse，无 MySQL）均通过。过程中修复了旧日志链路清理遗留的脚本问题，与 SQLite 无关：`fake-ip-transparent-e2e.py` 去掉对 sessions 不存在的 `configVersion` 的断言；`direct-outbound-address-safety-e2e.sh` 审计查询改为 `telemetry.proxy_session_log_v1`（错误码 `POLICY_BLOCKED` / `CONNECT_FAILED`），其 compose 补上 1 秒遥测刷新配置。

**尚未完成**：`scripts/e2e/worker-proxy-e2e.sh --provision`（依赖常驻 home-01 环境）、`make build` 全量（含前端生产构建）未在本机实跑；管理页面的浏览器人工点检未做；`SessionLogV1` 暂无配置版本字段，原审计"配置快照版本"断言未恢复。

## 4. 阶段 3：备份数据转换与线上切换

目标：把 `.tmp/home-proxy-mysql-20261030-082738.sql.gz` 这类 MySQL 备份，可重复地转换为阶段 2 基线 schema 对应的 `home-proxy.db`，然后完成线上切换。这个备份文件本身是只读输入，不修改，不提交。

### 4.1 任务

1. **导入脚本**：`scripts/migration/mysql-to-sqlite.py`（路径待定），流程、转换规则与校验清单沿用设计文档 §6，针对本次范围做三点调整：
   - 只导入保留的表，备份里属于阶段 1 已确认删除的表（含空表）和 `flyway_schema_history` 一律跳过；
   - 临时 `mysql:8.4` 容器还原备份，结束后销毁，不留数据卷；
   - 脚本使用与应用相同的 `V1__baseline.sql` 建库，避免两处 schema 漂移。
2. **反复演练**：用当前备份在本机完整跑通，逐项对照 §6.4 的校验清单，产出对账报告（每表行数、校验和、`foreign_key_check`、`integrity_check`）。
3. **启动验证**：用生成的 `home-proxy.db` 启动阶段 2 的控制端，登录、浏览主要页面，对每个 Worker 调用 `effective-config`，其 `configHash` 与阶段 1 记录的 B 一致。
4. **切换演练文档**：写成可执行的 runbook（停机窗口、最后一次备份、导入、启动、核对、回滚），在测试环境按文档完整演练一次。
5. **线上切换**：按设计文档 §9 执行；旧 MySQL 容器与数据卷在观察期（建议至少一周）内保持不动；观察期内冻结配置变更。
6. **收尾**：观察期结束后，经你确认再清理旧 MySQL 容器与数据卷，并删除本地备份副本。

### 4.2 验收

- 导入脚本对备份可重复运行且结果一致，对账报告全部通过；
- 线上切换后所有 Worker 的 `configHash` 与切换前一致，Worker 在线、配置下发与日志上报正常；
- 回滚路径已演练（用旧 compose 与旧镜像可恢复到切换前状态）。

### 4.3 阶段 3 执行结果（脚本与本机演练）

- 脚本：`scripts/migration/mysql-to-sqlite.py`（仅依赖 Python 3 标准库与 docker）。用法：`python3 -I scripts/migration/mysql-to-sqlite.py <备份.sql.gz> --out .tmp/home-proxy.db --sql .tmp/home-proxy-sqlite.sql --report .tmp/migration-report.json`。临时 `mysql:8.4` 容器无网络、tmpfs 存储、结束即销毁；读取时每列经 `HEX()` 取回，避免转义与 NULL 歧义；输出文件已存在则拒绝覆盖，校验失败则删除输出库。
- 导入 19 张保留表，跳过 18 张空表（阶段 1 已删除的表）与 `flyway_schema_history`；跳过的表若有数据则中止。`worker_status` 的 3 个 `http_gateway_*` 列按阶段 2 差异 1 丢弃。脚本直接执行 `V1__baseline.sql` 建库，并写入与 Flyway 一致的 `flyway_schema_history`（含校验和）。
- 校验（全部通过）：每表行数与内容摘要（SQLite 回读对比源数据）、`foreign_key_check` 无违规、`integrity_check` 为 `ok`、JSON 列 `json_valid`、唯一键 NOCASE 重复、`AUTOINCREMENT` 序列取 `max(MySQL AUTO_INCREMENT-1, max(id))`。
- 产物（`.tmp/`，已被忽略）：`home-proxy.db`、等价 SQL 脚本 `home-proxy-sqlite.sql`、对账报告 `migration-report.json`。
- 启动验证：阶段 2 的控制端 jar 打开该库时 Flyway 校验通过（`Successfully validated 1 migration`，无需迁移）；后续因本机无 ClickHouse 启动失败，与迁移无关。**登录/页面浏览、`configHash` 对比（需阶段 1 的 B 值）、runbook、测试环境演练、线上切换均未做。**

## 5. 阶段 4（延后）：ClickHouse → SQLite

本阶段在阶段 3 稳定运行后再启动，内容见设计文档 §5。前置条件：

- 阶段 0 式盘点：17 处 `FINAL` 的用途、旧 `access_log`/`auth_log` 与 `/api/logs/access` 是否仍有写入、`agg_*` 相关接口的最终归属、遥测查询是否需要跨库；
- 把 `overview-concurrency.sql` 的算法与现有独立对照实现（`gateway-observability-concurrency-e2e.py`）准备为改写后的比对基准；
- 阶段 1 中推迟的项（`LogController` 旧日志接口等）在此阶段一并处理。

### 5.1 阶段 4 执行结果

**盘点结论**：ClickHouse 中只有 5 张表。在用的是 `telemetry.proxy_session_log_v1`、`dns_query_log_v1`、`credential_auth_log_v1` 三张遥测表；旧 `default.access_log`/`auth_log` 全仓库没有写入方（唯一的 `INSERT` 在 `TelemetryStore`），读它们的接口一直返回空。17 处 `FINAL` 都只用于"同一事实重发只取一条"，没有依赖"多版本取最新"的语义。

| 项 | 结论 |
|---|---|
| 存储 | 独立文件 `<数据目录>/telemetry.db`（`observability.sqlite.url`），WAL、`busy_timeout`、`BEGIN IMMEDIATE`，与元数据库互不争锁；首次建库设 `auto_vacuum=INCREMENTAL`，`PRAGMA user_version=1`。`TelemetryDataSourceConfig`、`TelemetryJdbcClient` 替换 `clickhouse/` 包 |
| 表结构 | 仍由 `TelemetrySchema` 按 V1 DTO 字段反射生成：整数／布尔／时间为 `INTEGER`（时间为 UTC epoch 微秒），字符串与列表（JSON 数组）为 `TEXT`；索引 `(worker_id, occurred_at)`、`(occurred_at)`，会话表加 `(worker_id, inbound_id, occurred_at)`，认证表加 `(worker_id, worker_instance_id, connection_id)` |
| 去重 | 唯一键 `(worker_id, worker_instance_id, record_id)` + `INSERT OR IGNORE`，重发保留首次写入（含 `received_at`）；全部 `FINAL` 删除。一批在一个事务内写入，失败整批保留在内存队列重试 |
| 保留期 | `TTL` 改为 `TelemetryStore.purgeExpired`：每小时按表分批（5000 行）删除过期事实后 `incremental_vacuum`；`observability.*-retention-days` 默认不设即不删，与原行为一致 |
| 查询改写 | `multiIf`/`countIf`/`sumIf` → `CASE`；`intDiv(toUnixTimestamp64Micro(..))` → 微秒整数除法；`positionCaseInsensitiveUTF8` → `instr(lower(..), lower(?))`（仅 ASCII 大小写折叠）；`LIMIT 21 BY` → `ROW_NUMBER()`；元组 `IN ((?,?))` → `IN (VALUES (?,?))`；`toJSONString(array)` → 分列分组后 `json_array`；`quantileExactIf(0.95)` → 窗口函数取排序后第 `floor(0.95n)` 个样本（与 ClickHouse 语义一致）；`firstSessionAt` 仍输出 `yyyy-MM-dd HH:mm:ss.SSSSSS`（UTC） |
| 大数 | `UInt128` 累加改为 SQLite 64 位整数，溢出时报错而不是回绕；对单节点字节计数（上限约 9.2 EB）足够。接口仍以字符串返回 |
| 并发度 SQL | `resources/telemetry/overview-concurrency.sql`：`ARRAY JOIN`/`range` 改为 `UNION ALL` 事件与递归 CTE 桶边界，`leadInFrame` 改为 `LEAD`，`Int128` 改为 64 位。`scripts/e2e/gateway-observability-concurrency-e2e.py` 已改为用内存 SQLite 跑生产 SQL，7 个场景（含 4000 条随机区间、6 万条区间）与独立区间交集算法逐桶一致 |
| 旧 `access_log` 链路 | **已删除**：`clickhouse/`、`logstore/`、`LogService`、`LogController`（`/api/logs/**`）及专用 DTO；前端未挂路由的 `LogAudit`、`AggregatedAnalysis`、`LegacyTrafficOverview`、入站页"旧日志"来源与对应 API 方法；`scripts/clickhouse/`。仍在用的两处改读会话表：`UserTrafficStatsService`（首页状态面板，上传为 `byteIn`、下载为 `byteOut`）、`TrafficReportMailHandler`（Top 用户／目标／路由与日、月总量；"按应用"改为"按目标"，因为会话表没有原 `cutToFirstSignificantSubdomain` 的应用口径） |
| 部署 | `docker-compose.control.yml` 与两个 e2e 临时栈去掉 `clickhouse` 服务、环境变量与数据卷，控制面只剩 `control-manager` 一个容器；`Dockerfile.control-manager` 安装 `sqlite3` 命令行工具，供运维与 E2E 只读查询；`pom.xml` 去掉 `clickhouse-jdbc`；`Makefile` 去掉 `CLICKHOUSE_IMAGE`（`docker-save-offline` 保留为 `docker-save` 的别名） |

**测试**：新增 `TelemetrySqliteFixture` 与 `TelemetrySqliteQueryTest`，经真实 ingest → 缓冲 → flush 写入临时 SQLite 文件后，按手算结果核对重发去重、大数往返、审计分页／详情／认证尝试关联、概览（流量、连接、DNS、认证、并发度含裁剪与不足一桶的末桶）、分布 TopN 与全量分母、入站月流量、认证地理分布、用户流量统计、保留期删除；`NetworkInspectionServiceTest` 改为真实 SQLite 数据（含 P95）。其余 mock 单测按新 SQL 与微秒时间列更新。`mvn -pl control-manager -am test -DskipTests=false` 178 项通过；前端 `tsc --noEmit` 与 `npm test` 85 项通过。

**启动与 E2E（2026-10-30）**：在本机无 ClickHouse 的情况下用打包后的 jar 启动，4.6 秒内启动完成，自动创建 `telemetry.db` 的 3 张表与 9 个索引。`make e2e-transparent` 通过（52 项 PASS），新增的落库断言显示该 Worker 在 `telemetry.db` 中写入 72 条会话、42 条 DNS 记录；`make e2e-direct-outbound-safety` 通过（6 项 PASS），其审计断言改为在容器内用 `sqlite3` 查询 `proxy_session_log_v1`；本机 29290 端口被常驻 home-01 环境的 `fake-ip-persistent-e2e.py serve` 占用，运行时用 `E2E_DIRECT_SAFETY_OVERRIDE_PORT=29390` 换端口。

**未做 / 待确认**：
- 管理页面（概览、分布、审计、入站流量、首页状态面板）的界面效果由人工验证；
- 线上 ClickHouse 的历史日志不迁移（设计文档 §1.3），切换后新日志从零开始累计，旧 ClickHouse 容器与数据卷在确认后再手工清理；
- `gateway-observability-overview-e2e.py`、`gateway-observability-protocol-probe.py` 已改为读 SQLite，但依赖常驻 home-01 环境，本机未运行；
- 历史设计／测试文档中的 ClickHouse 描述保持原样。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 误删仍在使用的功能（空表不等于无用） | 阶段 1 要求"代码引用 + 前端 + Worker"三处证据，删除前写进清单；`rate_limits`、`dns_rule` 已核实为从未实现的功能骨架 |
| 清扫改变下发配置，导致后续哈希对不上 | §2.4 记录 A、B 两次哈希，阶段 3 以 B 为准 |
| Hibernate + SQLite 的 JSON/时间映射改动量超预期 | 阶段 2 以原型为门槛，必要时降低范围或终止 |
| 大小写语义变化（唯一键由不区分变为区分） | 基线 `COLLATE NOCASE`，导入前检查重复值 |
| 控制端缺少自动化测试 | 阶段 2 补仓库层与备份功能的集成测试；每阶段合入前人工回归 |
| 切换期间配置或遥测丢失 | 控制停机窗口；观察期冻结配置变更；旧 MySQL 保留用于回滚 |
| 备份文件含敏感信息 | 仅放被忽略目录，演练结束后删除本地副本与临时容器 |

## 7. 范围外

Worker 快捷部署（见《[Worker 快捷部署方案大纲](worker-quick-install-outline.md)》）、透明代理脚本改造、前端页面视觉改动，均不在本计划内。
