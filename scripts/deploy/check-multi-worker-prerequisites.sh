#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 CONTROL_ENV WORKER_ENV" >&2
  exit 2
fi

control_env=$1
worker_env=$2
root=$(cd "$(dirname "$0")/../.." && pwd)
tls_dir="$root/data/tls"

for file in "$tls_dir/control-manager.p12" "$tls_dir/control-manager.crt" "$tls_dir/control-manager-keystore.password"; do
  test -r "$file" || { echo "missing or unreadable: $file" >&2; exit 1; }
done

openssl x509 -in "$tls_dir/control-manager.crt" -noout -subject -dates -ext subjectAltName -fingerprint -sha256
docker compose --env-file "$control_env" -f "$root/deploy/docker/docker-compose.control.yml" config --quiet
docker compose --env-file "$worker_env" -f "$root/deploy/docker/docker-compose.worker.yml" config --quiet

worker_id=$(sed -n 's/^WORKER_ID=//p' "$worker_env" | tail -n 1)
test -n "$worker_id" || { echo "WORKER_ID is required in worker env" >&2; exit 1; }
getent hosts proxy-manager.internal >/dev/null || { echo "proxy-manager.internal does not resolve" >&2; exit 1; }
echo "multi-worker prerequisites verified for $worker_id"
