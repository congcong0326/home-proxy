#!/usr/bin/env python3
"""Read-only comparison of overview aggregates with retained ClickHouse facts."""
import importlib.util
import json
import os
import subprocess
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlencode
import sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('fake-ip-persistent-e2e.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
api = fixture.Api()
worker = os.environ.get('E2E_WORKER_ID', 'home-01')
to = datetime.now(timezone.utc) - timedelta(minutes=2)
start = to - timedelta(days=1)
scope = dict(workerId=worker, **{'from': start.isoformat(), 'to': to.isoformat()}, timeZone='Asia/Shanghai', trafficCategory='ALL')

def quote(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"

def ck(sql):
    result = subprocess.run(['docker', 'exec', 'home-proxy-clickhouse', 'sh', '-c',
        'exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "$1"', 'sh', sql + ' FORMAT JSONEachRow'],
        capture_output=True, text=True, check=True)
    return [json.loads(line) for line in result.stdout.splitlines() if line]

where = f"worker_id = {quote(worker)} AND occurred_at >= toDateTime64({quote(start.strftime('%Y-%m-%d %H:%M:%S.%f'))},6,'UTC') AND occurred_at < toDateTime64({quote(to.strftime('%Y-%m-%d %H:%M:%S.%f'))},6,'UTC')"
def overview(**extra):
    return api.request('/api/observability/v1/overview?' + urlencode(dict(scope, **extra)))

report = dict(readOnly=True, workerId=worker, scope=scope, categories={})
all_data = overview()
category_bytes = 0
for category in ['EXTERNAL', 'INTERNAL_REWRITE', 'OTHER_INTERNAL', 'UNKNOWN']:
    data = overview(trafficCategory=category)
    truth = ck(f"SELECT toString(sum(upload_bytes)) AS upload, toString(sum(download_bytes)) AS download FROM telemetry.proxy_session_log_v1 FINAL WHERE {where} AND coalesce(traffic_category,'UNKNOWN')={quote(category)}")[0]
    assert data['traffic']['upload'] == (truth['upload'] or '0') and data['traffic']['download'] == (truth['download'] or '0'), category
    category_bytes += int(data['traffic']['total'])
    report['categories'][category] = data['traffic']['total']
assert category_bytes == int(all_data['traffic']['total'])
assert sum(int(p.get('upload', '0')) + int(p.get('download', '0')) for p in all_data['traffic']['series']) == category_bytes
# Success is a completed DNS response, including protocol-negative answers; failed sends count as failures.
dns_status = "multiIf(outcome='BLOCKED','BLOCKED',outcome='CANCELLED','CANCELLED',outcome='ERROR' OR (query_origin='CLIENT' AND response_sent=false),'FAILURE',outcome='ANSWERED' AND (query_origin='PROXY_RESOLVE' OR response_sent=true),'SUCCESS','UNKNOWN')"
auth_status = "multiIf(auth_outcome='SUCCESS','SUCCESS',auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR'),'FAILURE',auth_outcome='CHALLENGE','CHALLENGE',auth_outcome='CANCELLED','CANCELLED','UNKNOWN')"
for kind, table, status in [('dns', 'dns_query_log_v1', dns_status), ('auth', 'credential_auth_log_v1', auth_status)]:
    origin = " AND query_origin='CLIENT'" if kind == 'dns' else ''
    expected = {r['result']: str(r['count']) for r in ck(f"SELECT {status} AS result, toString(count()) AS count FROM telemetry.{table} FINAL WHERE {where}{origin} GROUP BY result")}
    assert all_data[kind]['totals'] == expected, (kind, expected, all_data[kind]['totals'])
    selected = overview(**{kind + 'Result': 'FAILURE'})
    assert selected[kind]['total'] == expected.get('FAILURE', '0')
    report[kind] = expected
# Other-internal and unknown remain visible; changing business category must not alter DNS/auth counts.
external = overview(trafficCategory='EXTERNAL')
assert external['dns'] == all_data['dns'] and external['auth'] == all_data['auth']
# Failed credentials are searchable without a verified identity.
name = worker + '-telemetry-a'
account = overview(username=name, authResult='FAILURE')
expected = ck(f"SELECT toString(count()) AS count FROM telemetry.credential_auth_log_v1 FINAL WHERE {where} AND ({auth_status})='FAILURE' AND (username={quote(name)} OR attempted_username={quote(name)} OR verified_username={quote(name)})")[0]['count']
assert account['auth']['total'] == expected
report['failedAccountCount'] = expected
report['concurrency'] = {k: all_data['concurrency'][k] for k in ['average', 'peak']}
report['live'] = all_data['live']
Path('/tmp/gateway-observability-overview-e2e.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
