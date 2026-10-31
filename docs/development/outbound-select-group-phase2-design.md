# 二期 SELECT 出站组设计

整体规划见 [路由与出站配置模型演进规划](route-outbound-model-roadmap.md)，前置依赖为 [一期 路由与出站拆分、改写表设计](route-outbound-split-phase1-design.md)。

## 1. 目标与范围

目标：在不修改路由的情况下，手动切换某类流量走哪个出站。典型场景是 Steam：有时下载需要科学上网，平时希望直连。

范围：

- 出站新增 `SELECT` 组，成员为若干普通出站，记录当前选中的成员。
- `FORWARD` 路由可以引用 SELECT 组。
- 管理台一键切换，约 2 秒内生效。
- 切换历史；可选的定时恢复。

不在本期：

- worker 改动。组在控制端编译为选中成员，worker 无感知。
- 按用户分别选择。
- 切换时断开该组已有的连接（需要 worker 新增管理指令）。
- 组嵌套。
- FALLBACK 等自动策略（三期另行设计）。

## 2. 生效机制

```
管理台切换 → 更新 selected_member_id → 清除 aggregateConfig 缓存
→ worker 下一次 CONFIG_POLL（默认 2 秒）时哈希不一致，下发新配置
→ ProxyContext.refresh：监听未变，updateRuntimeConfig 原子替换快照
```

- 新连接立即使用新出站，已建立的连接保持原出站直到结束。比如正在进行的 Steam 下载不会中断，也不会切换出口。界面需要说明这一点。
- 每次切换都会改变配置哈希。这正是预期的下发方式，代价是整份配置重新下发一次，在家庭规模下可以忽略。

## 3. 数据模型

迁移脚本 `V3__outbound_select_group.sql`。

```sql
ALTER TABLE outbounds ADD COLUMN selected_member_id INTEGER REFERENCES outbounds (id);
ALTER TABLE outbounds ADD COLUMN revert_member_id INTEGER REFERENCES outbounds (id);
ALTER TABLE outbounds ADD COLUMN revert_at TEXT;

CREATE TABLE outbound_group_members (
    group_id   INTEGER NOT NULL,
    member_id  INTEGER NOT NULL,
    sort_order INTEGER NOT NULL,
    PRIMARY KEY (group_id, member_id),
    CONSTRAINT fk_ogm_group  FOREIGN KEY (group_id)  REFERENCES outbounds (id) ON DELETE CASCADE,
    CONSTRAINT fk_ogm_member FOREIGN KEY (member_id) REFERENCES outbounds (id) ON DELETE RESTRICT
);

CREATE TABLE outbound_selection_history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id       INTEGER NOT NULL,
    from_member_id INTEGER,
    to_member_id   INTEGER NOT NULL,
    source         TEXT    NOT NULL,   -- MANUAL / SCHEDULED_REVERT
    operator       TEXT,
    changed_at     TEXT    NOT NULL,
    CONSTRAINT fk_osh_group FOREIGN KEY (group_id) REFERENCES outbounds (id) ON DELETE CASCADE
);
CREATE INDEX idx_osh_group_changed ON outbound_selection_history (group_id, changed_at);
```

- 组仍然是 `outbounds` 中的一行，`kind = SELECT`，与 Clash 的出站和组同属一个命名空间的做法一致。路由的 `outbound_id` 不用区分指向的是出站还是组。
- `selected_member_id`、`revert_*` 只对 `SELECT` 有意义。
- 成员删除受 `RESTRICT` 保护，必须先从组中移除。

## 4. 规则与校验

- 成员至少 1 个，不能是 `SELECT`（不允许嵌套），也不能是组自身。
- 成员类别必须一致：要么全部是连接类（`DIRECT`、`BLOCK`、`PROXY`），要么全部是 `DNS_UPSTREAM`。DNS 上游组可以用来切换 DNS 上游。
- `selected_member_id` 必须是组的成员。新建组时默认选第一个成员。从组中移除当前选中的成员时，自动改选第一个成员，并记一条历史。
- 只有 `FORWARD` 路由可以引用组。改写路由的出站本期固定为"直连"。
- `FakeIpConfigValidator` 基于编译结果校验，所以组切换到不合法的 DNS 上游时也会被拦截。切换接口在保存前先对受影响的 worker 做一次编译校验，失败则拒绝切换。

## 5. 编译

`RouteCompiler` 在处理 `FORWARD` 路由时，如果出站是 `SELECT`，就先解析为 `selected_member_id` 指向的成员，再按一期 4.1 的规则编译。

`RouteDTO.outboundTag` 写入 `组名/成员名`。worker 当前不使用这个字段，填写后便于在下发的配置快照中排查；遥测不受影响。

## 6. 定时恢复（可选，2b）

切换时可以附带 `revertAfterMinutes`，例如"切到科学上网，2 小时后恢复直连"：

- 保存 `revert_member_id`（切换前的成员）和 `revert_at`。
- 控制端用一个 `@Scheduled` 任务（每分钟）扫描 `revert_at <= now` 的组，执行恢复并记录 `SCHEDULED_REVERT` 历史。
- 期间如果有新的手动切换，清除 `revert_*`，以最后一次操作为准。
- 不复用邮件计划任务（`mail_biz`），两者语义不同。

## 7. API

| 接口 | 说明 |
|---|---|
| `POST/PUT /api/outbounds` | `kind=SELECT` 时请求体包含 `memberIds`（有序） |
| `PUT /api/outbounds/{id}/selection` | `{ "memberId": 3, "revertAfterMinutes": 120 }`，返回新的选择状态和预计生效说明 |
| `GET /api/outbounds/groups` | 所有 SELECT 组及其成员、当前选择、恢复计划，供快捷切换面板使用 |
| `GET /api/outbounds/{id}/selection-history` | 分页查询切换历史 |

切换接口单独提供，不复用出站的 `PUT`，以便与"编辑组成员"区分权限和历史记录。

## 8. 前端

- `OutboundManagement.tsx`：组以卡片展示，成员用单选按钮显示，点击即切换。卡片上显示恢复倒计时和最近一次切换时间。
- 组表单：选择成员并拖拽排序。
- `RouteForm.tsx` 的出站下拉框分为"出站"和"出站组"两组。
- 快捷切换：在 `Dashboard.tsx` 增加"出站组"卡片。手机上切换 Steam 是常见操作，`MobileStatusPanel.tsx` 也提供同样的入口。
- 切换确认文案说明"新连接立即生效，进行中的下载不受影响"。

## 9. Steam 配置示例

```
规则集  steam-store     商店、社区、登录
规则集  steam-download  steamcontent.com、*.steamserver.net 等下载 CDN

出站组  Steam 下载 (SELECT)   成员: [直连, 机场]   当前: 直连

路由    Steam 商店   规则集 steam-store    → 机场
路由    Steam 下载   规则集 steam-download → 出站组「Steam 下载」
```

把下载 CDN 单独拆出来以后，大部分时间都不需要切换，只有在直连下载慢时才切到"机场"。

## 10. 测试

- `RouteCompiler`：组解析为选中成员；`outboundTag` 格式；成员类别校验。
- 切换接口：非成员返回 400；编译校验失败时拒绝切换且不写历史；成功后缓存失效、哈希变化。
- 移除选中成员时自动改选并记录历史。
- 定时恢复：到期执行；手动切换取消恢复计划。
- 端到端：切换后约 2 秒内新连接的遥测出站变化，已有长连接不中断。
- 前端：组卡片切换、恢复倒计时、移动端面板。

## 11. 任务拆分

1. `V3` 迁移与实体、组校验。
2. `RouteCompiler` 支持组，切换接口与历史记录。
3. 前端组管理与快捷切换（桌面端、移动端）。
4. 定时恢复（2b，可以单独上线）。

## 12. 与三期的衔接

- 三期的多出站 fallback 定义在路由上，表现为有序的出站列表。SELECT 组可以作为列表中的一项：编译时先解析组，再生成候选列表，两者正交。
- 本期不在组上预留 fallback 字段，避免过早固定形态。
