# 一期 路由与出站拆分、改写表设计

整体规划见 [路由与出站配置模型演进规划](route-outbound-model-roadmap.md)。

## 1. 目标与范围

目标：

- 出站成为独立资源，多条路由可以引用同一个出站。
- 新增改写表，把"每个 NAS 域名一条路由"收敛为"一张表 + 一条路由"。
- 现有数据自动迁移，迁移后下发给 worker 的配置与迁移前语义等价。

不在本期：

- worker 代码与下发协议的任何改动。
- 出站组（二期）、多出站 fallback（三期）。
- 改写后经上游代理转发。当前 worker 的 `DESTINATION_OVERRIDE` 固定为直连，本期保持不变。
- 删除 `routes` 表的旧出站列，放到二期完成后单独清理。

## 2. 现状与约束

| 位置 | 现状 | 对设计的约束 |
|---|---|---|
| `RouteSelector`、`DnsRouteSelector` | 按绑定列表顺序匹配，路由内多个条件之间是"或"，首个命中即停 | 改写表路由不能再叠加其他条件 |
| `OutboundConnectorFactory` | `DESTINATION_OVERRIDE` 要求 `outboundProxyHost/Port` 有效，否则阻断，连接方式为直连 | 连接目标改写必须带端口 |
| `DnsQueryService.rewrite` | `DNS_REWRITING` 读取 `outboundProxyHost`，要求为 IP 字面量 | DNS 改写的目标必须是 IP |
| `ProxyWorkerApplication.ConfigChangeListener` | 按 `RouteDTO.id` 建 map，绑定中的 routeId 必须能在下发的路由中找到 | 展开出的合成路由需要唯一 ID，绑定列表要同步替换 |
| `DefaultRouteConfig` | 已占用 `id = -1` | 合成路由 ID 不使用负数 |
| 遥测 | `routeId` 以无符号整数存储，`DistributionService` 按 `route_id` 聚合 | 合成路由 ID 必须为正且稳定 |
| `FakeIpConfigValidator` | 直接读 `Route` 实体的 `outboundProxy*` 校验 DNS 上游 | 改为读取编译结果 |
| `CanonicalConfigHasher` | 对下发的 DTO 计算哈希 | 编译结果参与哈希，无需改动 |
| worker 配置轮询 | `CONFIG_POLL` 默认 2 秒，只改路由时原子替换快照，不重启监听 | 迁移后哈希变化一次，worker 平滑重载 |

## 3. 数据模型

迁移脚本 `V2__route_outbound_split`。

### 3.1 出站 `outbounds`

```sql
CREATE TABLE outbounds (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL COLLATE NOCASE,
    kind         TEXT    NOT NULL,          -- DIRECT / BLOCK / PROXY / DNS_UPSTREAM
    builtin_key  TEXT,                      -- 内置出站为 DIRECT / BLOCK，其余为 NULL
    proxy_type   TEXT,                      -- PROXY: HTTPS_CONNECT/SOCKS5/SHADOW_SOCKS/VLESS_REALITY；DNS_UPSTREAM: DNS_SERVER/DOT
    host         TEXT,
    port         INTEGER,
    username     TEXT,
    password     TEXT,
    enc_algo     TEXT,
    config_json  TEXT,
    notes        TEXT,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL,
    CONSTRAINT uk_outbounds_name UNIQUE (name),
    CONSTRAINT uk_outbounds_builtin UNIQUE (builtin_key)
);
```

- 内置出站"直连""阻断"由迁移脚本插入，`DataInitializer` 启动时幂等补齐，不可编辑、不可删除。
- 出站不设启用状态。路由已有 `status`，在出站上再加一层开关会让"为什么没生效"更难排查。

### 3.2 改写表 `rewrite_tables`、`rewrite_entries`

```sql
CREATE TABLE rewrite_tables (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL COLLATE NOCASE,
    notes      TEXT,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    CONSTRAINT uk_rewrite_tables_name UNIQUE (name)
);

CREATE TABLE rewrite_entries (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    table_id       INTEGER NOT NULL,
    sort_order     INTEGER NOT NULL,
    domain_pattern TEXT    NOT NULL COLLATE NOCASE,
    target_host    TEXT    NOT NULL,
    target_port    INTEGER NOT NULL,
    enabled        INTEGER NOT NULL DEFAULT 1,
    notes          TEXT,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    CONSTRAINT uk_rewrite_entries_pattern UNIQUE (table_id, domain_pattern),
    CONSTRAINT fk_rewrite_entries_table FOREIGN KEY (table_id) REFERENCES rewrite_tables (id) ON DELETE CASCADE
);
CREATE INDEX idx_rewrite_entries_table_order ON rewrite_entries (table_id, sort_order);
```

- `domain_pattern` 沿用 `DomainMatcher` 语法：`a.com` 只匹配自身，`.a.com` 匹配自身及子域，`*.a.com` 只匹配子域。不允许单独的 `*`。
- 改写表只用于连接目标改写，`target_port` 必填。
- DNS 改写不使用改写表。原先"DNS 把 NAS 域名指向一个公网 IP，引流进透明代理"的做法已经被 Fake IP 取代；剩下的 DNS 改写是少量真正的分区解析（如 `nas.leetmentor.com → 192.168.10.1`），用固定目标路由就够了。见第 14 节。
- 改写表按"服务分组"建立，一个服务分组一张表。各用户组在入站绑定中选择需要的改写表路由，以此保留按用户的访问隔离，不建一张总表。

### 3.3 路由 `routes` 新增列

```sql
ALTER TABLE routes ADD COLUMN action TEXT;               -- FORWARD / REWRITE_DESTINATION / REWRITE_DNS
ALTER TABLE routes ADD COLUMN outbound_id INTEGER REFERENCES outbounds (id);
ALTER TABLE routes ADD COLUMN rewrite_table_id INTEGER REFERENCES rewrite_tables (id);
ALTER TABLE routes ADD COLUMN rewrite_target_host TEXT;
ALTER TABLE routes ADD COLUMN rewrite_target_port INTEGER;
```

SQLite 不能用 `ALTER` 加 `NOT NULL` 约束，所以 `action` 的非空由迁移回填和服务层校验保证。

各动作的字段组合：

| action | rules | outbound_id | rewrite_table_id | rewrite_target_* |
|---|---|---|---|---|
| `FORWARD` | 必填 | 必填 | 空 | 空 |
| `REWRITE_DESTINATION`，改写表 | 必须为空 | 内置"直连" | 必填 | 空 |
| `REWRITE_DESTINATION`，固定目标 | 必填 | 内置"直连" | 空 | host 与 port 必填 |
| `REWRITE_DNS`（只支持固定目标） | 必填 | 空 | 空 | host 必填且为 IP |

- 保留"固定目标"有两个原因：迁移时可以与旧路由一一对应、零风险；少数改写路由的条件不是纯域名（比如规则集），无法放进改写表。
- `REWRITE_DESTINATION` 的 `outbound_id` 本期固定为内置"直连"。提前存下来，以后支持改写后走上游时不用再迁移。

旧列处理：

- `policy` 列有 `NOT NULL` 约束，继续写入编译出的策略值，便于旧查询和排查。
- `outbound_tag`、`outbound_proxy_*` 列在迁移时冻结，之后不再读写，二期完成后由单独的迁移脚本重建表并删除。

## 4. 编译规则

新增 `RouteCompiler`（control-manager service 层），输入路由实体、出站和改写表，输出 `List<RouteDTO>`。`AggregateConfigService` 改为调用它，不再直接使用 `RouteService.convertToDTO` 的结果。

### 4.1 单条路由

| 路由 | 出站 kind | 编译出的 `RouteDTO` |
|---|---|---|
| `FORWARD` | `DIRECT` | `policy=DIRECT`，出站字段为空 |
| `FORWARD` | `BLOCK` | `policy=BLOCK`，出站字段为空 |
| `FORWARD` | `PROXY` | `policy=OUTBOUND_PROXY`，`outboundProxyType/Host/Port/Username/Password/EncAlgo/Config` 取自出站 |
| `FORWARD` | `DNS_UPSTREAM` | 同上，`outboundProxyType` 为 `DNS_SERVER` 或 `DOT` |
| `REWRITE_DESTINATION` 固定目标 | — | `policy=DESTINATION_OVERRIDE`，`outboundProxyHost/Port` 为改写目标，`rules` 原样 |
| `REWRITE_DNS` 固定目标 | — | `policy=DNS_REWRITING`，`outboundProxyHost` 为改写 IP，`rules` 原样 |

`id`、`name`、`rules`、`status`、`notes`、`outboundTag` 取自路由本身。

### 4.2 改写表路由展开

改写表路由按条目 `sort_order` 展开，每个已启用的条目生成一条合成路由：

```
id     = REWRITE_ROUTE_ID_BASE + entry.id      // REWRITE_ROUTE_ID_BASE = 1_000_000_000_000L
name   = 路由名 + " / " + domain_pattern
rules  = [ { DOMAIN, IN, domain_pattern } ]
policy = DESTINATION_OVERRIDE，outboundProxyHost/Port = target_host、target_port
```

- 号段 `1e12` 远大于 `AUTOINCREMENT` 的实际取值，小于 JavaScript 安全整数，遥测以无符号整数存储也没问题。条目 ID 不会复用，所以合成 ID 跨发布稳定。
- 已禁用的条目不展开。没有已启用条目的表，路由展开为空，效果等同于路由被禁用。
- 遥测中改写流量按条目分别聚合，名称可以直接看出是哪个 NAS 服务。

### 4.3 绑定列表替换

`AggregateConfigService.toRuntimeInbound` 现在会过滤禁用的路由，在这里增加一步：把绑定列表中的改写表路由 ID 原位替换成它展开后的合成 ID 列表。

```
绑定:   [10 (NAS 改写表), 11 (广告), 12 (科学上网)]
下发:   [1e12+1, 1e12+2, 1e12+3, 11, 12]
```

`fallbackRouteId` 不允许指向改写表路由，因为兜底只能是单条路由。保存入站时校验。

### 4.4 依赖解析与缓存

- `WorkerConfigDependencyResolver.resolveAffectedWorkerIds` 新增 `outbound`、`rewriteTable` 两类资源，用于判断删除引用和影响范围。
- 出站、改写表、条目的增删改都加 `@CacheEvict(value = "aggregateConfig", allEntries = true)`，与 `RouteService` 保持一致。
- `getRedactedAggregateConfig` 作用于编译结果，脱敏逻辑不变。

## 5. 校验

出站：

- 从 `RouteService` 迁移 `validateShadowsocksRoute`、`validateOutboundProxy`（Reality 参数）到 `OutboundService`。
- `DNS_UPSTREAM` 的 host 必须是 IP 字面量（可以用逗号分隔多个），与 `FakeIpConfigValidator.validateUpstream` 一致。
- 删除被路由引用的出站时返回 409，提示信息沿用 `RouteService.describeRouteReference` 的格式，列出引用它的路由。

路由：

- 按 3.3 的字段组合表校验。
- `FakeIpConfigValidator` 改为基于编译后的 `RouteDTO` 判断 DNS 转发与上游，不再读取实体上的旧列。

改写表：

- 条目的 `domain_pattern` 在表内唯一，不能为 `*`；`target_port` 在 1–65535。
- 同一个域名模式出现在多张表中是允许的（不同服务分组可以包含同一个服务）。如果同一个绑定引用的两张表中，同一个模式指向不同的目标，保存入站时给出警告，因为只有排在前面的那一条会生效。
- 删除被路由引用的表返回 409。

## 6. 数据迁移

迁移需要解析 JSON 并对出站去重，用 SQL 不好写，所以使用 Flyway Java 迁移 `db.migration.V2__route_outbound_split`（放在 `src/main/java/db/migration`，`spring.flyway.locations=classpath:db/migration` 会同时扫描 SQL 与 Java 迁移）。建表部分仍放在同一个迁移类中执行。

逐条处理旧路由：

| 旧 policy | 迁移结果 |
|---|---|
| `DIRECT` | `FORWARD`，出站为内置"直连" |
| `BLOCK` | `FORWARD`，出站为内置"阻断" |
| `OUTBOUND_PROXY` | `FORWARD`，出站按 `(proxy_type, host, port, username, password, enc_algo, config_json)` 去重后新建 |
| `DESTINATION_OVERRIDE` | `REWRITE_DESTINATION` 固定目标，目标取自 `outbound_proxy_host/port`，出站为内置"直连" |
| `DNS_REWRITING` | `REWRITE_DNS` 固定目标，目标取自 `outbound_proxy_host` |

- 新建出站的名称：`PROXY` 用 `类型 host:port`，`DNS_UPSTREAM` 用 `DNS host:port`，重名时加序号。迁移后可以在管理台重命名。
- 迁移不自动生成改写表。合并改写路由需要调整路由顺序，交给第 7 节的合并工具，由用户预览确认后执行。
- 迁移前按现有流程做在线备份（`SqliteBackupService`）。

等价性：

- 旧路由中可能残留与策略无关的字段，比如 `DIRECT` 路由上残留的 `outbound_proxy_host`。编译结果会清掉这些字段，所以迁移后配置哈希会变化一次，worker 会平滑重载一次。
- 验收以"有效字段等价"为准：对每个 worker，比较迁移前后的下发配置，按 policy 只比较对该策略有意义的字段。这个比较做成测试工具（见第 10 节），上线前对生产备份执行一遍。

## 7. 合并改写路由工具

用于把现有的大量改写路由一次性收敛为改写表。

```
POST /api/rewrite-tables/merge-from-routes
{ "routeIds": [...], "tableName": "NAS 服务", "routeName": "NAS 服务改写", "dryRun": true }
```

- 只接受 `REWRITE_DESTINATION` 固定目标路由，并且条件全部为 `DOMAIN IN`。其他路由返回 400，并说明原因。
- 一次调用生成一张表。按服务分组拆成多张表时，分多次调用。如果某张表中的路由只出现在部分绑定里，预览会把"合并后哪些用户组获得了原本没有的服务"列为权限扩大，必须逐项确认。
- 每个 `DOMAIN IN` 条件生成一个条目，目标取自所在路由，条目顺序按入参顺序。
- 新建一条改写表路由。在每个入站绑定中，它占据被合并路由第一次出现的位置，其余被合并路由从绑定中移除。
- 被合并的旧路由设为禁用，不删除，确认没问题后由用户手动删除。
- `dryRun=true` 只返回预览：将生成的条目、每个受影响绑定的前后顺序，以及"顺序变化风险"。如果某个绑定中，两条被合并路由之间夹着其他路由，合并后这些路由与改写条目的先后关系会改变，需要逐一列出由用户确认。
- 整个操作在一个事务内完成。

## 8. API

| 接口 | 说明 |
|---|---|
| `GET/POST /api/outbounds`，`GET/PUT/DELETE /api/outbounds/{id}` | 出站 CRUD，内置出站只读 |
| `GET /api/outbounds/{id}/references` | 引用该出站的路由 |
| `GET/POST /api/rewrite-tables`，`GET/PUT/DELETE /api/rewrite-tables/{id}` | 改写表 CRUD，`PUT` 时整表替换条目列表 |
| `POST /api/rewrite-tables/{id}/import` | 按行导入文本：`域名模式 host[:port] [备注]`，`mode=append/replace` |
| `GET /api/rewrite-tables/{id}/export` | 导出为同格式文本 |
| `POST /api/rewrite-tables/merge-from-routes` | 第 7 节 |
| `/api/routes` | 请求体去掉 `policy`、`outboundTag`、`outboundProxy*`，新增 `action`、`outboundId`、`rewriteTableId`、`rewriteTargetHost`、`rewriteTargetPort`。响应增加 `outboundName`、`rewriteTableName`、`entryCount` 等展示字段，`policy` 保留为只读的编译结果。列表筛选从 `policy` 改为 `action` 加 `outboundId` |

`common` 中的 `RouteDTO` 是 worker 的下发契约，保持不变。控制端管理接口使用 control-manager 内新的路由 DTO，避免两种用途共用同一个类。

## 9. 前端

- 新增 `OutboundManagement.tsx`。表单从 `RouteForm.tsx` 中抽出现有的上游代理参数部分（Shadowsocks、Reality 等），作为 `OutboundForm`。列表展示引用数。
- 新增 `RewriteTableManagement.tsx`。条目表格支持拖拽排序、启用开关、批量文本导入导出。表单提示：精确域名应放在通配之前。
- `RouteForm.tsx` 改为先选动作：
  - 转发：选择出站，条件编辑器不变。
  - 目标改写或 DNS 改写：选择"改写表"或"固定目标"。选改写表时隐藏条件编辑器，显示条目数和预览。
- `RouteManagement.tsx` 列表新增"动作""出站 / 改写表"两列；支持多选后"合并为改写表"，先展示 dry-run 预览再确认。
- `InboundManagement.tsx` 的路由选择项展示出站名称，并标出改写表路由。兜底路由下拉框排除改写表路由。
- 导航：`ProxyConfig.tsx` 增加"出站管理""改写表"，`App.tsx` 增加对应路由，`services/api.ts`、`types/outbound.ts`、`types/rewrite.ts`、`types/route.ts` 同步修改。

## 10. 测试

后端单测（`mvn -f backend/pom.xml -pl control-manager test -DskipTests=false`）：

- `RouteCompiler`：覆盖 4.1 表中每一行；改写表展开的 ID、名称、顺序；禁用条目；空表。
- `AggregateConfigService`：绑定列表原位替换；禁用的改写表路由被过滤；合成 ID 在多次发布间保持稳定。
- 迁移：用包含各种旧 policy、残留字段、重复上游的 SQLite 夹具执行 `V2`，断言出站去重结果，并通过有效字段等价比较。
- 基线导出：`migration/AggregateConfigSnapshotExportTest`。它只在传入 `-Dsnapshot.db` 时运行，复制该库后在副本上执行 Flyway，再把每个 worker 的下发配置导出到 `-Dsnapshot.out`。工具只装配聚合配置相关的 bean，不会连接库中登记的 worker。在当前代码上运行得到基线，加入 V2 后运行得到迁移结果。
- 等价比较工具：作为测试工具类，输入两份导出结果，按 policy 比较有效字段，输出差异列表。
- 合并工具：dry-run 预览；顺序变化风险检测；非 `DOMAIN IN` 条件被拒绝；事务回滚。
- 校验：第 5 节每条规则；删除被引用的出站或改写表返回 409。
- `FakeIpConfigValidator` 基于编译结果的回归。

前端（`npm test -- --watch=false`）：`OutboundManagement`、`RewriteTableManagement`、`RouteForm` 各动作分支、合并预览弹窗。

端到端：在测试 worker 上验证 NAS 域名经改写表命中后连接到内网目标，遥测中的路由名为"路由名 / 域名模式"。e2e 端口避开 29290。

## 11. 任务拆分

1. `V2` 迁移：建表、内置出站、旧数据迁移、等价比较工具。
2. `OutboundService`、`RewriteTableService` 与控制器，校验迁移，依赖解析与缓存失效。
3. `RouteCompiler`，`AggregateConfigService` 接入，`FakeIpConfigValidator` 改造。
4. 路由管理接口改为新字段，`ensureDefaultRouteExists` 改为引用内置"直连"。
5. 合并改写路由工具。
6. 前端：出站管理、改写表、路由表单、入站页调整。
7. 生产备份离线跑等价比较，上线，观察一周后用合并工具收敛改写路由。

## 12. 回滚

- 上线前做在线备份。回滚方式为旧版本镜像加恢复备份，备份之后的配置修改需要重新录入。
- 观察期内不删除旧列，也不删除被合并的旧路由（只禁用）。
- worker 不涉及变更，不需要回滚。

## 13. 风险与待验证

- 迁移后哈希会变化一次，需要确认所有 worker 都能平滑应用。可以在面板上看 `applied_config_hash` 是否都已更新。
- 合成路由 ID 第一次出现在遥测中，需要确认 `DistributionService` 和流量报表邮件对大 ID、带 `/` 的路由名展示正常。
- 改写表条目很多时，worker 仍然线性匹配。几十条没有问题，达到几千条再考虑 worker 端使用哈希索引。
- 合并工具可能改变匹配顺序，依赖 dry-run 预览和用户确认来控制。

## 14. 生产数据摸底（2026-10-30）

基线库为 192.168.10.85 上 `/mnt/ssd2/proxy/homeproxy/control-data/home-proxy.db` 的在线备份，本地副本在 `.tmp/baseline/`（git 忽略）。下面的摸底基于用户第一次清理之前的副本。清理后重新拉取的副本（`home-proxy-20261030-200907.db`）是当前的迁移基线，导出结果在 `.tmp/baseline/before/`；两个 worker 的导出哈希与 `worker_status.applied_config_hash` 一致，说明导出工具与线上计算结果完全相同。

清理后的变化：删除路由 11、14、21、26、36、40；DNS 入站的绑定合并为一组 `[4, 13]`，路由 41 因此不再被引用；路由 39（steam、cn → 直连）重新启用。迁移后上游出站为 4 个（Reality、SS、DNS、DoT），加上内置共 6 个。

总体情况：

- 42 条路由：目标重写 28（启用 27）、上游代理 7、DNS 改写 2、直连 3、阻断 2。worker 为 `default`、`happy-bounty-1`。
- 7 条上游代理路由去重后得到 6 个出站。路由 14、15 是同一个 Reality 节点。迁移后共 8 个出站（含 2 个内置）。
- 启用的改写路由条件全部为 `DOMAIN IN`。路由 38（`*` → 127.0.0.1:445，VPS SNI 入站的兜底改写）不能放入改写表，保留为固定目标。
- 路由 36 的 `outbound_transport=TLS`、`outbound_tls_config_json` 没有任何代码读取，属于死数据，迁移时忽略。这条路由未被引用。
- 未被引用的路由：14、21、36、40，照常迁移。

改写路由的访问隔离：不同用户组引用的改写路由子集不同。按"被哪些绑定引用"分类，可以得到 8 组，用它们组合即可精确还原现有的权限：

| 分组 | 路由 | 引用的绑定 |
|---|---|---|
| AI 反代 | 2 | SOCKS5 主力设备、透明代理 cmg 设备、透明代理服务器组 |
| 日常服务 | 3, 5, 6, 7, 8, 12, 17, 18, 19, 20, 37 | SOCKS5 主力设备、透明代理 cmg 设备 |
| 管理面板 | 9, 10, 23, 25, 31 | SOCKS5 主力设备、透明代理 cmg 设备、内网 HTTPS |
| 服务器 SSH | 22, 24, 35 | SOCKS5 主力设备、内网 HTTPS |
| 个人专属 | 27, 32, 33 | SOCKS5 主力设备 |
| leetmentor 正式 | 34 | SS hongkong_vps_1 |
| openlist | 42 | 内网 HTTPS |
| emby | 43 | SOCKS5 主力设备、透明代理 cmg 设备、透明代理电视组、内网 HTTPS |

- 只有一条路由的分组（AI 反代、leetmentor 正式、openlist、emby）可以继续使用固定目标路由，不需要建表。
- 改写域名两两不相交。夹在改写路由之间的路由 26（GPT）和 16（广告）与它们的域名也不相交，所以合并后顺序变化不影响匹配结果。
- 以 SOCKS5 主力设备为例，绑定中的改写路由从 24 条减少为 6 条（4 张表 + AI 反代 + emby）。

DNS 改写：

- 路由 11 把 20 个 NAS 域名统一解析到 `216.239.34.223`，这是 Fake IP 之前用来把流量引进透明代理的做法。DNS 入站（id 3）已经开启 Fake IP。删除路由 11 以后，这些域名会走 DNS 转发路由，并由 `DnsQueryService` 在 `CLIENT_FAKE` 模式下分配 Fake IP；透明代理反查到域名后，再由改写路由转到内网。效果相同。
- 路由 11 的域名列表已经和改写路由不一致（11 个改写域名不在列表中，`my-mock.com` 只在列表中），删除它同时消除了这份重复维护的列表。
- 路由 41（`nas.leetmentor.com → 192.168.10.1`）是真正的分区解析，与 Fake IP 无关，需要保留。
- 删除路由 11 是一次独立的配置操作，不依赖本期开发，可以在现有管理台中先完成：从 DNS 入站的三个绑定中移除路由 11，并把它设为禁用（不删除），观察一段时间。

## 15. 实施记录（2026-10-30）

已实现第 11 节任务 1–6，worker 未改动。与设计的出入：

- `routes.outbound_tag` 保留在路由上并原样编译进 `RouteDTO`。它参与配置哈希，保留后迁移前后的哈希可以逐字节一致。
- 路由更新接口改为整体替换（`RouteRequest`），不再支持只传部分字段。
- "兜底路由不能引用改写表"在两处校验：路由改为引用改写表时（`RouteService`），以及保存入站时（`WorkerConfigDependencyResolver.validateInboundReferences`）。
- 合并工具的"顺序变化"提示只列出可能与被合并域名重叠的路由：条件全部是具体域名、且与被合并的域名没有后缀重叠的路由不提示；改写表路由按表中条目判断；规则集、GEO、`NOT_IN` 一律提示。被合并的已禁用路由，生成的条目也是禁用状态。
- 改写表的文本导入、导出在前端编辑器内完成，保存时统一走整表替换。后端的 `/import`、`/export` 接口保留，供命令行使用。
- 未实现：同一绑定引用的多张改写表之间出现同名域名模式时的保存警告（第 5 节），留待需要时补充。

验证：

- 迁移等价：清理后的生产库副本执行 V2 后，`default`、`happy-bounty-1` 两个 worker 的下发配置哈希与迁移前完全一致（`44e90dba…`、`a3732c43…`），也与线上 `worker_status.applied_config_hash` 一致。
- 打包运行：用副本（已清空 worker 管理地址、停用邮件与计划任务）启动打包后的控制端，Java 迁移耗时 8 ms，出站和路由接口返回符合预期。
- 合并演练：按第 14 节的分组，在副本上合并出日常服务（11 条）、管理面板（5 条）、服务器 SSH（3 条）、个人专属（3 条）四张表，均未出现权限扩大。合并后逐个绑定比较：改写条目集合相同，其他路由顺序相同，兜底路由相同。SOCKS5 主力设备的绑定从 26 条减少到 8 条，透明代理 cmg 组从 22 条减少到 8 条，内网 HTTPS 从 11 条减少到 5 条。
- 测试：control-manager 211 个测试通过（1 个需手动传参的导出工具跳过），前端 88 个测试通过，`npm run build` 对本次改动的文件没有新增警告。
- 未做：管理台页面的浏览器手工验证与截图；生产环境上线。
