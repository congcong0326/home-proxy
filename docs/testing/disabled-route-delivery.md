# 禁用路由的配置下发

修复仅涉及 control-manager，不修改 common 配置契约或 proxy-worker。

## 下发行为

- 聚合配置只包含当前节点入站引用的启用路由。
- 入站绑定中的禁用路由 ID 被过滤，其余路由顺序保持不变；禁用的兜底路由引用被清空。
- 即使绑定中的所有路由均被禁用，也保留绑定及用户列表，避免影响用户认证。
- 仅由禁用路由引用的规则集不再下发，也不再要求其保持发布状态。
- 过滤在独立的下发副本上进行，不修改数据库中的绑定关系；重新启用路由后，原有绑定和兜底引用自动恢复。
- 配置哈希根据过滤后的内容计算，worker 通过现有配置通道获取并应用变化。

禁用兜底路由后，请求仍可命中其他启用路由；没有匹配路由和兜底时，沿用现有 worker 的关闭连接行为，不自动直连。

Fake IP DNS 依赖真实 DNS 转发兜底。禁用该兜底时，下发副本同时将该 DNS 入站的 `fakeIpEnabled` 设为 `false`，使现有 worker 能接受配置；数据库中的 Fake IP 开关保留，重新启用兜底后恢复下发。Fake IP 组合校验使用过滤后的入站快照。

不存在的路由引用仍报错；用户、启用路由引用的规则集等校验保持原有要求。

## 验证

新增 `AggregateConfigServiceTest`，覆盖混合路由过滤与顺序、引用副本隔离、全部禁用时保留认证、禁用／重新启用及哈希变化、规则集依赖过滤、Fake IP 兜底禁用／恢复、空绑定和不存在的路由。

```bash
mvn -f backend/pom.xml -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl control-manager -am test -DskipTests=false \
  -Dtest=AggregateConfigServiceTest,WorkerConfigDependencyResolverTest,CanonicalConfigHasherTest,FakeIpConfigValidatorTest,RouteServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

上述 19 项测试全部通过。发布仅需更新 controller；回滚 controller 版本会恢复旧的下发行为。
