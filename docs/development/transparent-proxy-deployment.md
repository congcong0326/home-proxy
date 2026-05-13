# 透明代理部署说明

这篇文档只讲 NasProxy 的透明代理落地方式，不试图写成完整的软路由教程。它适合这样的场景：你已经有一台 Linux 主机在网络里承担主路由、旁路由或网关职责，能确认哪块网卡接 LAN、哪块网卡出 WAN，并且可以用 root 权限配置 `iptables` 和策略路由。

如果你的目标是把小主机改成完整主路由，还需要自己处理 PPPoE、DHCP、DNS、防火墙和开机自启。这里会提到这些点，但重点仍然是 NasProxy 的 `TP_PROXY` 入站和 `backend/tproxy_netty.sh`。

## 透明代理怎么工作

普通 SOCKS/HTTP 代理需要在客户端里手动填代理地址。透明代理的想法是：客户端仍然正常访问网站，网关在转发流量时把 TCP 连接交给 NasProxy worker。

一条典型链路是：

```text
LAN 设备发起 TCP 连接
  -> 网关上的 iptables mangle PREROUTING
  -> TPROXY 给连接打 fwmark，并交给本机指定端口
  -> ip rule / table 100 把被标记的连接路由到本机
  -> NasProxy worker 的 TP_PROXY 入站接住连接
  -> worker 从 HTTP Host 或 TLS SNI 里识别域名
  -> 按控制面配置的用户、入站、域名、GeoIP、规则集选择出站
```

这里的关键点是 `TPROXY`，它不是普通的端口重定向。它会尽量保留原始目标 IP 和端口，worker 才能知道客户端原本要访问哪里。NasProxy 在 Linux epoll 可用时会打开 Netty 的 `IP_TRANSPARENT`，透明代理入站依赖这个能力。

对于 HTTP 流量，worker 会读取 `Host`。对于 HTTPS 流量，worker 会读取 TLS ClientHello 里的 SNI。不是 HTTP/TLS 的流量无法拿到域名，会回退到原始目标 IP，再继续走后续路由判断。

## 部署前检查

先把这些事情确认掉，后面排查会省很多时间：

- worker 要跑在 Linux 主机上，Docker Compose 里需要使用 `network_mode: host`。Docker Desktop 的 host network 行为和 Linux 原生 Docker 不完全一样，不建议拿它验证透明代理。
- 控制面里创建一个 `TP_PROXY` 入站，监听端口要和脚本里的 `TPORT` 一致，默认是 `10808`。
- `TP_PROXY` 入站的监听 IP 建议填 `0.0.0.0`。如果只监听 `127.0.0.1` 后没有流量命中，先改成 `0.0.0.0` 再测。
- `tproxy_netty.sh` 必须在宿主机上以 root 运行。它改的是宿主机的 `ip rule`、路由表和 `iptables`，不是容器内部网络。
- 先确认 LAN 网段、LAN 网卡、WAN 网卡。脚本默认 `LAN_CIDR=192.168.10.0/24`、`LAN_IF=enp2s0`、`WAN_IF=ppp0`，这只是示例。
- 如果这台机器直接拿公网 IP，它就是一台公开服务器。SSH 建议使用普通管理用户加密钥登录，禁用 root 和密码登录，并开启 fail2ban。公网 SOCKS 入站必须有认证，最好再用防火墙限制来源 IP。

## worker 启动方式

Docker Hub 镜像可以直接启动 worker，例如：

```yaml
name: home-proxy-worker

services:
  proxy-worker:
    image: congcong0326/home-proxy-worker:latest
    container_name: home-proxy-worker
    restart: unless-stopped
    network_mode: host
    environment:
      CONTROL_MANAGER_URL: http://127.0.0.1:18081
```

如果控制面在另一台机器，把 `CONTROL_MANAGER_URL` 换成控制面的实际地址。

启动后先确认 worker 已经从控制面拉到配置，并且 `TP_PROXY` 端口已经监听：

```bash
docker logs -f home-proxy-worker
ss -lntp | grep 10808
```

如果你在控制面里配置的透明代理端口不是 `10808`，后面的脚本参数也要一起改。

## tproxy_netty.sh 参数

脚本在仓库的 `backend/tproxy_netty.sh`。它不会被 Docker 镜像自动执行，需要在宿主机上单独运行。

常改的参数在脚本最上面：

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `LAN_CIDR` | `192.168.10.0/24` | 需要接入透明代理的内网网段 |
| `LAN_IF` | `enp2s0` | 接内网交换机、AP 或客户端的网卡 |
| `WAN_IF` | `ppp0` | 出公网的网卡，PPPoE 场景通常是 `ppp0` |
| `TPORT` | `10808` | NasProxy `TP_PROXY` 入站端口 |
| `MARK` | `1` | TPROXY 使用的 fwmark |
| `TABLE_ID` | `100` | 被标记流量使用的策略路由表 |
| `ENABLE_TPROXY` | `1` | `1` 表示 TCP 走透明代理，`0` 表示只做普通 NAT 转发 |
| `ENABLE_PROXY_443` | `1` | `1` 表示 HTTPS 也走透明代理，`0` 表示 443 直连 |
| `ENABLE_DNS_UDP53` | `0` | 是否把 LAN 的 UDP/53 劫持到本机 DNS |
| `DNS_LOCAL_PORT` | `53` | DNS 劫持开启时转发到的本机端口 |
| `BYPASS_TCP_PORTS` | `18081` | 不走透明代理的 TCP 端口，用空格分隔 |

`BYPASS_TCP_PORTS` 很有用。例如控制面端口、SSH 端口、你明确希望直连的服务端口，都可以放进去，避免管理流量被透明代理接走。

## 运行步骤

建议先手动跑通，不要一上来就写开机自启。

1. 在控制面创建并发布 `TP_PROXY` 入站，端口先用 `10808`。
2. 启动 worker，并确认宿主机能看到监听：

   ```bash
   ss -lntp | grep 10808
   ```

3. 把脚本复制到宿主机上，或者直接在源码目录执行。先按你的机器修改顶部参数：

   ```bash
   sudo cp backend/tproxy_netty.sh /root/tproxy_netty.sh
   sudo vi /root/tproxy_netty.sh
   sudo chmod +x /root/tproxy_netty.sh
   ```

4. 确认 WAN 已经就绪，再运行脚本：

   ```bash
   sudo /root/tproxy_netty.sh
   ```

   脚本会等待 `WAN_IF` 最多 20 秒。如果你的 PPPoE 或上游网络更慢，要么先手动确认 `WAN_IF` 已经拿到 IP，要么把脚本里的等待时间调大。

5. 看脚本打印的规则摘要。至少要看到：

   ```bash
   ip rule show
   ip route show table 100
   iptables -t mangle -L -n -v
   iptables -t nat -L -n -v
   iptables -L FORWARD -n -v
   ```

6. 从 LAN 设备测试访问：

   ```bash
   curl http://ipinfo.io/ip
   ```

   再访问几个会命中你路由规则的域名，回到控制面看访问日志和流量统计。

## 脚本实际做了什么

`tproxy_netty.sh` 会做这几类事：

- 检查 root 权限、LAN/WAN 网卡是否存在，并打开 `net.ipv4.ip_forward=1`。
- 清理上一次由脚本创建的策略路由、mangle 链、NAT 和 FORWARD 规则。
- 在 `ENABLE_TPROXY=1` 时增加：

  ```bash
  ip rule add fwmark 1 table 100
  ip route add local 0.0.0.0/0 dev lo table 100
  ```

- 在 `mangle PREROUTING` 里只处理来自 `LAN_IF` 和 `LAN_CIDR` 的 TCP 流量。
- 跳过回环地址、LAN 内互访、`BYPASS_TCP_PORTS` 里的端口，以及可选跳过 443。
- 其余 TCP 用 `TPROXY --on-port ${TPORT}` 交给 NasProxy worker。
- 为 LAN 到 WAN 增加 `MASQUERADE`，并放行基础 FORWARD。
- 可选把 LAN 的 UDP/53 重定向到本机 DNS。

脚本只清理自己使用的路由表和链，不要为了排查随手执行 `ip rule flush`，那会把系统默认策略路由也删掉。

## 排查思路

没有外网：

- 看 `WAN_IF` 是否存在并有 IP：`ip addr show ppp0`。
- 看默认路由是否正确：`ip route`。
- 看转发是否开启：`sysctl net.ipv4.ip_forward`。
- 看 NAT 和 FORWARD 规则计数是否增长：`iptables -t nat -L -n -v`、`iptables -L FORWARD -n -v`。

透明代理没有命中：

- 看 worker 是否监听了 `TPORT`：`ss -lntp | grep 10808`。
- 看控制面是否已经发布 `TP_PROXY` 入站，worker 日志里是否加载到这个入站。
- 看 `mangle` 计数是否增长：`iptables -t mangle -L -n -v`。
- 核对 `LAN_IF`、`LAN_CIDR`。网卡写错时，规则存在但计数不会动。
- 如果入站监听 `127.0.0.1` 没有流量，改成 `0.0.0.0` 后重新发布配置并重启 worker。

HTTPS 域名规则不生效：

- 透明代理只能从 TLS ClientHello 里拿 SNI。没有 SNI、被特殊客户端隐藏、或不是 HTTP/TLS 的连接，只能按目标 IP 判断。
- 如果你依赖 GeoIP，确认 mmdb 已经挂载或打进镜像，并且 `GEOIP_MMDB_PATH` / `GEOIP_DATA_DIR` 指向正确。

内网服务访问异常：

- 脚本默认跳过目标在 `LAN_CIDR` 内的流量，内网互访通常不该进代理。
- 如果某些管理端口仍然被接走，把端口加入 `BYPASS_TCP_PORTS`，例如：

  ```bash
  BYPASS_TCP_PORTS="22 18081"
  ```

DNS 行为不符合预期：

- 默认 `ENABLE_DNS_UDP53=0`，脚本不会劫持 DNS。
- 如果打开 DNS 劫持，必须确认本机 `DNS_LOCAL_PORT` 上真的有 DNS 服务，比如 dnsmasq 或 NasProxy 的 DNS 入站。

## 上线前安全提醒

主路由一旦拿到公网 IP，就会持续暴露在自动扫描下。至少建议做到：

- SSH 使用普通用户加密钥登录，关闭 root 登录和密码登录。
- 开启 fail2ban，降低暴力破解风险。
- 控制面端口不要直接暴露公网，确实要远程访问时优先走 VPN、内网穿透白名单或反向代理认证。
- 公网 SOCKS/HTTP 入站必须配置认证，不要做开放代理。
- 修改防火墙前留一个已经登录的 SSH 会话，确认新登录方式可用后再断开旧会话。
