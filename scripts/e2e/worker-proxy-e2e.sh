#!/usr/bin/env bash
#
# Persistent-environment worker proxy E2E.
#
# The control-manager and worker are deliberately not recreated here. The default
# run checks the preconfigured home-01 fixtures and exercises them with temporary
# local targets. Use --provision once when preparing a development/test database.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_PY="$ROOT_DIR/scripts/e2e/worker-proxy-fixture.py"

CONTROL_BASE="${E2E_CONTROL_BASE:-https://127.0.0.1:18443}"
WORKER_ID="${E2E_WORKER_ID:-home-01}"
WORKER_CONTAINER="${E2E_WORKER_CONTAINER:-home-proxy-worker-${WORKER_ID}}"
ADMIN_USER="${E2E_ADMIN_USER:-congcong}"
ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-congcong}"
TIMEOUT_SECONDS="${E2E_TIMEOUT_SECONDS:-60}"

TPROXY_PORT="${E2E_WORKER_TPROXY_PORT:-29180}"
SOCKS_PORT="${E2E_WORKER_SOCKS_PORT:-29181}"
TLS_SNI_PORT="${E2E_WORKER_TLS_SNI_PORT:-29182}"
ORIGIN_PORT="${E2E_WORKER_ORIGIN_PORT:-29190}"
TLS_ORIGIN_PORT="${E2E_WORKER_TLS_ORIGIN_PORT:-29193}"
OVERRIDE_PORT="${E2E_WORKER_OVERRIDE_PORT:-29191}"
UPSTREAM_PORT="${E2E_WORKER_UPSTREAM_PORT:-29192}"
ORIGIN_IP="${E2E_WORKER_ORIGIN_IP:-198.18.1.2}"
CLIENT_GATEWAY_IP="${E2E_WORKER_CLIENT_GATEWAY_IP:-198.18.0.1}"
CLIENT_IP="${E2E_WORKER_CLIENT_IP:-198.18.0.2}"
MARK="${E2E_WORKER_TPROXY_MARK:-0x93}"
ROUTE_TABLE="${E2E_WORKER_ROUTE_TABLE:-293}"
RULE_PRIORITY="${E2E_WORKER_RULE_PRIORITY:-1293}"

FIXTURE_USER="${E2E_WORKER_USER:-worker-e2e}"
FIXTURE_PASSWORD="${E2E_WORKER_PASSWORD:-worker-e2e-pass}"
FIXTURE_PREFIX="${E2E_WORKER_FIXTURE_PREFIX:-home-01-e2e}"

RUN_ID="${BASHPID}"
CLIENT_NS="hpwe2e${RUN_ID}"
HOST_IF="hpwh${RUN_ID}"
CLIENT_IF="hpwc${RUN_ID}"
RULE_COMMENT="home-proxy-worker-e2e-${RUN_ID}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/home-proxy-worker-e2e.XXXXXX")"

ORIGIN_PID=0
OVERRIDE_PID=0
TLS_ORIGIN_PID=0
UPSTREAM_PID=0
ORIGIN_IP_ADDED=0
NAMESPACE_CREATED=0
HOST_LINK_CREATED=0
POLICY_RULE_ADDED=0
POLICY_ROUTE_ADDED=0
TPROXY_RULE_ADDED=0

SUDO=()
ADMIN_TOKEN=""
USER_ID=""
FALLBACK_ID=""
DIRECT_ID=""
BLOCK_ID=""
PROXY_ID=""
OVERRIDE_ID=""
TLS_ID=""
EXPECTED_CONFIG_HASH=""

fail() {
    echo "E2E failure: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

run_privileged() {
    "${SUDO[@]}" "$@"
}

wait_until() {
    local description="$1"
    shift
    local deadline=$((SECONDS + TIMEOUT_SECONDS))
    until "$@"; do
        (( SECONDS < deadline )) || fail "timed out waiting for ${description}"
        sleep 1
    done
}

cleanup() {
    local result=$?
    trap - EXIT
    set +e

    if (( result != 0 )); then
        echo "--- worker E2E diagnostics ---" >&2
        docker logs --tail 120 "$WORKER_CONTAINER" >&2 2>/dev/null || true
        echo "effective config:" >&2
        api_get "/api/workers/${WORKER_ID}/effective-config" >&2 || true
    fi

    [[ "$ORIGIN_PID" == 0 ]] || kill "$ORIGIN_PID" >/dev/null 2>&1
    [[ "$OVERRIDE_PID" == 0 ]] || kill "$OVERRIDE_PID" >/dev/null 2>&1
    [[ "$TLS_ORIGIN_PID" == 0 ]] || kill "$TLS_ORIGIN_PID" >/dev/null 2>&1
    [[ "$UPSTREAM_PID" == 0 ]] || kill "$UPSTREAM_PID" >/dev/null 2>&1

    if (( TPROXY_RULE_ADDED )); then
        run_privileged iptables -t mangle -D PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$ORIGIN_PORT" \
            -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPROXY_PORT" \
            --tproxy-mark "$MARK/$MARK" >/dev/null 2>&1
    fi
    if (( POLICY_RULE_ADDED )); then
        run_privileged ip rule del priority "$RULE_PRIORITY" fwmark "$MARK/$MARK" lookup "$ROUTE_TABLE" >/dev/null 2>&1
    fi
    if (( POLICY_ROUTE_ADDED )); then
        run_privileged ip route del local 0.0.0.0/0 dev lo table "$ROUTE_TABLE" >/dev/null 2>&1
    fi
    if (( NAMESPACE_CREATED )); then run_privileged ip netns del "$CLIENT_NS" >/dev/null 2>&1; fi
    if (( HOST_LINK_CREATED )); then run_privileged ip link del "$HOST_IF" >/dev/null 2>&1; fi
    if (( ORIGIN_IP_ADDED )); then run_privileged ip addr del "$ORIGIN_IP/32" dev lo >/dev/null 2>&1; fi

    rm -rf "$TEMP_DIR"
    exit "$result"
}
trap cleanup EXIT

api_get() {
    curl -ksS --fail-with-body -H "Authorization: Bearer ${ADMIN_TOKEN}" "$CONTROL_BASE$1"
}

api_mutate() {
    local method="$1"
    local path="$2"
    local body="$3"
    curl -ksS --fail-with-body -X "$method" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H 'Content-Type: application/json' --data "$body" "$CONTROL_BASE$path"
}

login() {
    local response
    response="$(curl -ksS --fail-with-body -H 'Content-Type: application/json' \
        --data "$(jq -n --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}')" \
        "$CONTROL_BASE/admin/login")" || fail "control-manager login failed"
    ADMIN_TOKEN="$(jq -er '.token' <<<"$response")" || fail "login response did not contain a token"
}

route_body() {
    local name="$1"
    local domain="$2"
    local policy="$3"
    local proxy_type="${4:-}"
    local proxy_host="${5:-}"
    local proxy_port="${6:-}"
    jq -n --arg name "$name" --arg domain "$domain" --arg policy "$policy" \
        --arg proxyType "$proxy_type" --arg proxyHost "$proxy_host" --argjson proxyPort "${proxy_port:-null}" \
        '{name:$name,rules:[{conditionType:"DOMAIN",op:"IN",value:$domain}],policy:$policy,
          outboundProxyType:(if $proxyType=="" then null else $proxyType end),
          outboundProxyHost:(if $proxyHost=="" then null else $proxyHost end),
          outboundProxyPort:$proxyPort,status:1,notes:"persistent worker proxy E2E fixture"}'
}

lookup_route_id() {
    local name="$1"
    api_get '/api/routes?pageSize=200&sortBy=id&sortDir=asc' | jq -er --arg name "$name" \
        '.items[] | select(.name == $name) | .id' 2>/dev/null || true
}

ensure_route() {
    local name="$1"
    local domain="$2"
    local policy="$3"
    local proxy_type="${4:-}"
    local proxy_host="${5:-}"
    local proxy_port="${6:-}"
    local body route_id
    body="$(route_body "$name" "$domain" "$policy" "$proxy_type" "$proxy_host" "$proxy_port")"
    route_id="$(lookup_route_id "$name")"
    if [[ -n "$route_id" ]]; then
        api_mutate PUT "/api/routes/${route_id}" "$body" >/dev/null
    else
        route_id="$(api_mutate POST /api/routes "$body" | jq -er '.id')"
    fi
    printf '%s\n' "$route_id"
}

lookup_user_id() {
    api_get '/api/users?pageSize=200&sortBy=id&sortDir=asc' | jq -er --arg name "$FIXTURE_USER" \
        '.items[] | select(.username == $name) | .id' 2>/dev/null || true
}

ensure_user() {
    USER_ID="$(lookup_user_id)"
    if [[ -z "$USER_ID" ]]; then
        USER_ID="$(api_mutate POST /api/users "$(jq -n --arg u "$FIXTURE_USER" --arg p "$FIXTURE_PASSWORD" \
            '{username:$u,credential:$p,status:1,remark:"persistent worker proxy E2E fixture"}')" | jq -er '.id')"
    else
        api_mutate PUT "/api/users/${USER_ID}" "$(jq -n --arg u "$FIXTURE_USER" --arg p "$FIXTURE_PASSWORD" \
            '{username:$u,credential:$p,status:1,remark:"persistent worker proxy E2E fixture"}')" >/dev/null
    fi
}

lookup_inbound_id() {
    local name="$1"
    api_get '/api/inbounds?pageSize=200&sortBy=id&sortDir=asc' | jq -er --arg name "$name" \
        '.items[] | select(.name == $name) | .id' 2>/dev/null || true
}

ensure_inbound() {
    local name="$1"
    local protocol="$2"
    local port="$3"
    local inbound_id body
    body="$(jq -n --arg worker "$WORKER_ID" --arg name "$name" --arg protocol "$protocol" --argjson port "$port" \
        --argjson anonymous "$USER_ID" --argjson fallback "$FALLBACK_ID" \
        --argjson direct "$DIRECT_ID" --argjson block "$BLOCK_ID" --argjson proxy "$PROXY_ID" --argjson override "$OVERRIDE_ID" --argjson tls "$TLS_ID" \
        '{workerId:$worker,fallbackRouteId:$fallback,anonymousUserId:$anonymous,name:$name,protocol:$protocol,
          listenIp:"0.0.0.0",port:$port,tlsEnabled:false,sniffEnabled:(if $protocol=="TP_PROXY" then true else false end),
          inboundRouteBindings:[{userIds:[$anonymous],routeIds:[$direct,$block,$proxy,$override,$tls]}],status:1,
          notes:"persistent worker proxy E2E fixture"}')"
    inbound_id="$(lookup_inbound_id "$name")"
    if [[ -n "$inbound_id" ]]; then
        api_mutate PUT "/api/inbounds/${inbound_id}" "$body" >/dev/null
    else
        api_mutate POST /api/inbounds "$body" >/dev/null
    fi
}

provision() {
    ensure_user
    FALLBACK_ID="$(ensure_route "${FIXTURE_PREFIX}-fallback-block" '*' BLOCK)"
    DIRECT_ID="$(ensure_route "${FIXTURE_PREFIX}-direct" 'direct.localhost' DIRECT)"
    BLOCK_ID="$(ensure_route "${FIXTURE_PREFIX}-block" 'blocked.localhost' BLOCK)"
    PROXY_ID="$(ensure_route "${FIXTURE_PREFIX}-outbound-proxy" 'proxy.localhost' OUTBOUND_PROXY SOCKS5 127.0.0.1 "$UPSTREAM_PORT")"
    OVERRIDE_ID="$(ensure_route "${FIXTURE_PREFIX}-destination-override" 'override.localhost' DESTINATION_OVERRIDE '' 127.0.0.1 "$OVERRIDE_PORT")"
    TLS_ID="$(ensure_route "${FIXTURE_PREFIX}-tls-target" 'tls.localhost' DESTINATION_OVERRIDE '' 127.0.0.1 "$TLS_ORIGIN_PORT")"
    ensure_inbound "${FIXTURE_PREFIX}-transparent" TP_PROXY "$TPROXY_PORT"
    ensure_inbound "${FIXTURE_PREFIX}-socks5" SOCKS5 "$SOCKS_PORT"
    ensure_inbound "${FIXTURE_PREFIX}-tls-sni" TLS_SNI_TCP "$TLS_SNI_PORT"
    echo "Provisioned worker proxy E2E fixtures for ${WORKER_ID}"
}

validate_effective_config() {
    local config
    config="$(api_get "/api/workers/${WORKER_ID}/effective-config")"
    jq -e --arg worker "$WORKER_ID" --arg user "$FIXTURE_USER" \
        --arg transparent "${FIXTURE_PREFIX}-transparent" --arg socks "${FIXTURE_PREFIX}-socks5" \
        --arg tlsSni "${FIXTURE_PREFIX}-tls-sni" \
        --arg direct "${FIXTURE_PREFIX}-direct" --arg block "${FIXTURE_PREFIX}-block" \
        --arg proxy "${FIXTURE_PREFIX}-outbound-proxy" --arg override "${FIXTURE_PREFIX}-destination-override" \
        --argjson tport "$TPROXY_PORT" --argjson sport "$SOCKS_PORT" \
        --argjson tlsPort "$TLS_SNI_PORT" \
        --argjson uport "$UPSTREAM_PORT" --argjson oport "$OVERRIDE_PORT" \
        '.workerId == $worker
         and (.users | any(.username == $user))
         and (.inbounds | any(.name == $transparent and .protocol == "TP_PROXY" and .port == $tport))
         and (.inbounds | any(.name == $socks and .protocol == "SOCKS5" and .port == $sport))
         and (.inbounds | any(.name == $tlsSni and .protocol == "TLS_SNI_TCP" and .port == $tlsPort))
         and (.routes | any(.name == $direct and .policy == "DIRECT"))
         and (.routes | any(.name == $block and .policy == "BLOCK"))
         and (.routes | any(.name == $proxy and .policy == "OUTBOUND_PROXY" and .outboundProxyType == "SOCKS5" and .outboundProxyPort == $uport))
         and (.routes | any(.name == $override and .policy == "DESTINATION_OVERRIDE" and .outboundProxyHost == "127.0.0.1" and .outboundProxyPort == $oport))' \
        <<<"$config" >/dev/null || fail "home-01 effective config does not match the worker proxy E2E fixture; run make e2e-worker-proxy-setup"
}

worker_ready() {
    local status
    status="$(api_get "/api/workers/${WORKER_ID}/status" 2>/dev/null || true)"
    [[ -n "$status" ]] && jq -e --arg hash "$EXPECTED_CONFIG_HASH" \
        '.online == true and .applySuccess == true and .appliedConfigHash == $hash' >/dev/null <<<"$status"
}

listener_ready() {
    ss -ltn | awk -v t=":${TPROXY_PORT}" -v s=":${SOCKS_PORT}" \
        '$4 ~ (t "$" ) {a=1} $4 ~ (s "$" ) {b=1} END {exit !(a && b)}'
}

port_free() {
    ss -ltn | awk -v p=":$1" '$4 ~ (p "$" ) {found=1} END {exit found}'
}

preflight() {
    [[ "$(uname -s)" == Linux ]] || fail "worker proxy E2E requires Linux"
    for command in curl docker jq python3 ss ip iptables awk; do require_command "$command"; done
    docker info >/dev/null || fail "Docker daemon is unavailable"
    [[ -f "$FIXTURE_PY" ]] || fail "fixture helper is missing: $FIXTURE_PY"
    [[ -n "$(docker ps --filter "name=^/${WORKER_CONTAINER}$" --filter status=running -q)" ]] \
        || fail "worker container ${WORKER_CONTAINER} is not running"
    if (( EUID != 0 )); then
        require_command sudo
        sudo -n true || fail "passwordless sudo is required for transparent proxy namespace setup"
        SUDO=(sudo)
    fi
    ! run_privileged ip netns list | grep -q "^${CLIENT_NS}" || fail "network namespace already exists: ${CLIENT_NS}"
    ! run_privileged ip rule show | grep -q "^${RULE_PRIORITY}:" || fail "policy rule priority is already in use: ${RULE_PRIORITY}"
    ! run_privileged ip route show table "$ROUTE_TABLE" 2>/dev/null | grep -q . || fail "route table is not empty: ${ROUTE_TABLE}"
    port_free "$ORIGIN_PORT" || fail "origin target port is already in use: ${ORIGIN_PORT}"
    port_free "$TLS_ORIGIN_PORT" || fail "TLS origin target port is already in use: ${TLS_ORIGIN_PORT}"
    port_free "$TLS_SNI_PORT" || fail "TLS SNI listener port is already in use: ${TLS_SNI_PORT}"
    port_free "$OVERRIDE_PORT" || fail "override target port is already in use: ${OVERRIDE_PORT}"
    port_free "$UPSTREAM_PORT" || fail "SOCKS5 upstream port is already in use: ${UPSTREAM_PORT}"
    login
    api_get "/api/workers/${WORKER_ID}" | jq -e '.enabled == true' >/dev/null || fail "worker ${WORKER_ID} is not enabled"
}

start_targets() {
    python3 "$FIXTURE_PY" http --bind 0.0.0.0 --port "$ORIGIN_PORT" --body WORKER_E2E_DIRECT >"$TEMP_DIR/origin.log" 2>&1 & ORIGIN_PID=$!
    python3 "$FIXTURE_PY" tcp-response --bind 0.0.0.0 --port "$TLS_ORIGIN_PORT" --body WORKER_E2E_TLS_SNI >"$TEMP_DIR/tls-origin.log" 2>&1 & TLS_ORIGIN_PID=$!
    python3 "$FIXTURE_PY" http --bind 0.0.0.0 --port "$OVERRIDE_PORT" --body WORKER_E2E_OVERRIDE >"$TEMP_DIR/override.log" 2>&1 & OVERRIDE_PID=$!
    python3 "$FIXTURE_PY" socks --bind 127.0.0.1 --port "$UPSTREAM_PORT" >"$TEMP_DIR/upstream.log" 2>&1 & UPSTREAM_PID=$!
    wait_until "origin target" bash -c "ss -ltn | grep -q ':${ORIGIN_PORT} '"
    wait_until "TLS origin target" bash -c "ss -ltn | grep -q ':${TLS_ORIGIN_PORT} '"
    wait_until "override target" bash -c "ss -ltn | grep -q ':${OVERRIDE_PORT} '"
    wait_until "SOCKS5 upstream" bash -c "ss -ltn | grep -q ':${UPSTREAM_PORT} '"
}

setup_transparent_network() {
    run_privileged ip addr add "$ORIGIN_IP/32" dev lo
    ORIGIN_IP_ADDED=1
    run_privileged ip netns add "$CLIENT_NS"
    NAMESPACE_CREATED=1
    run_privileged ip link add "$HOST_IF" type veth peer name "$CLIENT_IF"
    HOST_LINK_CREATED=1
    run_privileged ip link set "$CLIENT_IF" netns "$CLIENT_NS"
    run_privileged ip addr add "$CLIENT_GATEWAY_IP/30" dev "$HOST_IF"
    run_privileged ip link set "$HOST_IF" up
    run_privileged ip netns exec "$CLIENT_NS" ip link set lo up
    run_privileged ip netns exec "$CLIENT_NS" ip addr add "$CLIENT_IP/30" dev "$CLIENT_IF"
    run_privileged ip netns exec "$CLIENT_NS" ip link set "$CLIENT_IF" up
    run_privileged ip netns exec "$CLIENT_NS" ip route add default via "$CLIENT_GATEWAY_IP"

    run_privileged iptables -t mangle -A PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$ORIGIN_PORT" \
        -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPROXY_PORT" --tproxy-mark "$MARK/$MARK"
    TPROXY_RULE_ADDED=1
    run_privileged ip rule add priority "$RULE_PRIORITY" fwmark "$MARK/$MARK" lookup "$ROUTE_TABLE"
    POLICY_RULE_ADDED=1
    run_privileged ip route add local 0.0.0.0/0 dev lo table "$ROUTE_TABLE"
    POLICY_ROUTE_ADDED=1
}

client_curl() {
    run_privileged ip netns exec "$CLIENT_NS" curl --noproxy '*' --silent --show-error --max-time 8 "$@"
}

assert_socks() {
    local label="$1" host="$2" expected="$3"
    local actual
    actual="$(python3 "$FIXTURE_PY" client --proxy-port "$SOCKS_PORT" --target-host "$host" --target-port "$ORIGIN_PORT" \
        --path / --username "$FIXTURE_USER" --password "$FIXTURE_PASSWORD")" || fail "${label} SOCKS5 request failed"
    [[ "$actual" == "$expected" ]] || fail "${label} returned '$actual', expected '$expected'"
    echo "PASS: SOCKS5 ${label}"
}

assert_socks_blocked() {
    if python3 "$FIXTURE_PY" client --proxy-port "$SOCKS_PORT" --target-host blocked.localhost --target-port "$ORIGIN_PORT" \
        --path / --username "$FIXTURE_USER" --password "$FIXTURE_PASSWORD" >/dev/null 2>&1; then
        fail "SOCKS5 block unexpectedly succeeded"
    fi
    echo "PASS: SOCKS5 block"
}

assert_transparent() {
    local label="$1" host="$2" expected="$3"
    local actual
    actual="$(client_curl -H "Host: ${host}" "http://${ORIGIN_IP}:${ORIGIN_PORT}/")" || fail "${label} transparent request failed"
    [[ "$actual" == "$expected" ]] || fail "${label} returned '$actual', expected '$expected'"
    echo "PASS: transparent ${label}"
}

assert_transparent_blocked() {
    if client_curl -H 'Host: blocked.localhost' "http://${ORIGIN_IP}:${ORIGIN_PORT}/" >/dev/null 2>&1; then
        fail "transparent block unexpectedly succeeded"
    fi
    echo "PASS: transparent block"
}

assert_tls_sni() {
    local actual
    actual="$(python3 "$FIXTURE_PY" tls-client --proxy-port "$TLS_SNI_PORT" --sni tls.localhost)" || fail "TLS SNI request failed"
    [[ "$actual" == "WORKER_E2E_TLS_SNI" ]] || fail "TLS SNI returned '$actual'"
    echo "PASS: TLS SNI direct"
}

run_assertions() {
    assert_socks direct direct.localhost WORKER_E2E_DIRECT
    assert_socks proxy proxy.localhost WORKER_E2E_DIRECT
    assert_socks override override.localhost WORKER_E2E_OVERRIDE
    assert_socks_blocked
    assert_tls_sni

    assert_transparent direct direct.localhost WORKER_E2E_DIRECT
    assert_transparent proxy proxy.localhost WORKER_E2E_DIRECT
    assert_transparent override override.localhost WORKER_E2E_OVERRIDE
    assert_transparent_blocked
}

main() {
    local provision_requested=0
    if [[ "${1:-}" == "--provision" ]]; then
        provision_requested=1
        shift
    fi
    [[ "$#" == 0 ]] || fail "usage: $0 [--provision]"

    preflight
    if (( provision_requested )); then
        provision
    fi
    validate_effective_config
    EXPECTED_CONFIG_HASH="$(api_get "/api/workers/${WORKER_ID}/effective-config" | jq -er '.configHash')"
    wait_until "worker configuration" worker_ready
    wait_until "worker listeners" listener_ready
    start_targets
    setup_transparent_network
    run_assertions
    echo "PASS: worker proxy E2E completed for ${WORKER_ID}"
}

main "$@"
