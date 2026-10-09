# Fake IP 部署与验证

Fake IP 首期只支持同一 worker 上的 UDP DNS A 查询和 TCP 透明代理。先在同一 worker 创建并启用一个 `TP_PROXY` 入站和一个 `DNS_SERVER` 入站，再打开 DNS 入站的 `fakeIpEnabled`。DNS 入站的兜底路由必须指向真实 UDP DNS 或 DoT 上游；所有 DNS 上游地址必须是 IP 字面量。启用后，普通域名的 A 查询返回 `198.18.0.0/15` 地址，TTL 为 1 秒；适用域名的 AAAA 查询返回空答案。因此 IPv6 专用站点可能无法访问。

将客户端的 UDP/53 请求送到该 worker 的 DNS 入站，并将整个 `198.18.0.0/15` 地址池的 TCP 流量送到该 worker 的透明代理。`backend/tproxy_netty.sh` 中 `ENABLE_DNS_UDP53=0` 是默认值；可根据部署将其设为 `1`，或通过 DHCP 指定 worker DNS。确认透明代理监听已启动、策略路由能接收整个地址池，再允许客户端使用 Fake IP DNS。DNS over TCP、DoH、DoT 和 QUIC 不在首期范围。

主路由本机的 DNS 如果也指向 Fake IP DNS，本机 curl、host 网络的 DDNS Go 等程序同样需要引流。`backend/tproxy_netty.sh` 默认启用 `ENABLE_LOCAL_FAKE_IP=1`：只对本机目标为 `198.18.0.0/15` 的 TCP 在 mangle OUTPUT 打标，经现有策略路由回送 `lo`，再在 PREROUTING 交给透明代理。源地址为 Fake IP 的回包不会再次打标，worker 连接真实上游的流量不受影响。该路径覆盖所有 TCP 端口，不使用 LAN 的端口白名单或 `ENABLE_PROXY_443` 开关。需要关闭时设为 `0` 并重新执行脚本；重复执行会清理旧规则。Docker bridge 网络的流量不会经过宿主机 OUTPUT，需要另行配置容器入口引流。

DNS 入站根据所见源 IP 匹配设备规则。若路由器对 DNS 查询做源地址转换，而透明 TCP 保留客户端地址，同一个客户端可能得到不同的 DNS 选路。此时需保持两侧所见源 IP 一致，或只使用不依赖设备身份的 DNS 规则。启用 Fake IP 的透明代理路由不能使用 GEO 条件。地址池不能与局域网、VPN、容器网段或 DNS 上游地址冲突。

Fake IP 映射只保存在 worker 内存，配置刷新时保留，worker 重启或 LRU 淘汰后可能丢失。旧地址连接会尝试从 HTTP Host 或 TLS SNI 恢复域名；无法恢复时关闭。1 秒 TTL 不能保证客户端按时停止使用旧地址，映射重用仍存在误归属风险。关闭开关仅停止发放新地址；透明代理仍会按地址池规则处理旧连接。

DNS 查询名与 Fake IP 主机名分别校验。A/AAAA 查询允许下划线、根域名 `.`、标签首尾连字符等不符合普通主机名规则的名称；此类名称不分配 Fake IP，也不抑制 AAAA，而是按设备及域名路由转发到真实 DNS，并使用共享缓存。BLOCK 和 DNS_REWRITING 策略仍优先生效。普通主机名继续使用严格的 IDN/STD3 规则分配 Fake IP。DNS 名称统一大小写与 IDN，去掉一个末尾点（根域名保留 `.`）；标签需为 1–63 字节，DNS 报文中名称的编码长度不超过 255 字节，不接受空标签、空名称、空白或控制字符。上游响应匹配和 CNAME 解析使用同样的 DNS 名称规则。

DNS 单次请求的名称校验失败会返回 `FORMERR`，其他同步处理错误返回 `SERVFAIL`；无法解码的报文会被丢弃，不关闭共享 UDP 监听。这些错误会记录日志，并非静默处理。监听运行状态同时检查 socket 是否存活。真实 DNS 上游复用前会检查绑定的本地地址及网卡；出站地址失效、写入失败或连续两次查询超时后会淘汰旧连接、结束待处理查询，并由后续查询重新连接。单次查询超时为 5 秒；检测到 WAN 地址已移除时，会在发送新查询前重建连接。可在日志中查找 `DNS 请求处理失败`、`丢弃处理失败的 DNS 请求` 和 `重建失效 DNS 上游连接` 来区分请求错误和上游连接故障。

本地联动验证可运行 `make e2e-fake-ip-transparent`（也可使用 `make e2e-transparent`）。脚本启动临时管理端、worker、DNS 上游和透明代理网络命名空间，覆盖 HTTP/TLS、域名路由、DNS 缓存与故障、设备身份、日志指标、配置刷新和重启恢复；完整场景与报告见 [E2E 测试说明](../testing/fake-ip-transparent-e2e.md)。另运行 `make e2e-worker-proxy` 和 `make e2e-direct-outbound-safety` 检查现有代理和目的地址防护场景。
