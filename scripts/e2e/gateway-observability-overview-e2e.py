#!/usr/bin/env python3
"""Read-only comparison of overview aggregates with retained facts in the telemetry SQLite database."""
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
    return "'" + value.replace("'", "''") + "'"

def micros(time):
    return int(time.timestamp()) * 1_000_000 + time.microsecond

def db(sql):
    container = os.environ.get('E2E_CONTROL_CONTAINER', 'home-proxy-control-manager')
    result = subprocess.run(['docker', 'exec', container, 'sqlite3', '-readonly', '-json', '/data/telemetry.db', sql],
        capture_output=True, text=True, check=True)
    return json.loads(result.stdout or '[]')

where = f"worker_id = {quote(worker)} AND occurred_at >= {micros(start)} AND occurred_at < {micros(to)}"
def overview(**extra):
    return api.request('/api/observability/v1/overview?' + urlencode(dict(scope, **extra)))

report = dict(readOnly=True, workerId=worker, scope=scope, categories={})
all_data = overview()
category_bytes = 0
for category in ['EXTERNAL', 'INTERNAL_REWRITE', 'OTHER_INTERNAL', 'UNKNOWN']:
    data = overview(trafficCategory=category)
    truth = db(f"SELECT CAST(SUM(upload_bytes) AS TEXT) AS upload, CAST(SUM(download_bytes) AS TEXT) AS download FROM proxy_session_log_v1 WHERE {where} AND coalesce(traffic_category,'UNKNOWN')={quote(category)}")[0]
    assert data['traffic']['upload'] == (truth['upload'] or '0') and data['traffic']['download'] == (truth['download'] or '0'), category
    category_bytes += int(data['traffic']['total'])
    report['categories'][category] = data['traffic']['total']
assert category_bytes == int(all_data['traffic']['total'])
assert sum(int(p.get('upload', '0')) + int(p.get('download', '0')) for p in all_data['traffic']['series']) == category_bytes
# Success is a completed DNS response, including protocol-negative answers; failed sends count as failures.
dns_status = ("CASE WHEN outcome='BLOCKED' THEN 'BLOCKED' WHEN outcome='CANCELLED' THEN 'CANCELLED' "
              "WHEN outcome='ERROR' OR (query_origin='CLIENT' AND response_sent=0) THEN 'FAILURE' "
              "WHEN outcome='ANSWERED' AND (query_origin='PROXY_RESOLVE' OR response_sent=1) THEN 'SUCCESS' ELSE 'UNKNOWN' END")
auth_status = ("CASE WHEN auth_outcome='SUCCESS' THEN 'SUCCESS' WHEN auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR') THEN 'FAILURE' "
               "WHEN auth_outcome='CHALLENGE' THEN 'CHALLENGE' WHEN auth_outcome='CANCELLED' THEN 'CANCELLED' ELSE 'UNKNOWN' END")
for kind, table, status in [('dns', 'dns_query_log_v1', dns_status), ('auth', 'credential_auth_log_v1', auth_status)]:
    origin = " AND query_origin='CLIENT'" if kind == 'dns' else ''
    expected = {r['result']: str(r['count']) for r in db(f"SELECT {status} AS result, COUNT(*) AS count FROM {table} WHERE {where}{origin} GROUP BY result")}
    assert all_data[kind]['totals'] == expected, (kind, expected, all_data[kind]['totals'])
    if kind == 'dns':
        selected = overview(dnsResult='FAILURE')
        assert selected[kind]['total'] == expected.get('FAILURE', '0')
    report[kind] = expected
# Other-internal and unknown remain visible; changing business category must not alter DNS/auth counts.
external = overview(trafficCategory='EXTERNAL')
assert external['dns'] == all_data['dns'] and external['auth'] == all_data['auth']
# Failed credentials are searchable without a verified identity.
name = worker + '-telemetry-a'
account = overview(username=name, authResult='FAILURE')
expected = str(db(f"SELECT COUNT(*) AS count FROM credential_auth_log_v1 WHERE {where} AND ({auth_status})='FAILURE' AND (username={quote(name)} OR attempted_username={quote(name)})")[0]['count'])
assert account['auth']['total'] == expected
report['failedAccountCount'] = expected
report['concurrency'] = {k: all_data['concurrency'][k] for k in ['average', 'peak']}
report['live'] = all_data['live']
Path('/tmp/gateway-observability-overview-e2e.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
