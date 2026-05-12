#!/usr/bin/env bash
set -euo pipefail

LSBLK_BIN="${LSBLK_BIN:-lsblk}"
SMARTCTL_BIN="${SMARTCTL_BIN:-smartctl}"
CURL_BIN="${CURL_BIN:-curl}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "missing required env: ${name}" >&2
    exit 2
  fi
}

require_command() {
  local command_name="$1"
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "missing required command: ${command_name}" >&2
    exit 2
  fi
}

require_env CONTROL_MANAGER_URL
require_env DISK_PUSH_TOKEN
require_env HOST_ID
require_command "${LSBLK_BIN}"
require_command "${SMARTCTL_BIN}"
require_command "${CURL_BIN}"
require_command "${PYTHON_BIN}"

HOST_NAME="${HOST_NAME:-$(hostname -f 2>/dev/null || hostname 2>/dev/null || printf '%s' "${HOST_ID}")}"
CONTROL_MANAGER_URL="${CONTROL_MANAGER_URL%/}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

MANIFEST="${TMP_DIR}/disks.tsv"
PAYLOAD="${TMP_DIR}/payload.json"
: > "${MANIFEST}"

while read -r name type _model; do
  if [[ "${type}" != "disk" ]]; then
    continue
  fi
  device="${name#/dev/}"
  output_file="${TMP_DIR}/${device}.smartctl"
  if "${SMARTCTL_BIN}" -a "/dev/${device}" > "${output_file}" 2>&1; then
    printf '%s\t%s\n' "${device}" "${output_file}" >> "${MANIFEST}"
  else
    echo "smartctl failed for /dev/${device}, skipping" >&2
  fi
done < <("${LSBLK_BIN}" -dn -o NAME,TYPE,MODEL)

if [[ ! -s "${MANIFEST}" ]]; then
  echo "no physical disks found or no smartctl output collected" >&2
  exit 0
fi

"${PYTHON_BIN}" - "${HOST_ID}" "${HOST_NAME}" "${MANIFEST}" "${PAYLOAD}" <<'PY'
import json
import sys
from datetime import datetime, timezone

host_id, host_name, manifest_path, payload_path = sys.argv[1:5]
disks = []

with open(manifest_path, encoding="utf-8") as manifest:
    for line in manifest:
        device, output_path = line.rstrip("\n").split("\t", 1)
        with open(output_path, encoding="utf-8", errors="replace") as output:
            disks.append({
                "device": device,
                "smartctlOutput": output.read(),
            })

payload = {
    "hostId": host_id,
    "hostName": host_name,
    "sampledAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "disks": disks,
}

with open(payload_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False)
PY

"${CURL_BIN}" -fsS \
  -X POST "${CONTROL_MANAGER_URL}/api/disk/push" \
  -H "Content-Type: application/json" \
  -H "X-Disk-Push-Token: ${DISK_PUSH_TOKEN}" \
  --data-binary "@${PAYLOAD}"
