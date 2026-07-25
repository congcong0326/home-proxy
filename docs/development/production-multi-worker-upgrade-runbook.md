# Production Multi-Worker Upgrade Runbook

This runbook upgrades an existing single-worker deployment to controller/worker version 2.0 with per-worker tokens. It assumes the repository is checked out at `$ROOT` on the control host and each worker host.

Do not reassign an enabled inbound to a new worker before that worker is online and has applied a valid configuration. Reassignment removes that inbound from the old worker's next configuration pull.

## 1. Define The Upgrade Inputs

Run these commands on the control host. Use an immutable release tag or commit, not an uncommitted worktree.

```bash
export ROOT=/opt/home-proxy
export RELEASE=<immutable-release-tag-or-commit>
export CONTROL_ENV=/opt/home-proxy/env/control.env
export BACKUP_DIR=/opt/home-proxy/backups/$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"
cd "$ROOT"
git status --short
git rev-parse HEAD
```

The release must include `V20__create_worker_metric_samples.sql`,
`V21__create_proxy_worker_registry.sql`, and
`V22__bind_inbounds_tasks_and_status_to_worker.sql`.

Record every active inbound and its intended destination worker before changing the controller. Existing inbounds and unconsumed tasks are initially assigned to `default` by the migration.

## 2. Backup And Preflight The Database

Back up MySQL, ClickHouse data, and `data/tls` using the production backup mechanism. Keep the current application images under an immutable local tag as well.

```bash
mkdir -p "$BACKUP_DIR/tls"
cp -a data/tls/. "$BACKUP_DIR/tls/"
docker image tag home-proxy-control-manager:latest "home-proxy-control-manager:pre-worker-v2-$(date +%Y%m%d-%H%M%S)"
docker image tag home-proxy-worker:latest "home-proxy-worker:pre-worker-v2-$(date +%Y%m%d-%H%M%S)"
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events "$MYSQL_DATABASE"' \
  > "$BACKUP_DIR/tpproxy.sql"
```

Check Flyway history before deployment. If V20 exists, its script and checksum must match the expected output. Do not run `flyway repair` to bypass a mismatch.

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT version, script, checksum, success
FROM flyway_schema_history
WHERE version >= 19
ORDER BY installed_rank;

SELECT worker_id, COUNT(*) AS status_rows
FROM worker_status
GROUP BY worker_id;

SELECT COUNT(*) AS pending_tasks
FROM worker_tasks
WHERE consumed_at IS NULL;

SELECT id, username, ip_address
FROM users
WHERE username = '\''Anonymous user'\'' OR ip_address = '\''127.0.0.1'\'';"'
```

Expected Flyway states:

- V20 is absent, or it is `V20__create_worker_metric_samples.sql` with checksum `-468473104`.
- V21 and V22 are absent before the first upgrade attempt.
- All reported rows have `success=1`.

Stop the rollout for any other result. MySQL DDL in V21/V22 is not an automatic rollback boundary; restore or repair a staging clone before changing production.

## 3. Prepare TLS And Controller Environment

The new worker Compose configuration connects to `https://proxy-manager.internal:18443`. The control certificate must contain `proxy-manager.internal` in its DNS subject alternative names. Distribute only `control-manager.crt` to workers; never distribute the P12 file or keystore password.

Add this temporary compatibility setting to `$CONTROL_ENV` for the controller rollout:

```dotenv
WORKER_AUTH_LEGACY_ENABLED=true
```

`legacy` mode authenticates unauthenticated old workers as `default` only. It does not preserve individual historical worker ownership and must be disabled after all workers use tokens.

The current control Compose configuration enables HTTPS directly. Existing workers using the old HTTP endpoint will stop receiving new configuration after this controller switch, but continue serving their currently applied configuration until they are restarted. Keep the controller-to-worker transition short and do not restart an old worker before its HTTPS, certificate, and token files are ready.

Validate configuration interpolation before changing containers:

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml config --quiet
```

## 4. Upgrade The Controller

Build the full release, including frontend static assets, then start only the controller service.

```bash
make package
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml up -d --build control-manager
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml ps
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml logs --tail=200 control-manager
```

Wait for Flyway to report V21 and V22 as applied and for `ControlManagerApplication` to start. Then verify the migrated data:

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT worker_id, enabled, token_hash IS NOT NULL AS token_present
FROM proxy_worker ORDER BY worker_id;

SELECT worker_id, COUNT(*) AS inbounds
FROM inbound_configs GROUP BY worker_id;

SELECT worker_id, COUNT(*) AS pending_tasks
FROM worker_tasks WHERE consumed_at IS NULL GROUP BY worker_id;

SELECT COUNT(*) AS null_default_references
FROM inbound_configs
WHERE fallback_route_id IS NULL OR anonymous_user_id IS NULL;"'
```

All existing inbounds and outstanding tasks should show `worker_id=default`. `null_default_references` must be zero.

## 5. Create Worker Credentials And Environment Files

Use the controller UI to create a token for `default`, then create tokens for each additional target worker. A token is shown exactly once. Do not put the token in Git, shell history, or a Compose environment file.

For each worker host, create its token file and local environment file. Replace the placeholders before starting it.

```bash
export ROOT=/opt/home-proxy
export WORKER_ID=shanghai-01
export CONTROL_MANAGER_HOST_IP=192.168.1.10
export WORKER_ENV=/opt/home-proxy/env/workers/${WORKER_ID}.env
export TOKEN_FILE=/opt/home-proxy/secrets/${WORKER_ID}.token

install -d -m 700 "$(dirname "$TOKEN_FILE")" "$(dirname "$WORKER_ENV")" "$ROOT/data/tls"
install -m 600 /dev/null "$TOKEN_FILE"
# Write the one-time token into $TOKEN_FILE with an editor that preserves 0600 permissions.
cp /secure-transfer/control-manager.crt "$ROOT/data/tls/control-manager.crt"

cat > "$WORKER_ENV" <<EOF
WORKER_ID=${WORKER_ID}
CONTROL_MANAGER_HOST_IP=${CONTROL_MANAGER_HOST_IP}
CONTROL_HTTPS_HOST_PORT=18443
WORKER_TOKEN_HOST_FILE=${TOKEN_FILE}
EOF
chmod 600 "$WORKER_ENV"
```

For an existing single worker, use `WORKER_ID=default`. Do not move active inbounds to another worker in this step.

## 6. Upgrade Workers One At A Time

On each worker host, first validate its Compose configuration, then build and start it using the local environment file.

```bash
cd "$ROOT"
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml config --quiet
make docker-up-worker WORKER_ENV_FILE="$WORKER_ENV"
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml ps
docker compose --env-file "$WORKER_ENV" -f deploy/docker/docker-compose.worker.yml logs --tail=200 proxy-worker
```

Do not advance until the controller reports all of the following for that worker:

- `last_seen_at` is current.
- `apply_success=1`.
- `apply_error` is empty.
- `applied_config_hash` is populated.

Use the controller UI or query MySQL:

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D"$MYSQL_DATABASE" -e "
SELECT worker_id, last_seen_at, applied_config_hash, apply_success, apply_error, applied_at
FROM worker_status ORDER BY worker_id;"'
```

The worker's `last-known-good-config.json` volume lets a restarted upgraded worker restore its most recently successful configuration before the controller is reachable.

## 7. Move Inbounds And Validate Traffic

After a target worker is healthy, move only the intended inbounds to that worker in the UI. Wait for the target worker to apply the new configuration before moving the next inbound. Verify its host network has each requested listening port free before assignment.

For each moved inbound, confirm:

1. The old worker no longer reports that inbound after its next configuration pull.
2. The target worker reports `apply_success=1` with a new hash.
3. The target host is listening on the expected port.
4. A real proxy connection succeeds through the target worker.

Do not run two workers with overlapping `listen_ip:port` bindings on the same host. `network_mode: host` makes this an operating-system port conflict even though the database permits the same port on different workers.

## 8. Finish The Authentication Cutover

When every worker uses a unique token and is healthy, remove the temporary compatibility setting from `$CONTROL_ENV` or set it to `false`:

```dotenv
WORKER_AUTH_LEGACY_ENABLED=false
```

Redeploy the controller and verify unauthenticated worker requests return `401`, while token-authenticated workers remain online.

```bash
docker compose --env-file "$CONTROL_ENV" -f deploy/docker/docker-compose.control.yml up -d --build control-manager
curl --insecure --silent --output /dev/null --write-out '%{http_code}\n' \
  https://127.0.0.1:18443/api/config/aggregate
```

The final command must return `401`.

## 9. Rollback Boundary

Before V21/V22 are applied, rollback is an image redeploy. After they are applied, do not assume an old control-manager image can validate the newer Flyway history. Roll back application and database together from the backup/clone plan, and first restore inbound ownership to the previous topology if traffic has been moved.
