# 固定 Fake IP 与透明代理联动测试

此测试使用正在运行的本地 controller 和 `home-01` worker，配置和测试服务保留，方便在管理台查看日志。不同于 `make e2e-transparent`，结束时不删除临时数据库，而是把配置写入现有 controller。

## 运行

Linux 宿主机需要 root、systemd、iproute2、iptables、Python 3，以及已启动的本地 controller/worker。默认 controller 地址为 `https://127.0.0.1:18443`；登录使用现有 E2E 开发账号，可通过 `E2E_ADMIN_USER`、`E2E_ADMIN_PASSWORD` 覆盖。

```bash
make e2e-fake-ip-persistent
```

重复运行复用同名路由、入站、网络命名空间和服务。只重新发起访问并检查本轮日志时运行：

```bash
python3 scripts/e2e/fake-ip-persistent-e2e.py test
```

配置保存在数据库，容器重建后保留。宿主机重启后，运行 `make e2e-fake-ip-persistent` 恢复网络和测试服务。

## 固定资源

| 资源 | 配置 |
| --- | --- |
| 客户端 | 网络命名空间 `hp-fake-ip-test`，源 IP `192.0.2.194` |
| DNS 入站 | `home-01-fake-ip-dns`，`192.0.2.193:15353`，Fake IP 开启 |
| 真实 DNS 上游 | `home-01-fake-ip-real-dns`，`127.0.0.1:15354` |
| 透明代理 | 复用 `home-01` 唯一启用的透明入站，端口 `29180` |
| 测试域名直连路由 | `home-01-fake-ip-test-direct` |
| 测试域名 | `fake-direct.persistent.home-proxy.test` |
| HTTP 源站 | `192.0.2.202:29290`，返回 `PERSISTENT_FAKE_IP_E2E_OK` |
| 服务端先发送源站 | `192.0.2.202:29291`，连接后主动返回相同内容 |
| 服务 | `home-proxy-fake-ip-e2e-fixture.service` |
| 引流 | 仅测试 veth 接口上的 `198.18.0.0/15` TCP；mark `0x94`，路由表 `294`，规则优先级 `1294` |

真实 DNS 测试上游只解析该测试域名，其他域名返回 NXDOMAIN。启用此 DNS 入站也会使同 worker 的出站域名解析使用它，因此该配置用于本地测试节点。原透明代理的既有路由保留，新增该测试域名的直连绑定。

## 验证与日志

测试断言重复 A 查询返回相同 Fake IP、TTL=1、AAAA 空答案，然后连接查询得到的 Fake IP：

1. 发送不带 HTTP Host 的请求，确认通过透明代理反查域名、真实 DNS 解析和直连，得到源站内容。
2. 不发送任何客户端负载，确认服务器主动返回内容，验证无需等待首包嗅探。
3. 通过 controller API 查询本轮两个 TCP 请求，检查状态 200、原始 Fake IP、恢复域名、`FAKE_IP` 来源、真实连接 IP、DNS 路由与配置版本。小批量日志默认最多 60 秒后落库，脚本等待最多 90 秒。

管理台“日志审计”按节点 `home-01`、客户端 `192.0.2.194` 或域名 `fake-direct.persistent.home-proxy.test` 筛选；DNS 日志和透明 TCP 日志都会保留。脚本打印 TCP 请求 ID，便于定位详情。

```bash
journalctl -u home-proxy-fake-ip-e2e-fixture -f
docker logs -f home-proxy-worker-home-01
```

停用测试时先在管理台停用 `home-01-fake-ip-dns`，再停止 fixture 服务；避免留下启用的 Fake DNS 而真实上游已关闭。网络资源仅用于上述测试客户端，不接管局域网流量。
