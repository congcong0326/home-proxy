#!/bin/bash
set -e

############################
# 可配置参数
############################

LAN_CIDR="192.168.10.0/24"   # 局域网网段（内网设备）
LAN_IF="enp2s0"              # 内网网卡（接路由器 / 交换机）

WAN_IF="ppp0"                # 外网网卡（PPPoE 拨号出来的接口）

MARK=1                       # fwmark
TABLE_ID=100                 # 策略路由表 ID
TPORT=10808                  # netty/tpproxy 监听端口 (127.0.0.1:TPORT)

ENABLE_TPROXY=1              # 1 = TCP 走 TPROXY -> 本机 ${TPORT}
                             # 0 = 不做 TPROXY，TCP 直接按路由/NAT 转发

ENABLE_PROXY_443=1           # 0 = 443 不代理（直连），1 = 443 走代理

ENABLE_DNS_UDP53=0           # 0 = 不劫持 UDP/53，1 = 劫持到本机 DNS
DNS_LOCAL_PORT=53            # 本机上运行的 DNS 端口，一般就是 53

CHAIN_LAN="nas_proxy"        # 代理 LAN 设备的链名

# 不走透明代理的 TCP 端口白名单（可选）
# 用空格分隔端口号，例如： "22 80 18081"
# 例如你现在想放行 SOCKS 端口 18081：
BYPASS_TCP_PORTS="18081"



echo "[*] 等待 $WAN_IF 出现并获取 IP..."

for i in {1..20}; do   # 最多等 20 秒
  if ip addr show "$WAN_IF" 2>/dev/null | grep -q 'inet '; then
    echo "[*] $WAN_IF 已经 up 且有 IP"
    break
  fi
  echo "[*] 第 $i 秒：$WAN_IF 还没就绪，继续等..."
  sleep 1
done

if ! ip addr show "$WAN_IF" 2>/dev/null | grep -q 'inet '; then
  echo "[!] 等待 $WAN_IF 获取 IP 超时，退出"
  exit 1
fi

############################
# 0. 基本检查 & 开启转发
############################

if [ "$EUID" -ne 0 ]; then
  echo "请用 root 运行这个脚本（sudo $0）"
  exit 1
fi

echo "[*] 启动 tproxy_netty 配置脚本..."
echo "[*] ENABLE_TPROXY=${ENABLE_TPROXY} (1=TCP 走 TPROXY, 0=TCP 直接转发)"

if ! ip link show "${LAN_IF}" >/dev/null 2>&1 ; then
  echo "[!] LAN_IF=${LAN_IF} 不存在"
  exit 1
fi

if ! ip link show "${WAN_IF}" >/dev/null 2>&1 ; then
  echo "[!] WAN_IF=${WAN_IF} 不存在，请先确保 PPPoE 拨号成功（ppp0 已 UP）"
  exit 1
fi

echo "[*] 开启 net.ipv4.ip_forward=1"
sysctl -w net.ipv4.ip_forward=1 >/dev/null

############################
# 1. 清理旧策略路由（只动自己这张表）
############################

echo "[*] 清理旧策略路由 (table ${TABLE_ID})..."
ip rule del fwmark ${MARK} table ${TABLE_ID} 2>/dev/null || true
ip route flush table ${TABLE_ID} 2>/dev/null || true

############################
# 2. 清理旧 mangle 链
############################

echo "[*] 清理旧 mangle 规则与链 ${CHAIN_LAN}..."
iptables -t mangle -D PREROUTING -i ${LAN_IF} -s ${LAN_CIDR} -p tcp -j ${CHAIN_LAN} 2>/dev/null || true
iptables -t mangle -F ${CHAIN_LAN} 2>/dev/null || true
iptables -t mangle -X ${CHAIN_LAN} 2>/dev/null || true

############################
# 3. 清理旧 NAT & FORWARD 规则
############################

echo "[*] 清理旧 NAT(FORWARD/DNS) 规则..."
iptables -t nat -D PREROUTING -i ${LAN_IF} -s ${LAN_CIDR} -p udp --dport 53 \
  -j REDIRECT --to-ports ${DNS_LOCAL_PORT} 2>/dev/null || true

iptables -t nat -D POSTROUTING -s ${LAN_CIDR} -o ${WAN_IF} -j MASQUERADE 2>/dev/null || true

iptables -D FORWARD -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i ${LAN_IF} -s ${LAN_CIDR} -j ACCEPT 2>/dev/null || true

############################
# 4. 策略路由表
############################

echo "[*] 配置策略路由表 ${TABLE_ID}..."
if [ "${ENABLE_TPROXY}" -eq 1 ]; then
  ip rule add fwmark ${MARK} table ${TABLE_ID} 2>/dev/null || true
  ip route add local 0.0.0.0/0 dev lo table ${TABLE_ID} 2>/dev/null || true
else
  echo "[*] ENABLE_TPROXY=0，不启用 fwmark/table ${TABLE_ID}"
fi

############################
# 5. mangle + TPROXY
############################

echo "[*] 创建 mangle 链 ${CHAIN_LAN} 并配置 TPROXY..."
if [ "${ENABLE_TPROXY}" -eq 1 ]; then
  iptables -t mangle -N ${CHAIN_LAN}

  iptables -t mangle -A PREROUTING -i ${LAN_IF} -s ${LAN_CIDR} -p tcp -j ${CHAIN_LAN}

  # 不处理发往本机回环
  iptables -t mangle -A ${CHAIN_LAN} -d 127.0.0.0/8 -j RETURN
  # 不处理内网互访
  iptables -t mangle -A ${CHAIN_LAN} -d ${LAN_CIDR} -j RETURN

  # 不走透明代理的 TCP 端口白名单
  if [ -n "${BYPASS_TCP_PORTS}" ]; then
    echo "[*] 不走透明代理的 TCP 端口: ${BYPASS_TCP_PORTS}"
    for PORT in ${BYPASS_TCP_PORTS}; do
      iptables -t mangle -A ${CHAIN_LAN} -p tcp --dport ${PORT} -j RETURN
    done
  fi

  if [ "${ENABLE_PROXY_443}" -eq 0 ]; then
    echo "[*] 443 端口不走代理（直连）"
    iptables -t mangle -A ${CHAIN_LAN} -p tcp --dport 443 -j RETURN
  else
    echo "[*] 443 端口将走代理"
  fi

  # 其余 TCP 走 TPROXY
  iptables -t mangle -A ${CHAIN_LAN} -p tcp \
    -j TPROXY --on-port ${TPORT} --tproxy-mark ${MARK}/${MARK}
else
  echo "[*] ENABLE_TPROXY=0，不创建 TPROXY 规则"
fi

############################
# 6. NAT & FORWARD
############################

echo "[*] 配置 NAT (MASQUERADE) 和 FORWARD 规则..."
iptables -t nat -A POSTROUTING -s ${LAN_CIDR} -o ${WAN_IF} -j MASQUERADE

if [ "${ENABLE_DNS_UDP53}" -eq 1 ]; then
  echo "[*] 启用 DNS 劫持到本机 ${DNS_LOCAL_PORT}"
  iptables -t nat -A PREROUTING -i ${LAN_IF} -s ${LAN_CIDR} -p udp --dport 53 \
    -j REDIRECT --to-ports ${DNS_LOCAL_PORT}
else
  echo "[*] 不劫持 UDP/53，按客户端自己配置的 DNS 走"
fi

iptables -A FORWARD -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A FORWARD -i ${LAN_IF} -s ${LAN_CIDR} -j ACCEPT

############################
# 7. 调试信息
############################

echo
echo "==== ip rule ===="
ip rule show

echo
echo "==== ip route show table ${TABLE_ID} ===="
ip route show table ${TABLE_ID}

echo
echo "==== mangle 表 ===="
iptables -t mangle -L -n -v

echo
echo "==== nat 表 ===="
iptables -t nat -L -n -v

echo
echo "==== FORWARD 规则 ===="
iptables -L FORWARD -n -v

echo
echo "==== 配置完成 ===="
echo " LAN_CIDR         = ${LAN_CIDR}"
echo " LAN_IF           = ${LAN_IF}"
echo " WAN_IF           = ${WAN_IF}"
echo " TABLE_ID         = ${TABLE_ID}"
echo " TPROXY_PORT      = ${TPORT}"
echo " ENABLE_TPROXY    = ${ENABLE_TPROXY}"
echo " ENABLE_PROXY_443 = ${ENABLE_PROXY_443}"
echo " ENABLE_DNS_UDP53 = ${ENABLE_DNS_UDP53}"
echo " BYPASS_TCP_PORTS = ${BYPASS_TCP_PORTS}"
echo " CHAIN_LAN        = ${CHAIN_LAN}"

