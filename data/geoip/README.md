# GeoIP 数据目录

私有容器镜像构建前，将 MaxMind `GeoLite2-City.mmdb` 放到本目录：

```text
data/geoip/GeoLite2-City.mmdb
```

`*.mmdb` 已在 Git 中忽略，不要提交数据库文件。`deploy/docker/Dockerfile.control-manager`
和 `deploy/docker/Dockerfile.proxy-worker` 会把本目录复制到镜像内的
`/app/data/geoip/`。
