#!/usr/bin/env bash
#
# Privileged transparent-proxy E2E test.
#
# This test deliberately covers the deployment boundary that normal Netty tests cannot:
# Linux TPROXY, policy routing, IP_TRANSPARENT, HTTP Host sniffing, TLS SNI sniffing,
# route selection, destination override, BLOCK, and control-plane configuration delivery.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/scripts/e2e/transparent-proxy.compose.yml"

CONTROL_PORT="${E2E_CONTROL_PORT:-28443}"
TPORT="${E2E_TPROXY_PORT:-29080}"
HTTP_ORIGIN_PORT="${E2E_HTTP_ORIGIN_PORT:-29090}"
HTTP_OVERRIDE_PORT="${E2E_HTTP_OVERRIDE_PORT:-29091}"
TLS_ORIGIN_PORT="${E2E_TLS_ORIGIN_PORT:-29490}"
TLS_OVERRIDE_PORT="${E2E_TLS_OVERRIDE_PORT:-29491}"

ORIGIN_IP="${E2E_ORIGIN_IP:-198.18.1.2}"
OVERRIDE_IP="${E2E_OVERRIDE_IP:-198.18.1.3}"
CLIENT_GATEWAY_IP="${E2E_CLIENT_GATEWAY_IP:-198.18.0.1}"
CLIENT_IP="${E2E_CLIENT_IP:-198.18.0.2}"
MARK="${E2E_TPROXY_MARK:-0x91}"
ROUTE_TABLE="${E2E_ROUTE_TABLE:-290}"
RULE_PRIORITY="${E2E_RULE_PRIORITY:-1290}"
TIMEOUT_SECONDS="${E2E_TIMEOUT_SECONDS:-90}"
KEEP_ARTIFACTS="${E2E_KEEP_ARTIFACTS:-0}"

RUN_ID="${BASHPID}"
PROJECT="homeproxy-tproxy-e2e-${RUN_ID}"
WORKER_ID="tproxy-e2e-${RUN_ID}"
RULE_COMMENT="home-proxy-tproxy-e2e-${RUN_ID}"
CLIENT_NS="home-proxy-tproxy-e2e-${RUN_ID}"
HOST_IF="hpte${RUN_ID}"
CLIENT_IF="hptc${RUN_ID}"
WORKER_CONTAINER="home-proxy-tproxy-worker-${RUN_ID}"
ORIGIN_CONTAINER="home-proxy-tproxy-origin-${RUN_ID}"
OVERRIDE_CONTAINER="home-proxy-tproxy-override-${RUN_ID}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/home-proxy-tproxy-e2e.XXXXXX")"

CONTROL_IMAGE="${E2E_CONTROL_IMAGE:-home-proxy-control-manager:e2e}"
WORKER_IMAGE="${E2E_WORKER_IMAGE:-home-proxy-worker:e2e}"
CONTROL_BASE="https://127.0.0.1:${CONTROL_PORT}"

SUDO=()
COMPOSE_STARTED=0
WORKER_STARTED=0
ORIGIN_STARTED=0
OVERRIDE_STARTED=0
ORIGIN_IP_ADDED=0
OVERRIDE_IP_ADDED=0
NAMESPACE_CREATED=0
HOST_LINK_CREATED=0
HTTP_RULE_ADDED=0
TLS_RULE_ADDED=0
POLICY_RULE_ADDED=0
POLICY_ROUTE_ADDED=0

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
        if (( SECONDS >= deadline )); then
            fail "timed out waiting for ${description}"
        fi
        sleep 1
    done
}

cleanup() {
    local result=$?
    trap - EXIT
    set +e

    if (( result != 0 )); then
        echo "--- E2E diagnostic logs ---" >&2
        if (( WORKER_STARTED )); then docker logs "$WORKER_CONTAINER" >&2; fi
        if (( COMPOSE_STARTED )); then
            docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" logs --no-color >&2
        fi
    fi

    if (( WORKER_STARTED )); then docker rm -f "$WORKER_CONTAINER" >/dev/null 2>&1; fi
    if (( ORIGIN_STARTED )); then docker rm -f "$ORIGIN_CONTAINER" >/dev/null 2>&1; fi
    if (( OVERRIDE_STARTED )); then docker rm -f "$OVERRIDE_CONTAINER" >/dev/null 2>&1; fi

    if (( TLS_RULE_ADDED )); then
        run_privileged iptables -t mangle -D PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$TLS_ORIGIN_PORT" \
            -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPORT" --tproxy-mark "$MARK/$MARK" >/dev/null 2>&1
    fi
    if (( HTTP_RULE_ADDED )); then
        run_privileged iptables -t mangle -D PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$HTTP_ORIGIN_PORT" \
            -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPORT" --tproxy-mark "$MARK/$MARK" >/dev/null 2>&1
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
    if (( OVERRIDE_IP_ADDED )); then run_privileged ip addr del "$OVERRIDE_IP/32" dev lo >/dev/null 2>&1; fi

    if (( COMPOSE_STARTED )); then
        docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" down --volumes --remove-orphans >/dev/null 2>&1
    fi
    if [[ "$KEEP_ARTIFACTS" != "1" ]]; then rm -rf "$TEMP_DIR"; else echo "E2E artifacts kept at $TEMP_DIR"; fi
    exit "$result"
}
trap cleanup EXIT

controller_ready() {
    curl -ksSf "$CONTROL_BASE/admin/setup-status" >/dev/null 2>&1
}

listener_ready() {
    ss -ltn | awk -v port=":${TPORT}" '$4 ~ (port "$") { found = 1 } END { exit !found }'
}

target_ready() {
    ss -ltn | awk -v originHttp=":${HTTP_ORIGIN_PORT}" -v originTls=":${TLS_ORIGIN_PORT}" \
        -v overrideHttp=":${HTTP_OVERRIDE_PORT}" -v overrideTls=":${TLS_OVERRIDE_PORT}" \
        '$4 ~ (originHttp "$") { originHttpFound = 1 }
         $4 ~ (originTls "$") { originTlsFound = 1 }
         $4 ~ (overrideHttp "$") { overrideHttpFound = 1 }
         $4 ~ (overrideTls "$") { overrideTlsFound = 1 }
         END { exit !(originHttpFound && originTlsFound && overrideHttpFound && overrideTlsFound) }'
}

worker_status_ready() {
    local status
    status="$(curl -ksS -H "Authorization: Bearer ${ADMIN_TOKEN}" "$CONTROL_BASE/api/workers/$WORKER_ID/status" 2>/dev/null || true)"
    [[ -n "$status" ]] && jq -e '.online == true and .applySuccess == true and .runningInboundCount == 1' >/dev/null <<<"$status"
}

api_get() {
    curl -ksS --fail-with-body -H "Authorization: Bearer ${ADMIN_TOKEN}" "$CONTROL_BASE$1"
}

api_post() {
    curl -ksS --fail-with-body -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' --data "$2" "$CONTROL_BASE$1"
}

client_curl() {
    run_privileged ip netns exec "$CLIENT_NS" curl --noproxy '*' -ksS --max-time 8 "$@"
}

assert_response() {
    local label="$1"
    local expected="$2"
    shift 2
    local actual
    actual="$("$@")" || fail "${label} request failed"
    [[ "$actual" == "$expected" ]] || fail "${label} response was '$actual', expected '$expected'"
    echo "PASS: $label"
}

assert_blocked() {
    local label="$1"
    shift
    local output
    if output="$("$@" 2>&1)"; then
        fail "${label} unexpectedly succeeded: $output"
    fi
    echo "PASS: $label"
}

preflight() {
    [[ "$(uname -s)" == "Linux" ]] || fail "transparent E2E requires Linux"
    for command in docker curl jq openssl ip iptables ss awk grep sed; do require_command "$command"; done
    docker info >/dev/null || fail "Docker daemon is unavailable"
    docker compose version >/dev/null || fail "Docker Compose v2 is unavailable"
    if [[ "${E2E_BUILD:-1}" != "0" ]]; then require_command mvn; fi

    if (( EUID != 0 )); then
        require_command sudo
        sudo -n true || fail "passwordless sudo is required for network namespace and iptables operations"
        SUDO=(sudo)
    fi

    [[ ${#HOST_IF} -le 15 && ${#CLIENT_IF} -le 15 ]] || fail "generated veth interface name exceeds Linux's 15-character limit"
    ! listener_ready || fail "TPROXY test port ${TPORT} is already listening"
    ! run_privileged ip netns list | grep -q "^${CLIENT_NS}" || fail "test network namespace already exists"
    ! run_privileged ip rule show | grep -q "^${RULE_PRIORITY}:" || fail "policy-rule priority ${RULE_PRIORITY} is already in use"
    ! run_privileged ip route show table "$ROUTE_TABLE" 2>/dev/null | grep -q . || fail "route table ${ROUTE_TABLE} is not empty"
}

build_images() {
    if [[ "${E2E_BUILD:-1}" == "0" ]]; then
        return
    fi
    echo "Building current control-manager and worker E2E images"
    local repo="${MAVEN_REPO:-$ROOT_DIR/.m2/repository}"
    mvn -f "$ROOT_DIR/backend/pom.xml" -B -ntp -Dmaven.repo.local="$repo" \
        -pl control-manager,proxy-worker -am package -DskipTests
    docker build -f "$ROOT_DIR/deploy/docker/Dockerfile.control-manager" -t "$CONTROL_IMAGE" "$ROOT_DIR"
    docker build -f "$ROOT_DIR/deploy/docker/Dockerfile.proxy-worker" -t "$WORKER_IMAGE" "$ROOT_DIR"
}

create_target_files() {
    mkdir -p "$TEMP_DIR/origin-http" "$TEMP_DIR/origin-tls" "$TEMP_DIR/override-http" "$TEMP_DIR/override-tls"
    # The worker token itself is 0600, but nginx runs as an unprivileged worker process and
    # needs to traverse the parent directory to read its read-only test fixtures.
    chmod 755 "$TEMP_DIR" "$TEMP_DIR/origin-http" "$TEMP_DIR/origin-tls" "$TEMP_DIR/override-http" "$TEMP_DIR/override-tls"
    printf 'HTTP_DIRECT_E2E\n' > "$TEMP_DIR/origin-http/index.html"
    printf 'TLS_DIRECT_E2E\n' > "$TEMP_DIR/origin-tls/index.html"
    printf 'HTTP_OVERRIDE_E2E\n' > "$TEMP_DIR/override-http/index.html"
    printf 'TLS_OVERRIDE_E2E\n' > "$TEMP_DIR/override-tls/index.html"

    openssl req -x509 -nodes -newkey rsa:2048 -keyout "$TEMP_DIR/origin.key" -out "$TEMP_DIR/origin.crt" \
        -days 1 -subj '/CN=direct.tproxy-e2e.test' >/dev/null 2>&1
    openssl req -x509 -nodes -newkey rsa:2048 -keyout "$TEMP_DIR/override.key" -out "$TEMP_DIR/override.crt" \
        -days 1 -subj '/CN=tls-override.tproxy-e2e.test' >/dev/null 2>&1

    cat > "$TEMP_DIR/origin.conf" <<EOF
server {
  listen ${ORIGIN_IP}:${HTTP_ORIGIN_PORT};
  location / { root /usr/share/nginx/html/http; }
}
server {
  listen ${ORIGIN_IP}:${TLS_ORIGIN_PORT} ssl;
  ssl_certificate /etc/nginx/conf.d/origin.crt;
  ssl_certificate_key /etc/nginx/conf.d/origin.key;
  location / { root /usr/share/nginx/html/tls; }
}
EOF
    cat > "$TEMP_DIR/override.conf" <<EOF
server {
  listen ${OVERRIDE_IP}:${HTTP_OVERRIDE_PORT};
  location / { root /usr/share/nginx/html/http; }
}
server {
  listen ${OVERRIDE_IP}:${TLS_OVERRIDE_PORT} ssl;
  ssl_certificate /etc/nginx/conf.d/override.crt;
  ssl_certificate_key /etc/nginx/conf.d/override.key;
  location / { root /usr/share/nginx/html/tls; }
}
EOF
}

start_targets() {
    docker run -d --rm --name "$ORIGIN_CONTAINER" --network host \
        -v "$TEMP_DIR/origin.conf:/etc/nginx/conf.d/default.conf:ro" \
        -v "$TEMP_DIR/origin-http:/usr/share/nginx/html/http:ro" \
        -v "$TEMP_DIR/origin-tls:/usr/share/nginx/html/tls:ro" \
        -v "$TEMP_DIR/origin.crt:/etc/nginx/conf.d/origin.crt:ro" \
        -v "$TEMP_DIR/origin.key:/etc/nginx/conf.d/origin.key:ro" \
        nginx:1.27-alpine >/dev/null
    ORIGIN_STARTED=1
    docker run -d --rm --name "$OVERRIDE_CONTAINER" --network host \
        -v "$TEMP_DIR/override.conf:/etc/nginx/conf.d/default.conf:ro" \
        -v "$TEMP_DIR/override-http:/usr/share/nginx/html/http:ro" \
        -v "$TEMP_DIR/override-tls:/usr/share/nginx/html/tls:ro" \
        -v "$TEMP_DIR/override.crt:/etc/nginx/conf.d/override.crt:ro" \
        -v "$TEMP_DIR/override.key:/etc/nginx/conf.d/override.key:ro" \
        nginx:1.27-alpine >/dev/null
    OVERRIDE_STARTED=1
    wait_until "origin target listeners" target_ready
}

setup_control_plane() {
    export E2E_CONTROL_IMAGE="$CONTROL_IMAGE"
    export E2E_CONTROL_PORT="$CONTROL_PORT"
    export E2E_MYSQL_PASSWORD="e2e_mysql_${RUN_ID}"
    export E2E_CLICKHOUSE_PASSWORD="e2e_clickhouse_${RUN_ID}"
    export E2E_JWT_SECRET="e2e_jwt_$(openssl rand -hex 24)"

    COMPOSE_STARTED=1
    docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" up -d
    wait_until "control-manager HTTPS endpoint" controller_ready

    local admin_password="$(openssl rand -hex 24)"
    local setup_response
    setup_response="$(curl -ksS --fail-with-body -H 'Content-Type: application/json' \
        --data "{\"username\":\"e2e_admin\",\"password\":\"${admin_password}\"}" "$CONTROL_BASE/admin/setup")"
    ADMIN_TOKEN="$(jq -er '.token' <<<"$setup_response")"
}

create_runtime_config() {
    local anonymous_id fallback_route_id block_id direct_id http_override_id tls_override_id
    anonymous_id="$(api_get '/api/users?pageSize=200&sortBy=id&sortDir=asc' | jq -er '.items[] | select(.username == "Anonymous user") | .id')"
    fallback_route_id="$(api_get '/api/routes?pageSize=200&sortBy=id&sortDir=asc' | jq -er '.items[] | select(.name == "兜底直连路由规则") | .id')"

    block_id="$(api_post /api/routes '{"name":"e2e-tproxy-block","rules":[{"conditionType":"DOMAIN","op":"IN","value":"blocked-http.tproxy-e2e.test"},{"conditionType":"DOMAIN","op":"IN","value":"blocked-tls.tproxy-e2e.test"}],"policy":"BLOCK","status":1}' | jq -er '.id')"
    direct_id="$(api_post /api/routes '{"name":"e2e-tproxy-direct","rules":[{"conditionType":"DOMAIN","op":"IN","value":"direct-http.tproxy-e2e.test"},{"conditionType":"DOMAIN","op":"IN","value":"direct-tls.tproxy-e2e.test"}],"policy":"DIRECT","status":1}' | jq -er '.id')"
    http_override_id="$(api_post /api/routes "$(jq -n --arg host "$OVERRIDE_IP" --argjson port "$HTTP_OVERRIDE_PORT" '{name:"e2e-tproxy-http-override",rules:[{conditionType:"DOMAIN",op:"IN",value:"http-override.tproxy-e2e.test"}],policy:"DESTINATION_OVERRIDE",outboundProxyHost:$host,outboundProxyPort:$port,status:1}')" | jq -er '.id')"
    tls_override_id="$(api_post /api/routes "$(jq -n --arg host "$OVERRIDE_IP" --argjson port "$TLS_OVERRIDE_PORT" '{name:"e2e-tproxy-tls-override",rules:[{conditionType:"DOMAIN",op:"IN",value:"tls-override.tproxy-e2e.test"}],policy:"DESTINATION_OVERRIDE",outboundProxyHost:$host,outboundProxyPort:$port,status:1}')" | jq -er '.id')"

    local worker_response worker_token inbound
    worker_response="$(api_post /api/workers "{\"workerId\":\"${WORKER_ID}\",\"displayName\":\"Transparent proxy E2E\",\"description\":\"ephemeral E2E worker\"}")"
    worker_token="$(jq -er '.token' <<<"$worker_response")"
    (
        umask 077
        printf '%s' "$worker_token" > "$TEMP_DIR/worker-token"
    )

    inbound="$(jq -n --arg worker "$WORKER_ID" --argjson fallback "$fallback_route_id" --argjson anonymous "$anonymous_id" --argjson tport "$TPORT" \
        --argjson block "$block_id" --argjson direct "$direct_id" --argjson httpOverride "$http_override_id" --argjson tlsOverride "$tls_override_id" \
        '{workerId:$worker,fallbackRouteId:$fallback,anonymousUserId:$anonymous,name:"e2e-tproxy-inbound",protocol:"TP_PROXY",listenIp:"0.0.0.0",port:$tport,tlsEnabled:false,sniffEnabled:true,inboundRouteBindings:[{userIds:[$anonymous],routeIds:[$block,$direct,$httpOverride,$tlsOverride]}],status:1,notes:"ephemeral privileged E2E inbound"}')"
    api_post /api/inbounds "$inbound" >/dev/null

    docker run -d --rm --name "$WORKER_CONTAINER" --network host --cap-add NET_ADMIN \
        -v "$TEMP_DIR/worker-token:/run/secrets/worker-token:ro" \
        -e CONTROL_MANAGER_URL="$CONTROL_BASE" \
        -e WORKER_ID="$WORKER_ID" \
        -e WORKER_TOKEN_FILE=/run/secrets/worker-token \
        -e LAST_KNOWN_GOOD_CONFIG=/tmp/last-known-good-config.json \
        -e WORKER_CONTROL_POLL_INTERVAL_MS=500 \
        "$WORKER_IMAGE" >/dev/null
    WORKER_STARTED=1
    wait_until "TPROXY worker listener" listener_ready
    wait_until "worker applied config" worker_status_ready
}

setup_network() {
    run_privileged ip addr add "$ORIGIN_IP/32" dev lo
    ORIGIN_IP_ADDED=1
    run_privileged ip addr add "$OVERRIDE_IP/32" dev lo
    OVERRIDE_IP_ADDED=1
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

    run_privileged iptables -t mangle -A PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$HTTP_ORIGIN_PORT" \
        -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPORT" --tproxy-mark "$MARK/$MARK"
    HTTP_RULE_ADDED=1
    run_privileged iptables -t mangle -A PREROUTING -i "$HOST_IF" -p tcp -d "$ORIGIN_IP" --dport "$TLS_ORIGIN_PORT" \
        -m comment --comment "$RULE_COMMENT" -j TPROXY --on-ip 0.0.0.0 --on-port "$TPORT" --tproxy-mark "$MARK/$MARK"
    TLS_RULE_ADDED=1
    run_privileged ip rule add priority "$RULE_PRIORITY" fwmark "$MARK/$MARK" lookup "$ROUTE_TABLE"
    POLICY_RULE_ADDED=1
    run_privileged ip route add local 0.0.0.0/0 dev lo table "$ROUTE_TABLE"
    POLICY_ROUTE_ADDED=1
}

run_assertions() {
    assert_response 'HTTP direct' HTTP_DIRECT_E2E client_curl -H 'Host: direct-http.tproxy-e2e.test' "http://${ORIGIN_IP}:${HTTP_ORIGIN_PORT}/"
    assert_response 'HTTP destination override' HTTP_OVERRIDE_E2E client_curl -H 'Host: http-override.tproxy-e2e.test' "http://${ORIGIN_IP}:${HTTP_ORIGIN_PORT}/"
    assert_blocked 'HTTP block' client_curl -H 'Host: blocked-http.tproxy-e2e.test' "http://${ORIGIN_IP}:${HTTP_ORIGIN_PORT}/"

    assert_response 'TLS SNI direct' TLS_DIRECT_E2E client_curl --resolve "direct-tls.tproxy-e2e.test:${TLS_ORIGIN_PORT}:${ORIGIN_IP}" "https://direct-tls.tproxy-e2e.test:${TLS_ORIGIN_PORT}/"
    assert_response 'TLS SNI destination override' TLS_OVERRIDE_E2E client_curl --resolve "tls-override.tproxy-e2e.test:${TLS_ORIGIN_PORT}:${ORIGIN_IP}" "https://tls-override.tproxy-e2e.test:${TLS_ORIGIN_PORT}/"
    assert_blocked 'TLS SNI block' client_curl --resolve "blocked-tls.tproxy-e2e.test:${TLS_ORIGIN_PORT}:${ORIGIN_IP}" "https://blocked-tls.tproxy-e2e.test:${TLS_ORIGIN_PORT}/"

    local counters packets
    counters="$(run_privileged iptables-save -c -t mangle | grep -- "$RULE_COMMENT")"
    packets="$(sed -En 's/^\[([0-9]+):[0-9]+\].*/\1/p' <<<"$counters" | awk '{ sum += $1 } END { print sum + 0 }')"
    (( packets > 0 )) || fail "TPROXY rules were not hit"
    echo "PASS: TPROXY rules received ${packets} packets"
}

main() {
    preflight
    build_images
    setup_control_plane
    create_runtime_config
    setup_network
    create_target_files
    start_targets
    run_assertions
    echo "PASS: privileged transparent-proxy E2E completed"
}

main "$@"
