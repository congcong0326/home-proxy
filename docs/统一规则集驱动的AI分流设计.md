# 统一规则集驱动的 AI 分流设计

## 1. 目标

为现有代理系统设计一套可持续演进的分流体系，满足以下目标：

- 支持 AI 域名分流
- 能灵活复用 GitHub 上持续维护的成熟规则集
- 降低新增分类时的前后端与 worker 适配成本
- 让规则更新从“发版行为”变成“配置行为”
- 为后续 GEO、广告、流媒体、SaaS、开发工具等分流场景复用同一套机制

本方案的核心结论是：

- 不再为每一类业务域名单独增加一个硬编码路由类型
- 引入“统一规则集 RuleSet”作为分流基础能力
- 由控制面负责导入、合并、发布规则集
- 由 worker 只负责消费已发布规则并执行匹配

## 2. 当前现状与问题

当前系统中的路由逻辑主要建立在以下基础上：

- `RouterService` 按 `RouteConditionType` 固定枚举执行匹配
- GEO / 广告等域名分类依赖 `DomainRuleEngine + loader`
- loader 直接消费 txt 规则
- 前端表单写死支持的条件类型

现有实现的问题：

### 2.1 扩展新分类需要改代码

如果现在要支持 “AI 域名分流”，继续沿用旧思路会变成：

- common 层新增一个枚举
- worker 新增一个匹配分支
- 再写一个新的规则 loader
- 控制面和前端增加一个新选项

这意味着每新增一个分类，都要重新走一遍开发、联调、发版。

### 2.2 规则来源不统一

当前规则来源混合了几种方式：

- worker 内置 loader 拉取远端规则
- 仓库本地缓存 txt
- 控制面的 routes JSON

结果是：

- 规则版本不可见
- 多 worker 可能不一致
- 控制面无法审计规则来源与变更
- 上游仓库一旦变化，影响不可控

### 2.3 AI 域名本质上不是“路由类型”

AI 域名不是一个稳定不变的技术概念，而是一个持续演进的业务分类。

例如：

- OpenAI
- Claude
- Gemini
- Copilot
- Perplexity
- 各种 AI 图片、代码、搜索服务

这些集合会变化、拆分、合并，因此它本质上应该是：

- 一个可维护的数据对象
- 一个可版本化的规则集
- 一个能被路由引用的配置实体

而不是一个新枚举。

## 3. 设计原则

### 3.1 规则与引擎解耦

规则来源、规则格式、规则分类不应耦合在 worker 代码里。

### 3.2 上游可变，内部稳定

GitHub 仓库可以变化，但内部暴露给路由系统的能力必须稳定。

因此路由只能引用内部 `ruleSetKey`，不能直接绑定外部 URL 或仓库路径。

### 3.3 发布优先于在线直连

生产环境不应依赖 worker 直接在线拉 GitHub 规则。

正确方式是：

- 控制面导入
- 校验
- 编译
- 发布
- worker 消费已发布版本

### 3.4 支持多源合并

单一上游很难同时满足：

- 规则基础语义稳定
- 细分类目充足
- 更新频率高
- 误伤率可控

因此必须支持多源合并与本地覆盖。

## 4. 推荐的总体方案

引入“统一规则集驱动路由”架构。

整体分为四层：

1. 上游规则源 `RuleSource`
2. 内部规则集快照 `RuleSetSnapshot`
3. 已发布规则集 `RuleSet`
4. 路由引用 `RouteRule -> ruleSetKey`

### 4.1 目标形态

当前路由规则：

```json
{
  "conditionType": "GEO",
  "op": "IN",
  "value": "CN"
}
```

演进后的推荐形态：

```json
{
  "conditionType": "RULE_SET",
  "op": "IN",
  "value": "ai-common"
}
```

其中：

- `RULE_SET` 是通用条件类型
- `ai-common` 是内部规则集 key
- 规则集的来源、内容、版本、发布状态由控制面管理

## 5. GitHub 规则源策略

这部分是本方案的关键。

重点不是“允许填 GitHub URL”，而是要把成熟 GitHub 规则源变成可配置、可组合、可发布的输入层。

### 5.0 最终结论：不是三选一，也不是一次性全做

这里需要明确：

- 架构上，系统设计为支持多来源
- 落地上，不建议一开始把三个来源一次性全部深度适配完
- 更合理的方式是“统一模型，多源接入，分阶段落地”

也就是说：

- 控制面要具备接入多个外部来源的能力
- 但实际开发时，按业务价值和实现复杂度分阶段支持
- worker 永远不直接理解这三个仓库的格式
- worker 只消费控制面下发的内部标准化 RuleSet

因此，本方案不是：

- “只选一个仓库，后面永远绑定它”

也不是：

- “三个仓库同时在 worker 里各写一套运行时解析逻辑”

而是：

- 控制面做多源适配
- worker 只认内部规则集

### 5.0.1 推荐的分阶段适配策略

#### P0：先适配两个来源

优先适配：

- `v2fly/domain-list-community`
- `blackmatrix7/ios_rule_script`

原因：

- `v2fly/domain-list-community` 是基础规则语义来源，适合做 GEO / 通用分类 / geosite 基础能力
- `blackmatrix7/ios_rule_script` 对 AI、流媒体、SaaS、厂商分类更细，最贴近你当前 AI 分流诉求

这一阶段就已经能覆盖：

- AI 分流
- 非中国大陆分流
- 广告或基础分类分流

#### P1：再适配 `MetaCubeX/meta-rules-dat`

用途定位：

- 作为增强产物源或分发源
- 用来减少大规则集的自行编译成本
- 用于补足某些现成规则产物

原因：

- 它更适合作为“聚合产物来源”
- 不是当前 AI 分流最小闭环的必需前置

所以我的建议不是“一开始三个都做完”，而是：

- 先把基础能力链路做通
- 再把 `MetaCubeX` 接进来做增强和加速

### 5.0.2 三个来源在系统中的角色不是平级替代关系

要避免把这三个来源理解成“只能选一个”。

更准确的定位是：

- `v2fly/domain-list-community`：基础规则语义源
- `blackmatrix7/ios_rule_script`：AI / 垂直分类专项源
- `MetaCubeX/meta-rules-dat`：增强产物源 / 分发源

所以它们不是互斥关系，而是不同角色。

### 5.0.3 worker 不直接适配三个来源

这里再强调一次落地边界：

- 外部来源的适配发生在控制面
- worker 不解析 GitHub 仓库格式
- worker 不感知仓库名、分支、路径、yaml、txt、dat

worker 只感知：

- `ruleSetKey`
- `matchTarget`
- 标准化后的规则项

这能把复杂度稳定在控制面，避免数据面随着来源增加而膨胀。

### 5.1 来源分层

建议把上游来源分成四层。

#### L1. 基础语义源

推荐：

- `v2fly/domain-list-community`

用途：

- 作为 geosite 规则语义基础
- 适合作为基础分类源
- 适合做 include、属性过滤、基础域名集合

适合承载：

- `builtin:geo-cn`
- `builtin:geo-foreign`
- `google`
- `microsoft`
- `github`
- `category-dev`

特点：

- 社区成熟
- 规则语义稳定
- 适合作为“基础事实来源”

#### L2. 聚合分发源

推荐：

- `MetaCubeX/meta-rules-dat`
- `Loyalsoldier/v2ray-rules-dat`

用途：

- 提供编译后的规则分发产物
- 提供增强版 geosite / geoip
- 减少自己从头处理大规则集的成本

特点：

- 更适合消费产物
- 更适合生产分发
- 不适合作为系统内部唯一真相源

#### L3. 垂直专项源

推荐：

- `blackmatrix7/ios_rule_script`

用途：

- AI 厂商分类
- 流媒体平台分类
- SaaS 分类
- 开发工具、厂商细分类

特点：

- 分类细
- 更新快
- 非常适合 AI 专项分流

适合承载：

- `ai-openai`
- `ai-anthropic`
- `ai-gemini`
- `ai-mixed`

#### L4. 本地覆盖层

这一层必须由你自己的系统掌控。

内容包括：

- 手工追加域名
- 手工排除误伤域名
- 临时应急规则
- 灰度规则
- 运行日志回灌的补充域名

这一层不能继续依赖仓库里的 txt 文件，必须进入数据库管理。

### 5.2 推荐的来源组合

#### 方案 A：稳定优先

- 基础源：`v2fly/domain-list-community`
- 分发源：`MetaCubeX/meta-rules-dat`
- 本地覆盖：数据库 overlay

适合：

- 优先考虑稳定性
- 规则语义优先

#### 方案 B：AI 分类优先

- 基础源：`v2fly/domain-list-community`
- AI 专项源：`blackmatrix7/ios_rule_script`
- 本地覆盖：数据库 overlay

适合：

- AI 分流是当前核心诉求
- 需要更细的厂商维度划分

#### 方案 C：生产实用优先

- GEO / 代理大类：`Loyalsoldier/v2ray-rules-dat`
- 通用 geosite：`v2fly/domain-list-community`
- AI 专项：`blackmatrix7/ios_rule_script`
- 最终统一发布：控制面内部 RuleSet

这是最推荐的组合。

原因：

- 通用规则有稳定基础
- GEO 大类有增强来源
- AI 分类有专项来源
- 最终对 worker 暴露的是内部统一模型

### 5.3 每个来源适配后的具体使用方式

适配完成后，不是让前端或路由直接选 GitHub 仓库，而是通过“来源绑定到内部规则集”的方式使用。

#### 5.3.1 `v2fly/domain-list-community` 的使用方式

主要用途：

- 构建基础规则集
- 构建 builtin 规则集
- 提供稳定的 geosite 语义来源

推荐用法：

- `builtin:geo-cn`
- `builtin:geo-foreign`
- `google`
- `microsoft`
- `github`
- `dev-common`

示例：

```text
RuleSource:
  repo = v2fly/domain-list-community
  path = data/geolocation-!cn
  parserType = DLC_TEXT

导入后发布为:
  ruleSetKey = builtin:geo-foreign
```

使用时，路由引用的不是仓库路径，而是：

```json
{
  "conditionType": "RULE_SET",
  "op": "IN",
  "value": "builtin:geo-foreign"
}
```

#### 5.3.2 `blackmatrix7/ios_rule_script` 的使用方式

主要用途：

- 构建 AI 专项规则集
- 构建流媒体、SaaS、开发工具等细分规则集

推荐用法：

- `ai-openai`
- `ai-anthropic`
- `ai-gemini`
- `ai-copilot`
- `streaming-youtube`
- `saas-notion`

示例：

```text
RuleSource:
  repo = blackmatrix7/ios_rule_script
  path = rule/Clash/OpenAI/OpenAI.yaml
  parserType = CLASH_YAML

导入后发布为:
  ruleSetKey = ai-openai
```

然后再基于多个内部规则集组合出：

```text
ai-common = ai-openai + ai-anthropic + ai-gemini + local-overlay
```

路由使用时直接引用：

```json
{
  "conditionType": "RULE_SET",
  "op": "IN",
  "value": "ai-common"
}
```

#### 5.3.3 `MetaCubeX/meta-rules-dat` 的使用方式

主要用途：

- 引入聚合产物
- 作为增强规则来源
- 作为部分大规则集的快速导入源

推荐用法：

- 补充 GEO 规则产物
- 补充通用分类规则产物
- 在后续阶段作为规则编译与分发加速来源

不建议在第一阶段把它作为唯一事实来源。

更合适的方式是：

- 作为某些内部规则集的一个输入来源
- 或作为预编译产物缓存来源

示例：

```text
RuleSource:
  repo = MetaCubeX/meta-rules-dat
  path = 某个可消费规则产物
  parserType = 对应产物解析器

导入后:
  绑定到 builtin / common 类型的内部规则集
```

### 5.4 前端和路由层如何使用

适配外部来源后，前端和路由层的使用方式必须统一。

正确方式：

1. 运维在“规则源管理”中配置外部来源
2. 运维在“规则集管理”中把一个或多个来源绑定到内部规则集
3. 控制面同步、合并、校验、发布
4. 路由页只选择内部规则集 key
5. worker 只匹配内部规则集

也就是说：

- 外部来源给规则集管理页使用
- 内部规则集给路由页使用

前端路由页不直接展示：

- GitHub 仓库名
- 仓库路径
- 分支名
- 原始 yaml / txt / dat 文件

前端路由页只展示：

- AI 通用
- OpenAI
- Claude
- Gemini
- 非中国大陆
- 广告域名

### 5.5 推荐的最小可用落地组合

如果目标是尽快把 AI 分流做起来，我建议文档明确采用下面的策略：

#### 第一阶段实际要做的

- 适配 `v2fly/domain-list-community`
- 适配 `blackmatrix7/ios_rule_script`
- 支持内部 RuleSet
- 路由支持 `RULE_SET`

#### 第一阶段先不做深度适配的

- `MetaCubeX/meta-rules-dat`

原因：

- 它不是 AI 分流最小闭环必需项
- 放到第二阶段更稳妥

#### 第一阶段上线后的使用方式

- GEO / 基础规则：来自 `v2fly`
- AI 分类规则：来自 `blackmatrix7`
- 业务微调：来自本地 overlay
- 路由只引用内部 key

这能在实现复杂度和业务价值之间取得最好的平衡。

## 6. 内部规则模型设计

### 6.1 RuleSource

用于描述“从哪里拉规则、怎么解析”。

建议字段：

- `id`
- `sourceKey`
- `name`
- `sourceType`
  - `GIT_RAW_FILE`
  - `HTTP_FILE`
  - `MANUAL_TEXT`
- `repo`
- `ref`
- `path`
- `url`
- `parserType`
  - `DLC_TEXT`
  - `CLASH_YAML`
  - `PLAIN_DOMAIN`
  - `CIDR_LIST`
  - `GEOSITE_DAT`
- `license`
- `enabled`
- `lastSyncAt`
- `lastSyncStatus`
- `remark`

### 6.2 RuleSet

用于描述“系统内部可被路由引用的规则集”。

建议字段：

- `id`
- `ruleKey`
- `name`
- `category`
  - `AI`
  - `GEO`
  - `AD`
  - `STREAMING`
  - `SAAS`
  - `DEV`
  - `CUSTOM`
  - `BUILTIN`
- `matchTarget`
  - `DOMAIN`
  - `IP_CIDR`
- `builtin`
- `enabled`
- `description`
- `publishedVersion`
- `createdAt`
- `updatedAt`

### 6.3 RuleSetSourceBinding

用于描述一个规则集由哪些来源组合而成。

建议字段：

- `id`
- `ruleSetId`
- `sourceId`
- `orderNo`
- `mode`
  - `INCLUDE`
  - `EXCLUDE`
- `selector`
  - 类别名
  - 文件路径
  - 需要提取的子规则
- `attributeFilter`
- `enabled`

### 6.4 RuleSetVersion

用于保存每次导入、编译后的版本快照。

建议字段：

- `id`
- `ruleSetId`
- `versionNo`
- `contentHash`
- `rawContent`
- `compiledSummary`
- `status`
  - `DRAFT`
  - `SYNCED`
  - `COMPILED`
  - `PUBLISHED`
  - `FAILED`
- `publishedAt`
- `createdAt`

## 7. 规则导入与发布流程

### 7.1 同步流程

1. 控制面按 `RuleSource` 拉取上游内容
2. 按 `parserType` 解析
3. 生成标准化中间格式
4. 按 `RuleSetSourceBinding` 合并
5. 应用本地 include / exclude overlay
6. 生成版本快照
7. 校验后发布

### 7.2 标准化中间格式

建议在控制面内部统一转换成如下结构：

```json
{
  "domains": [
    {
      "type": "DOMAIN",
      "value": "openai.com"
    },
    {
      "type": "DOMAIN_SUFFIX",
      "value": "claude.ai"
    }
  ],
  "cidrs": [],
  "meta": {
    "upstream": "blackmatrix7/ios_rule_script",
    "sourcePath": "rule/Clash/Claude/Claude.yaml"
  }
}
```

这样可以把不同来源格式统一掉，避免 worker 识别多种上游格式。

### 7.3 发布流程

生产环境只使用“已发布版本”。

约束如下：

- 同步失败不影响当前已发布版本
- 编译失败不影响当前已发布版本
- 发布前支持 diff 预览
- 发布前支持测试域名回归

## 8. AI 规则集构建方案

### 8.1 推荐的内部规则集

建议内部先定义以下规则集：

- `ai-common`
- `ai-openai`
- `ai-anthropic`
- `ai-gemini`
- `ai-copilot`
- `ai-search`
- `ai-image`

### 8.2 组合方式

例如：

#### `ai-openai`

来源：

- `blackmatrix7/OpenAI`
- 本地补充
- 本地排除

#### `ai-anthropic`

来源：

- `blackmatrix7/Claude`
- 本地补充
- 本地排除

#### `ai-common`

来源：

- `ai-openai`
- `ai-anthropic`
- `ai-gemini`
- `ai-copilot`
- 本地补充
- 本地排除

也就是说：

- 前端和路由永远引用内部 key
- 外部 GitHub 文件路径只是“导入来源”
- 不是“运行时直接依赖”

### 8.3 为什么需要组合规则集

因为上游规则通常存在这些问题：

- 分类风格不一致
- 命名不一致
- 某些厂商归类过粗
- 存在误伤或漏匹配

内部组合层可以解决：

- 多源聚合
- 精细补丁
- 规则降噪
- 历史兼容

## 9. 路由系统改造建议

### 9.1 第一阶段最小改造

保留现有 `RouteRule` 结构，新增一个通用条件类型：

- `RouteConditionType.RULE_SET`

示例：

```json
{
  "conditionType": "RULE_SET",
  "op": "IN",
  "value": "ai-common"
}
```

worker 执行时只做一件事：

- 用 `ruleSetKey` 找到对应规则集
- 对目标 host 执行匹配

### 9.2 兼容旧能力

现有能力可以逐步映射为 builtin rule set：

- `GEO CN` -> `builtin:geo-cn`
- `GEO NOT CN` -> `builtin:geo-foreign`
- `AD_BLOCK` -> `builtin:ad`

这样第一阶段不需要一次性推翻现有实现。

### 9.3 路由优先级建议

建议优先级如下：

1. 精细业务规则
2. AI / 流媒体 / SaaS / 厂商分类规则
3. 广告规则
4. GEO 大类规则
5. 兜底规则

避免宽泛规则抢先命中。

## 10. 聚合配置与 worker 设计

### 10.1 聚合配置新增内容

控制面下发聚合配置时，除了现有：

- `inbounds`
- `routes`
- `rateLimits`
- `users`

还要新增：

- `ruleSets`

示例：

```json
{
  "configHash": "xxx",
  "routes": [
    {
      "name": "AI 专线",
      "rules": [
        {
          "conditionType": "RULE_SET",
          "op": "IN",
          "value": "ai-common"
        }
      ],
      "policy": "OUTBOUND_PROXY",
      "outboundTag": "ai-proxy"
    }
  ],
  "ruleSets": [
    {
      "key": "ai-common",
      "matchTarget": "DOMAIN",
      "version": 12,
      "items": [
        { "type": "DOMAIN", "value": "openai.com" },
        { "type": "DOMAIN_SUFFIX", "value": "claude.ai" }
      ]
    }
  ]
}
```

### 10.2 worker 的职责

worker 不再：

- 自己去 GitHub 拉取业务规则
- 自己决定哪些规则可用

worker 只负责：

- 加载控制面已发布规则集
- 编译为内存匹配结构
- 在路由时执行匹配

### 10.3 worker 内部建议组件

- `RuleSetRegistry`
- `RuleSetCompiler`
- `RuleSetMatcher`

匹配结构可以复用并增强当前实现：

- `DomainTrie`
- `FullMatcher`
- `SuffixMatcher`
- `KeywordMatcher`
- `CidrMatcher`

## 11. 前端设计建议

### 11.1 新增“规则集管理”页面

建议提供：

- 规则集列表
- 分类筛选
- 来源列表
- 同步按钮
- 发布按钮
- 版本历史
- diff 预览
- 测试命中

### 11.2 路由页改造

在路由表单中：

- 条件类型新增 `RULE_SET`
- 当选择 `RULE_SET` 时，值使用下拉选择内部规则集

例如：

- AI 通用
- OpenAI
- Claude
- Gemini
- 非中国大陆
- 广告域名

前端不需要理解上游 GitHub 规则，只需要引用内部 key。

### 11.3 运维辅助能力

建议新增：

- 域名测试工具
- 路由仿真工具
- 规则命中链路展示

输入一个域名后，应能看到：

- 命中了哪个 RuleSet
- 命中了哪条路由
- 使用了哪个出站

## 12. 风险控制

### 12.1 上游变更风险

风险：

- 仓库路径变化
- 分类删除
- 格式变化
- 规则误更新

应对：

- 导入与发布解耦
- 保留最后一个可用版本
- 发布前人工审核
- 发布前回归测试

### 12.2 许可证风险

不同 GitHub 仓库许可证不同。

建议：

- 在 `RuleSource` 中记录 license
- 在规则集详情页展示来源与许可证
- 内部保留来源追踪

### 12.3 性能风险

规则集变多后，worker 内存和匹配耗时会上升。

应对：

- 只下发启用且已发布的规则集
- 使用编译后的 trie / suffix index
- 对超大规则集按类别拆分

## 13. 分阶段实施建议

### P0

- 新增 `RuleSource / RuleSet / RuleSetVersion` 数据模型
- 路由新增 `RULE_SET`
- 聚合配置新增 `ruleSets`
- worker 新增 `RuleSetRegistry`
- 前端路由表单支持选择规则集
- 先接入 AI 规则集

### P1

- 新增规则集管理页
- 支持 GitHub 规则导入
- 支持版本管理与发布
- 支持测试命中

### P2

- 把 GEO / AD 等内置能力迁移为 builtin rule set
- 完全收敛 worker 侧直连 GitHub 的 loader
- 增加回归样本测试与 diff 审核

## 14. 最终建议

针对你的场景，推荐最终落地路径如下：

### 14.1 短期

先做最小闭环：

- 路由引入 `RULE_SET`
- 控制面支持内部规则集
- AI 分流先用 `blackmatrix7/ios_rule_script` 作为专项来源
- 本地维护 overlay

### 14.2 中期

把规则来源标准化：

- `domain-list-community` 做基础源
- `Loyalsoldier / MetaCubeX` 做增强和产物源
- `blackmatrix7` 做专项细分类

### 14.3 长期

把“分流能力”彻底升级为“规则平台能力”：

- 一个统一规则中心
- 多来源导入
- 多规则集组合
- 多业务模块复用

## 15. 结论

AI 域名分流不是单独加一套 loader 就能长期解决的问题。

正确方向是：

- 把 GitHub 成熟规则源纳入控制面
- 通过统一 RuleSet 模型做标准化
- 通过快照、版本、发布机制控制风险
- 让路由只引用内部规则集 key

这样你得到的不是“一次性的 AI 分流支持”，而是一套可持续扩展的分流基础设施。
