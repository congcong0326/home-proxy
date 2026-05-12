#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

cat > "${TMP_DIR}/lsblk" <<'FAKE_LSBLK'
#!/usr/bin/env bash
cat <<'OUT'
sda disk Samsung_SSD
sda1 part
nvme0n1 disk WD_Black
loop0 loop loop
OUT
FAKE_LSBLK

cat > "${TMP_DIR}/smartctl" <<'FAKE_SMARTCTL'
#!/usr/bin/env bash
device="$2"
cat <<OUT
smartctl fake
Device Model:     ${device##*/} model
Serial Number:    ${device##*/}-serial
SMART overall-health self-assessment test result: PASSED
OUT
FAKE_SMARTCTL

cat > "${TMP_DIR}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "${CURL_ARGS_OUTPUT}"
while (($#)); do
  case "$1" in
    --data-binary)
      shift
      payload="${1#@}"
      cat "${payload}" > "${CURL_PAYLOAD_OUTPUT}"
      ;;
  esac
  shift || true
done
FAKE_CURL

chmod +x "${TMP_DIR}/lsblk" "${TMP_DIR}/smartctl" "${TMP_DIR}/curl"

env \
  -u CONTROL_MANAGER_URL \
  -u DISK_PUSH_TOKEN \
  -u HOST_ID \
  -u HOST_NAME \
  PATH="${TMP_DIR}:${PATH}" \
  CURL_ARGS_OUTPUT="${TMP_DIR}/curl.args" \
  CURL_PAYLOAD_OUTPUT="${TMP_DIR}/payload.json" \
  bash "${SCRIPT_DIR}/push-smartctl.sh"

grep -q 'http://127.0.0.1:18081/api/disk/push' "${TMP_DIR}/curl.args"
grep -q 'X-Disk-Push-Token: replace-with-the-token-visible-on-disk-monitor-page' "${TMP_DIR}/curl.args"

python3 - "${TMP_DIR}/payload.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    payload = json.load(fh)

assert payload["hostId"] == "nas-main"
assert payload["hostName"] == "NAS Main"
assert len(payload["disks"]) == 2
assert [disk["device"] for disk in payload["disks"]] == ["sda", "nvme0n1"]
assert "Device Model:" in payload["disks"][0]["smartctlOutput"]
PY
