# GeoIP 数据目录

私有容器镜像构建前，将 MaxMind `GeoLite2-City.mmdb` 放到本目录：

```text
data/geoip/GeoLite2-City.mmdb
```

`*.mmdb` 已在 Git 中忽略，不要提交数据库文件。`deploy/docker/Dockerfile.control-manager`
和 `deploy/docker/Dockerfile.proxy-worker` 会把本目录复制到镜像内的
`/app/data/geoip/`。

管理端认证审计也依赖此文件；只有本 README 时会显示“位置数据库不可用”。
可从 MaxMind GeoLite2 下载取得，或使用公开分发镜像
[P3TERX/GeoLite.mmdb](https://github.com/P3TERX/GeoLite.mmdb/releases)。本地修复使用其
`2026.10.07` 发布的 `GeoLite2-City.mmdb`，数据库未提交。

管理端缺失／加载失败时每分钟重试；文件恢复后，新日志和历史标记为
`DB_UNAVAILABLE` 的认证日志查询会重新解析，无需改写原始认证记录。
