#!/usr/bin/env bash
# Real Netty recvmsg + transparent reply experiment. No changes outside private namespaces.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROUTER="hp-udp-router-$$"
CLIENT="hp-udp-client-$$"
TEMP_DIR="$(mktemp -d)"
PROBE_PID=""
cleanup() {
  local result=$?
  if (( result != 0 )) && [[ -f "$TEMP_DIR/probe.log" ]]; then cat "$TEMP_DIR/probe.log" >&2; fi
  if [[ -n "$PROBE_PID" ]]; then kill "$PROBE_PID" 2>/dev/null || true; fi
  ip netns del "$CLIENT" 2>/dev/null || true
  ip netns del "$ROUTER" 2>/dev/null || true
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT
if [[ "${UDP_PROBE_BUILD:-1}" == 1 ]]; then
  mvn -f "$ROOT_DIR/backend/pom.xml" -B -ntp -Dmaven.repo.local="$ROOT_DIR/.m2/repository" \
    -pl proxy-worker -am package -DskipTests
fi
ip netns add "$ROUTER"
ip netns add "$CLIENT"
ip -n "$ROUTER" link add udpr type veth peer name udpc
ip -n "$ROUTER" link set udpc netns "$CLIENT"
ip -n "$ROUTER" addr add 192.0.2.1/30 dev udpr
ip -n "$CLIENT" addr add 192.0.2.2/30 dev udpc
for spec in "$ROUTER udpr" "$CLIENT udpc"; do
  read -r ns iface <<< "$spec"
  ip -n "$ns" link set lo up
  ip -n "$ns" link set "$iface" up
done
ip -n "$CLIENT" route add default via 192.0.2.1
ip -n "$ROUTER" rule add fwmark 1 table 100
ip -n "$ROUTER" route add local 0.0.0.0/0 dev lo table 100
ip netns exec "$ROUTER" iptables -t mangle -A PREROUTING -i udpr -p udp -d 198.18.0.0/15 \
  -j TPROXY --on-port 39992 --tproxy-mark 1/1
for rp in 0 1; do
  ip netns exec "$ROUTER" sysctl -qw "net.ipv4.conf.all.rp_filter=$rp" "net.ipv4.conf.udpr.rp_filter=$rp"
  for target_port in 39992 39993; do
    ip netns exec "$ROUTER" java -cp "$ROOT_DIR/backend/proxy-worker/target/test-classes:$ROOT_DIR/backend/proxy-worker/target/proxy-worker-2.0-SNAPSHOT.jar" \
      org.congcong.proxyworker.udp.UdpOriginalDestinationProbe "$target_port" > "$TEMP_DIR/probe.log" 2>&1 &
    PROBE_PID=$!
    for attempt in {1..100}; do
      if grep -q '^READY$' "$TEMP_DIR/probe.log"; then break; fi
      kill -0 "$PROBE_PID" 2>/dev/null || { cat "$TEMP_DIR/probe.log"; exit 1; }
      sleep .1
    done
    grep -q '^READY$' "$TEMP_DIR/probe.log"
    ip netns exec "$CLIENT" python3 - "$target_port" <<'PY'
import socket, sys
peer = ('198.18.2.3', int(sys.argv[1]))
clients = [socket.socket(socket.AF_INET, socket.SOCK_DGRAM) for _ in range(2)]
for i, client in enumerate(clients):
    client.settimeout(5)
    payload = bytes([i + 1]) * 3000
    client.sendto(payload, peer)
    data, source = client.recvfrom(65535)
    assert data == payload and source == peer, (len(data), source)
    client.close()
PY
    wait "$PROBE_PID" || { cat "$TEMP_DIR/probe.log"; exit 1; }
    PROBE_PID=""
    echo "PASS original recipient, shared transparent reply, 3000 bytes: target_port=$target_port rp_filter=$rp"
  done
done
