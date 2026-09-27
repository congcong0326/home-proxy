#!/usr/bin/env bash
#
# Isolated full-process E2E for DIRECT outbound address safety.
#
# It starts the control-manager and worker containers, publishes real inbound
# configuration, and verifies protocol-specific rejection and audit behavior.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/scripts/e2e/direct-outbound-address-safety.compose.yml"
FIXTURE_PY="$ROOT_DIR/scripts/e2e/worker-proxy-fixture.py"

CONTROL_PORT="${E2E_DIRECT_SAFETY_CONTROL_PORT:-28453}"
MANAGEMENT_PORT="${E2E_DIRECT_SAFETY_MANAGEMENT_PORT:-28454}"
SOCKS_PORT="${E2E_DIRECT_SAFETY_SOCKS_PORT:-29281}"
HTTP_PORT="${E2E_DIRECT_SAFETY_HTTP_PORT:-29282}"
SHADOWSOCKS_PORT="${E2E_DIRECT_SAFETY_SHADOWSOCKS_PORT:-29283}"
TLS_SNI_PORT="${E2E_DIRECT_SAFETY_TLS_SNI_PORT:-29284}"
OVERRIDE_PORT="${E2E_DIRECT_SAFETY_OVERRIDE_PORT:-29290}"
TIMEOUT_SECONDS="${E2E_TIMEOUT_SECONDS:-90}"

RUN_ID="${BASHPID}"
PROJECT="homeproxy-direct-safety-e2e-${RUN_ID}"
WORKER_ID="direct-safety-e2e-${RUN_ID}"
WORKER_CONTAINER="home-proxy-direct-safety-worker-${RUN_ID}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/home-proxy-direct-safety-e2e.XXXXXX")"

CONTROL_IMAGE="${E2E_CONTROL_IMAGE:-home-proxy-control-manager:e2e}"
WORKER_IMAGE="${E2E_WORKER_IMAGE:-home-proxy-worker:e2e}"
CONTROL_BASE="https://127.0.0.1:${CONTROL_PORT}"
E2E_USER="direct-safety-user"
E2E_PASSWORD="direct-safety-pass"

COMPOSE_STARTED=0
WORKER_STARTED=0
OVERRIDE_PID=0
ADMIN_TOKEN=""
E2E_CLICKHOUSE_PASSWORD=""

fail() {
    echo "E2E failure: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
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
        echo "--- direct outbound safety E2E diagnostics ---" >&2
        if (( WORKER_STARTED )); then docker logs "$WORKER_CONTAINER" >&2; fi
        if (( COMPOSE_STARTED )); then
            docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" logs --no-color >&2
        fi
    fi
    [[ "$OVERRIDE_PID" == 0 ]] || kill "$OVERRIDE_PID" >/dev/null 2>&1
    if (( WORKER_STARTED )); then docker rm -f "$WORKER_CONTAINER" >/dev/null 2>&1; fi
    if (( COMPOSE_STARTED )); then
        docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" down --volumes --remove-orphans >/dev/null 2>&1
    fi
    rm -rf "$TEMP_DIR"
    exit "$result"
}
trap cleanup EXIT

api_get() {
    curl -ksS --fail-with-body -H "Authorization: Bearer ${ADMIN_TOKEN}" "$CONTROL_BASE$1"
}

api_post() {
    local path="$1"
    local body="$2"
    local response
    if ! response="$(curl -ksS --fail-with-body -X POST -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H 'Content-Type: application/json' --data "$body" "$CONTROL_BASE$path")"; then
        fail "POST ${path} failed: ${response}"
    fi
    printf '%s\n' "$response"
}

controller_ready() {
    curl -ksSf "$CONTROL_BASE/admin/setup-status" >/dev/null 2>&1
}

port_free() {
    ss -ltn | awk -v port=":$1" '$4 ~ (port "$") { found = 1 } END { exit found }'
}

listeners_ready() {
    ss -ltn | awk -v socks=":${SOCKS_PORT}" -v http=":${HTTP_PORT}" -v ss=":${SHADOWSOCKS_PORT}" -v tls=":${TLS_SNI_PORT}" '
        $4 ~ (socks "$") { socksFound = 1 }
        $4 ~ (http "$") { httpFound = 1 }
        $4 ~ (ss "$") { shadowsocksFound = 1 }
        $4 ~ (tls "$") { tlsFound = 1 }
        END { exit !(socksFound && httpFound && shadowsocksFound && tlsFound) }'
}

worker_ready() {
    local status
    status="$(api_get "/api/workers/${WORKER_ID}/status" 2>/dev/null || true)"
    [[ -n "$status" ]] && jq -e '.online == true and .applySuccess == true and .runningInboundCount == 4' >/dev/null <<<"$status"
}

preflight() {
    [[ "$(uname -s)" == Linux ]] || fail "direct outbound safety E2E requires Linux"
    for command in docker curl jq python3 ss openssl; do require_command "$command"; done
    if [[ "${E2E_BUILD:-1}" != "0" ]]; then require_command mvn; fi
    python3 -c 'from cryptography.hazmat.primitives.ciphers.aead import AESGCM' \
        || fail "Shadowsocks E2E requires Python cryptography (AES-GCM)"
    docker info >/dev/null || fail "Docker daemon is unavailable"
    docker compose version >/dev/null || fail "Docker Compose v2 is unavailable"
    [[ -f "$FIXTURE_PY" ]] || fail "fixture helper is missing: ${FIXTURE_PY}"
    for port in "$CONTROL_PORT" "$MANAGEMENT_PORT" "$SOCKS_PORT" "$HTTP_PORT" "$SHADOWSOCKS_PORT" "$TLS_SNI_PORT" "$OVERRIDE_PORT"; do
        port_free "$port" || fail "E2E port is already in use: ${port}"
    done
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

setup_control_plane() {
    export E2E_CONTROL_IMAGE="$CONTROL_IMAGE"
    export E2E_CONTROL_PORT="$CONTROL_PORT"
    export E2E_MYSQL_PASSWORD="e2e_mysql_${RUN_ID}"
    E2E_CLICKHOUSE_PASSWORD="e2e_clickhouse_${RUN_ID}"
    export E2E_CLICKHOUSE_PASSWORD
    export E2E_JWT_SECRET="e2e_jwt_$(openssl rand -hex 24)"

    COMPOSE_STARTED=1
    docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" up -d
    wait_until "control-manager HTTPS endpoint" controller_ready

    local admin_password setup_response
    admin_password="$(openssl rand -hex 24)"
    setup_response="$(curl -ksS --fail-with-body -H 'Content-Type: application/json' \
        --data "{\"username\":\"e2e_admin\",\"password\":\"${admin_password}\"}" "$CONTROL_BASE/admin/setup")" \
        || fail "POST /admin/setup failed"
    ADMIN_TOKEN="$(jq -er '.token' <<<"$setup_response")"
}

create_route() {
    api_post /api/routes "$1" | jq -er '.id'
}

create_inbound() {
    local name="$1"
    local protocol="$2"
    local port="$3"
    local fallback_id="$4"
    local user_id="$5"
    local direct_id="$6"
    local override_id="$7"
    local ss_method="${8:-}"
    local body
    body="$(jq -n --arg worker "$WORKER_ID" --arg name "$name" --arg protocol "$protocol" \
        --arg ssMethod "$ss_method" --argjson port "$port" --argjson fallback "$fallback_id" \
        --argjson user "$user_id" --argjson direct "$direct_id" --argjson override "$override_id" '
        {workerId:$worker,fallbackRouteId:$fallback,anonymousUserId:$user,name:$name,protocol:$protocol,
         listenIp:"0.0.0.0",port:$port,tlsEnabled:false,sniffEnabled:($protocol == "TLS_SNI_TCP"),
         ssMethod:(if $ssMethod == "" then null else $ssMethod end),
         inboundRouteBindings:[{userIds:[$user],routeIds:[$direct,$override]}],status:1,
         notes:"ephemeral DIRECT address safety E2E fixture"}')"
    api_post /api/inbounds "$body" >/dev/null
}

create_runtime_config() {
    local fallback_id user_id direct_id override_id worker_token
    fallback_id="$(api_get '/api/routes?pageSize=200&sortBy=id&sortDir=asc' | jq -er '.items[] | select(.name == "兜底直连路由规则") | .id')"
    user_id="$(api_post /api/users "$(jq -n --arg user "$E2E_USER" --arg password "$E2E_PASSWORD" \
        '{username:$user,credential:$password,status:1,remark:"ephemeral DIRECT address safety E2E user"}')" | jq -er '.id')"
    direct_id="$(create_route '{"name":"e2e-direct-localhost","rules":[{"conditionType":"DOMAIN","op":"IN","value":"localhost"}],"policy":"DIRECT","status":1}')"
    override_id="$(create_route "$(jq -n --argjson port "$OVERRIDE_PORT" \
        '{name:"e2e-direct-safety-override",rules:[{conditionType:"DOMAIN",op:"IN",value:"override.e2e.test"}],policy:"DESTINATION_OVERRIDE",outboundProxyHost:"127.0.0.1",outboundProxyPort:$port,status:1}')")"

    worker_token="hpw_$(openssl rand -hex 32)"
    (
        umask 077
        printf '%s' "$worker_token" > "$TEMP_DIR/worker-token"
    )
    api_post /api/workers "$(jq -n --arg worker "$WORKER_ID" --arg token "$worker_token" \
        --arg url "wss://home-proxy-control-manager:${MANAGEMENT_PORT}/management/ws" \
        '{workerId:$worker,displayName:"DIRECT safety E2E",description:"ephemeral E2E worker",managementUrl:$url,managementToken:$token}')" >/dev/null

    create_inbound e2e-direct-safety-socks SOCKS5 "$SOCKS_PORT" "$fallback_id" "$user_id" "$direct_id" "$override_id"
    create_inbound e2e-direct-safety-http HTTPS_CONNECT "$HTTP_PORT" "$fallback_id" "$user_id" "$direct_id" "$override_id"
    create_inbound e2e-direct-safety-shadowsocks SHADOW_SOCKS "$SHADOWSOCKS_PORT" "$fallback_id" "$user_id" "$direct_id" "$override_id" aes_128_gcm
    create_inbound e2e-direct-safety-tls-sni TLS_SNI_TCP "$TLS_SNI_PORT" "$fallback_id" "$user_id" "$direct_id" "$override_id"

    docker run -d --rm --name "$WORKER_CONTAINER" --network host \
        -v "$TEMP_DIR/worker-token:/run/secrets/worker-token:ro" \
        -e CONTROL_MANAGER_URL="$CONTROL_BASE" \
        -e WORKER_ID="$WORKER_ID" \
        -e WORKER_MANAGEMENT_ENABLED=true \
        -e WORKER_MANAGEMENT_PORT="$MANAGEMENT_PORT" \
        -e WORKER_MANAGEMENT_TOKEN_FILE=/run/secrets/worker-token \
        -e LAST_KNOWN_GOOD_CONFIG=/tmp/last-known-good-config.json \
        -e WORKER_MANAGEMENT_POLL_INTERVAL_MS=500 \
        "$WORKER_IMAGE" >/dev/null
    WORKER_STARTED=1
    wait_until "worker listeners" listeners_ready
    wait_until "worker applied configuration" worker_ready
}

start_override_target() {
    python3 "$FIXTURE_PY" http --bind 127.0.0.1 --port "$OVERRIDE_PORT" --body DIRECT_SAFETY_OVERRIDE \
        >"$TEMP_DIR/override.log" 2>&1 &
    OVERRIDE_PID=$!
    wait_until "destination override target" bash -c "ss -ltn | grep -q ':${OVERRIDE_PORT} '"
}

assert_equals() {
    local label="$1"
    local expected="$2"
    shift 2
    local actual
    actual="$("$@")" || fail "${label} command failed"
    [[ "$actual" == "$expected" ]] || fail "${label} returned '${actual}', expected '${expected}'"
    echo "PASS: ${label}"
}

assert_tls_sni_closed() {
    local actual
    actual="$(python3 "$FIXTURE_PY" tls-client --proxy-port "$TLS_SNI_PORT" --sni localhost)" \
        || fail "TLS SNI private-resolver request did not close cleanly"
    [[ -z "$actual" ]] || fail "TLS SNI private-resolver request returned unexpected data: ${actual}"
    echo "PASS: TLS SNI private DNS result is blocked"
}

audit_private_blocks_visible() {
    local counts blocked network_errors
    counts="$(docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE" exec -T clickhouse \
        clickhouse-client --user default --password "$E2E_CLICKHOUSE_PASSWORD" --format TSVRaw --query \
        "SELECT countIf(error_code = 'PRIVATE_ADDRESS_BLOCKED' AND status = 403), countIf(error_code = 'NETWORK_ERROR' AND original_target_host IN ('127.0.0.1', 'localhost')) FROM default.access_log WHERE worker_id = '${WORKER_ID}'")" || return 1
    blocked="${counts%%$'\t'*}"
    network_errors="${counts#*$'\t'}"
    [[ "$blocked" -ge 4 && "$network_errors" == 0 ]]
}

run_assertions() {
    assert_equals "SOCKS5 private literal returns ruleset forbidden" 2 \
        python3 "$FIXTURE_PY" socks-connect-status --proxy-port "$SOCKS_PORT" --target-host 127.0.0.1 --target-port 22 \
        --username "$E2E_USER" --password "$E2E_PASSWORD"
    assert_equals "HTTP CONNECT private literal returns 403" 403 \
        python3 "$FIXTURE_PY" http-connect-status --proxy-port "$HTTP_PORT" --target-host 127.0.0.1 --target-port 22 \
        --username "$E2E_USER" --password "$E2E_PASSWORD"
    assert_equals "SOCKS5 destination override reaches trusted internal target" DIRECT_SAFETY_OVERRIDE \
        python3 "$FIXTURE_PY" client --proxy-port "$SOCKS_PORT" --target-host override.e2e.test --target-port 443 --path / \
        --username "$E2E_USER" --password "$E2E_PASSWORD"
    python3 "$FIXTURE_PY" shadowsocks-expect-close --proxy-port "$SHADOWSOCKS_PORT" --target-host 127.0.0.1 --target-port 22 \
        --password "$E2E_PASSWORD" || fail "Shadowsocks private literal was not closed"
    echo "PASS: Shadowsocks private literal is blocked"
    assert_tls_sni_closed
    wait_until "PRIVATE_ADDRESS_BLOCKED audit records" audit_private_blocks_visible
    echo "PASS: audit records private-address blocks without network-error classification"
}

main() {
    preflight
    build_images
    setup_control_plane
    create_runtime_config
    start_override_target
    run_assertions
    echo "PASS: DIRECT outbound address safety E2E completed"
}

main "$@"
