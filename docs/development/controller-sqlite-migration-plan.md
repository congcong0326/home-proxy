# 控制端数据库裁剪研发计划

> 状态：阶段 1 代码清扫已完成（见 §2.6），阶段 2 起尚未实施。设计细节（SQLite 运行参数、类型映射、导入脚本校验、切换回滚）见《[控制端数据库裁剪设计](controller-sqlite-migration-design.md)》，本文只回答"按什么顺序做什么、怎样算做完"。
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

## 5. 阶段 4（延后）：ClickHouse → SQLite

本阶段在阶段 3 稳定运行后再启动，内容见设计文档 §5。前置条件：

- 阶段 0 式盘点：17 处 `FINAL` 的用途、旧 `access_log`/`auth_log` 与 `/api/logs/access` 是否仍有写入、`agg_*` 相关接口的最终归属、遥测查询是否需要跨库；
- 把 `overview-concurrency.sql` 的算法与现有独立对照实现（`gateway-observability-concurrency-e2e.py`）准备为改写后的比对基准；
- 阶段 1 中推迟的项（`LogController` 旧日志接口等）在此阶段一并处理。

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
