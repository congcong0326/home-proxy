# 网关观测：访问分布

## 业务取舍

原 `AggregatedAnalysis` 的用户、应用（实际是目标主机）、用户－应用、路由规则 TopN 迁移能力以独立页面提供：`/config/observability/distribution`，导航为“网关观测 → 访问分布”。目标字段名称改为“目标主机”；新增来源 IP、流量分类维度。指标提供连接数、上行、下行和总流量。

连接维度不提供来源国家和目标国家：V1 连接事实没有对应 GeoIP 字段。认证地理位置作为独立认证尝试维度提供，具体口径见下文；DNS、认证与诊断事件不计入连接 TopN。

旧 `/config/overview/aggregate`、`getAccessDailyTop`、旧日志采集和数据库均保留；新页提供旧版链接。新增查询不修改配置、采集协议或数据库结构，也不替换旧入口。此次交付独立预览，已按用户要求更新本地管理端容器，不表示其他阶段已验收。

## 口径与 API

`GET /api/observability/v1/distribution` 参数：

| 参数 | 说明 |
| --- | --- |
| `workerId` | 必需，单节点历史范围 |
| `from` / `to` | 必需，UTC Instant，半开区间 `[from,to)`，最长 93 天 |
| `dimension` | `users`、`targets`、`user_targets`、`routes`、`client_ips`、`categories` |
| `metric` | `connections`、`bytes`、`upload`、`download` |
| `limit` | 1–100，默认 10 |
| `trafficCategory` | 默认 `EXTERNAL`，支持四分类或 `ALL` |
| `connectOutcome` | 默认 `ALL`，支持五种原始建连结果 |
| `username` / `clientIp` | 可选，精确匹配连接事实，不把失败认证尝试账号当成已识别用户 |

- 仅查询 `telemetry.proxy_session_log_v1 FINAL`。去重单位是 V1 唯一结束汇总，不从审计分页抽样；每条记录计一次连接，失败／阻断／取消／未尝试也属于选中结果。
- `occurred_at` 为连接结束时间。前端按浏览器本地日期转换 UTC，结束日期包含全天。不会把完整连接字节解释为逐分钟实时传输量。
- 用户分组保留 user ID 和当时用户名；路由保留规则 ID 和名称快照。名字变更可以形成多个历史组；不存在的配置不影响历史排行。不把未知用户合并进任何已确认用户。
- 目标分组使用 `logical_target_host`，保留日志原始值，不识别应用、不按最终 IP 或代理对端替代原始目标；未恢复目标保持未知。
- 流量相加前转换 UInt128，所有计数和流量返回十进制字符串。前端使用 BigInt 计算占比和格式化，悬停可看完整字节数；图形几何使用浮点近似，提示和表格保留精确值。
- 窗口求和在 LIMIT 之前执行，返回全量 `totals`、`totalValue`、`groups`、`missingConnections`；`otherValue` 是全部减去 TopN 的排序指标，饼图显式包含“其余项目”。零流量不绘制占比，未知维度仍保留在列表和分母。
- `missingConnections` 是当前分组需要的维度缺失的连接数。用户－目标任一缺失即计入，未分类也计入该维度的缺失统计。它不是日志丢失率。
- `firstSessionAt` 为选中节点最早保留的连接记录，与用户、结果、类别筛选无关；不是完整覆盖保证，历史起点受保留策略影响。该字段保留在 API，按用户要求不再显示页面底部的口径／历史说明。
- 未启用查询或数据库失败返回 503，非法参数返回 400。页面显示错误、清空旧结果；不伪造零流量或自动混入旧源。

## 验证

后端单元测试与可选只读真实 ClickHouse 测试：

```bash
DISTRIBUTION_CLICKHOUSE_CONTAINER=home-proxy-clickhouse \
  mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl control-manager -am test -DskipTests=false \
  -Dtest=DistributionServiceTest,DistributionControllerTest,DistributionClickHouseIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

实测 7 项通过。真实 ClickHouse 24.8 使用内联只读样本覆盖六维度 × 四指标、总和超过 UInt64、未知维度、跨节点隔离、半开区间和筛选值含引号；另查询现有 `home-01` 历史，和独立 `FINAL count()` 核对。测试不写表或业务配置；未指定容器时跳过 IT。

前端：

```bash
cd frontend
CI=true npm test -- --watch=false --runInBand --runTestsByPath \
  src/pages/ObservabilityDistribution.test.tsx src/pages/ProxyConfig.test.tsx
```

实测 10 项通过：全量占比及其余项目、大数、筛选、无效日期、切换节点的迟到响应、服务不可用、导航兼容。`make frontend-build` 通过，既有页面的 lint 警告仍存在；新页面无新增 lint 警告。

浏览器布局验证：先构建前端并用本地静态服务器提供 `frontend/build`，再运行：

```bash
python3 -m http.server 18767 --bind 127.0.0.1 --directory frontend/build
# 另一个终端；NODE_PATH 指向环境已有的 Playwright 安装
NODE_PATH=/tmp/home-proxy-b1-browser/node_modules \
  E2E_SCREENSHOT_DIR=docs/testing/assets/gateway-distribution \
  node scripts/e2e/gateway-observability-distribution-browser.cjs
```

浏览器验证使用明确的 UI 样例响应，不连接或写入已部署网关；SQL 正确性由上述真实数据库测试验证。检查桌面、用户－目标与流量筛选、390px 手机宽度、失败后无旧总量，无页面错误、无文档横向溢出。

预览截图：[桌面](assets/gateway-distribution/desktop.png)、[用户－目标](assets/gateway-distribution/targets.png)、[手机](assets/gateway-distribution/mobile.png)。

## 本地容器更新（2026-10-09）

按用户要求执行 `make docker-build-control`，随后以当前容器环境覆盖 Compose 默认配置，仅重建管理端：

```bash
docker compose -f deploy/docker/docker-compose.control.yml \
  -f /tmp/distribution-control-runtime.json \
  up -d --no-deps --force-recreate control-manager
```

运行环境与数据挂载核对一致，MySQL、ClickHouse 和 home-01 Worker 容器 ID 未变，观测 ingest/query 开关仍为 true。页面地址为 `https://127.0.0.1:18443/config/observability/distribution`。

真实登录后的浏览器验证通过：默认外网查询返回 24 条连接；六维度 × 四指标的 24 次 API 检查均返回 200，并验证流量相加与 TopN／其余项目的分母关系；目标／总流量筛选及 390px 手机布局通过，无页面错误、无文档横向溢出。本次查询没有写入代理配置或日志事实。

真实数据截图：[桌面](assets/gateway-distribution/live/desktop.png)、[目标排行](assets/gateway-distribution/live/targets.png)、[手机](assets/gateway-distribution/live/mobile.png)。构建日志在 `/tmp/distribution-deploy-build.log`，浏览器报告在 `/tmp/distribution-live-report.json`。

更新前镜像保留为 `home-proxy-control-manager:before-distribution-20261009T121614Z`；需要容器回退时使用该镜像并沿用原运行环境，无需清理数据卷。

## 恢复原聚合分析布局（用户审阅调整）

按用户要求恢复 `AggregatedAnalysis` 的页面组织：Ant Design 下拉框和日期范围选择器、响应式筛选栏、全宽 TopN 表格、下方独立饼图。去掉自绘原生控件和四张独立汇总卡片，汇总数据改为表格上方文字；表格按当前指标展示排行与占比，用户／来源 IP 收进“更多筛选”。饼图恢复标签和图例，节点、分类、结果、新源口径与大数精度保留。

沿用现有 4 项页面测试验证筛选、清空日期、迟到响应、查询失败及大数展示，全部通过；生产构建和管理端镜像构建通过。仅重建本地管理端，运行环境及 Worker／MySQL／ClickHouse 容器保持原状态。实际浏览器验证节点初始数据、分类／目标／流量指标切换、更多筛选中的用户名提交、390px 手机布局通过，无页面错误或文档横向溢出；上述真实数据截图已更新为此布局。

此轮构建记录为 `/tmp/distribution-layout-deploy.log`，测试记录为 `/tmp/distribution-layout-tests.log`，真实浏览器记录为 `/tmp/distribution-layout-live.log`。调整前镜像保留为 `home-proxy-control-manager:before-distribution-layout-20261009T122734Z`，可沿用 `/tmp/distribution-layout-runtime.json` 的现有配置回退。

## 页面文案清理（用户审阅）

2026-10-09 按用户要求检查未提交改动中的页面，清理访问分布底部的统计口径段落、日志审计中的数据源／分页实现／重复 UTC 范围／关联覆盖说明，以及入站流量悬浮提示中的历史起点和口径说明。保留查询错误、实际日志字段、分类流量与字段问号帮助；查询及 API 契约不变。

三个页面的现有测试共 41 项分别验证通过；访问分布的交互测试超时调整为 15 秒，并重置 mock，避免多套 Ant Design 测试连续运行时的超时串扰。生产构建与镜像构建通过，仅更新本地管理端；实际浏览器检查三个页面与连接／认证详情、入站悬浮提示均通过，无页面错误。更新前镜像为 `home-proxy-control-manager:before-copy-cleanup-20261009T123703Z`。测试与构建日志为 `/tmp/observability-copy-{tests,distribution-tests,deploy,browser}.log`。

清理后的页面：[访问分布](assets/copy-cleanup/distribution.png)、[日志审计](assets/copy-cleanup/audit.png)、[连接详情](assets/copy-cleanup/session-detail.png)、[入站流量](assets/copy-cleanup/inbound.png)。

## 风险与回退

直接读取原始事实并全量分组，93 天高基数目标可能增加查询成本。当前限制单节点、93 天、Top100，未进行大规模容量压测。部署后应按实际规模评估查询耗时，再考虑独立物化聚合；不能通过截断输入样本改善速度而改变分母。

回退使用旧聚合分析入口即可，无需改表或 Worker；移除新导航／路由亦不影响现有业务。此次不清理旧代码、旧表和旧查询依赖。

## 认证地理位置（三元组，2026-10-10）

页面将“来源 IP”维度替换为“认证地理位置”；来源 IP 精确筛选继续保留，后端旧 `client_ips` 维度兼容保留。新 `dimension=auth_geo` 仅支持 `metric=authentications`，独立查询 `telemetry.credential_auth_log_v1 FINAL`，以认证发生时间和选中节点限定范围，最长 93 天。`authOutcome=ALL/SUCCESS/FAILURE` 默认全部；失败包含 FAILURE、DENIED、MALFORMED、ERROR，CHALLENGE、CANCELLED 和未记录结果计入其他。用户名精确匹配尝试账号、已验证账号或原始 username，不受连接结果和流量分类影响。

ClickHouse 先按来源 IP 全量计数，管理端复用 `CredentialGeoAnalysis` 解析每个 IP，再按国家、地区、城市三个独立字段合并。三字段统一去除首尾空白，null、空字符串、空白均归一为空值；只在前端拼接展示，例如“中国 / 地区未知 / 城市未知”。内网来源单独归为“内网来源”，三个位置字段都缺失的归为“位置未知”。未知原因按查询状态计数，提示区分数据库不可用、未收录、解析失败等；位置未知为灰色扇区。

统计使用查询时 GeoIP 数据库，因此数据库恢复后历史记录也可以定位，无需改写事实或迁移表；审计原来已存储的位置仍保留历史分析，数据库更新后可能与当前统计有差异。所有认证次数（含未知、内网）先合计分母再做 TopN，剩余部分显示“其他位置”，所有计数及占比使用大整数；认证响应返回 `authTotals`、每项 `auth` 和 `sources`，不伪造连接数和流量字段。成功、失败与其他之和等于认证次数。

点击表格位置或饼图扇区可查看该位置的来源 IP、次数和成功／失败情况，再选择来源 IP 查看认证审计与完整详情。明细复用独立账号精确筛选 `exactAuthUsername` 和认证结果组筛选 `authOutcomeGroup`，避免失败统计进入明细后混入成功记录；两项仅适用于认证审计。超过 31 天的范围拆成连续半开时间段供用户选择，统计范围和分母不变。来源 IP 缺失的记录仍保留计数，但不以空筛选打开所有认证记录。

无需变更 common DTO、Worker 采集和数据库。与原有连接聚合相比，新维度增加按 IP 的 GeoIP 查询与管理端内存合并，TopN 项携带完整 IP 列表；大规模来源数量下仍需评估查询、响应体和内存成本，不能提前截断来源而改变分母。回退可恢复原前端来源 IP 选项，旧接口与表均保留。

验证命令：

```bash
mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl control-manager -am test -DskipTests=false \
  -Dtest=CredentialDistributionServiceTest,DistributionServiceTest,DistributionControllerTest,TelemetryAuditTest \
  -Dsurefire.failIfNoSpecifiedTests=false
cd frontend
CI=true npm test -- --watch=false --runInBand --runTestsByPath \
  src/pages/ObservabilityDistribution.test.tsx src/pages/ObservabilityLogAudit.test.tsx
```

覆盖三元组合并、null／空白归一、部分地理信息、内网／未知原因、超过 UInt64 的计数、全量分母与其余项、独立认证数据源、参数化账号筛选、认证失败分组、切换维度及来源 IP 明细关联。生产构建使用 `make frontend-build`。

验证结果：后端 17 项测试通过；前端访问分布 6 项和认证审计 28 项测试通过。ClickHouse 24.8 内联只读样本验证按 IP 分组、空 IP、成功／失败／其他结果计数。浏览器 UI 样例验证桌面、390px 手机布局、认证结果切换、来源 IP 审计抽屉和查询失败清空结果，无页面错误或文档横向溢出。截图：[认证地理位置](assets/gateway-distribution/auth-geo/desktop.png)、[手机](assets/gateway-distribution/auth-geo/mobile.png)、[来源明细](assets/gateway-distribution/auth-geo/detail.png)。前端生产构建通过，仅有既有页面 lint 警告。此次未更新运行中的管理端容器。
