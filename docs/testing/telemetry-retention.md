# 遥测日志保留与部署验证

## 行为

- 会话、DNS、认证三张事实表，跨所有节点合计最多 100,000,000 条。
- 默认保留含当天的最近 90 个 UTC 自然日。例如 2026-10-31 清理时，保留起点为 2026-08-03 00:00:00 UTC（含）。
- 先删除过期日期，再检查总量；超限时找出全局最早日期，将该天在三张表中的数据全部删除，逐天重复直到满足上限。单日删除允许低于上限，必要时也会删除当天。
- 启动 60 秒后首次执行，此后每小时执行；每个删除事务最多 5000 条。两次检查之间可能暂时超限。
- 新旧 SQLite 遥测库统一生效，无需结构迁移。原有单表保留天数可以缩短全局保留期。默认值和环境变量见根目录 README。
- 流量统计等直接读取同一事实表，因此仅能统计仍保留的数据；删除后的空闲页可复用，执行有限增量回收。

## 自动验证

`TelemetryRetentionTest` 使用临时真实 SQLite，覆盖 UTC 90 天边界、三表与多节点合计、连续多个整天删除、恰好达到上限、当天超限、先过期再按量、单表覆盖、空表和超过 5000 条的分批删除。所有数据均为临时测试数据。

```bash
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.m2/repository" \
  -pl control-manager -am test -DskipTests=false
```

2026-10-31 执行通过：control-manager 223 项，0 失败，0 错误，1 项跳过；公共模块测试通过。管理端打包及 Docker 镜像构建通过。

## 192.168.10.85 部署记录

- Compose：`/root/docker-nas/home-proxy-sqlite/docker-compose.yml`，镜像改为 `home-proxy-control-manager:latest`，未创建额外版本标签。
- 容器名沿用 `home-proxy-controller`，本次容器 ID 为 `b97cc7af2358`，端口沿用 `192.168.10.85:18081 -> 8443`。
- 数据挂载、内存限制、JVM 参数及重启策略沿用原配置。前端资源保持 `main.c4478482.js`。
- 替换前已将两份 SQLite 数据库在线备份到 `/root/docker-nas/home-proxy-sqlite/before-log-retention/`，备份通过 `PRAGMA quick_check`；同目录保存原 Compose、容器 inspect 和原镜像 ID。
- 新容器无重启；登录页和三类日志分页 API 返回 200，主节点 WSS 重连、心跳和遥测上报正常。
- 首次清理于 2026-10-31 09:11:07 UTC 完成：`removed=0, remaining=151739, retentionDays=90, maxRecords=100000000 (UTC days)`，现有数据全部保留。
- 重启后已确认新落库 187 条会话、151 条 DNS，遥测库在线 `PRAGMA quick_check` 返回 `ok`；本次镜像传输临时文件已清理，回滚备份保留。

回滚程序时，可将备份的 Compose 复制回原路径，再运行 `docker compose up -d --force-recreate --no-build --pull never control-manager`；原镜像 `home-proxy-control-manager:backup-async-auth-20261031` 仍保留。回滚程序不会恢复以后被保留策略删除的数据；如需恢复数据库，应先停容器并单独处理备份，避免覆盖新日志和配置。
