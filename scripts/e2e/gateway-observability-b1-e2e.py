#!/usr/bin/env python3
"""Read-only B1 audit API checks against retained local telemetry facts."""
import importlib.util
import json
import os
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.parse import urlencode
from urllib.error import HTTPError
import uuid
import sys
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('fake-ip-persistent-e2e.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
api = fixture.Api()
worker = os.environ.get('E2E_WORKER_ID', 'home-01')
now = datetime.now(timezone.utc)
scope = {'workerId': worker, 'from': (now - timedelta(days=1)).isoformat(), 'to': now.isoformat()}
report = {'workerId': worker, 'scope': scope, 'types': {}, 'readOnly': True}


def page(kind, **filters):
    return api.request('/api/observability/v1/' + kind + '/page?' + urlencode(dict(scope, **filters)))


def expect_status(path, expected):
    try:
        api.request(path)
    except HTTPError as error:
        assert error.code == expected, (path, error.code, expected)
    else:
        raise AssertionError('Expected HTTP ' + str(expected))


for kind in ['sessions', 'dns', 'events', 'credential-auth']:
    first = page(kind, limit=2)
    assert first['items'], 'Retained sample required for ' + kind
    assert isinstance(first['total'], str) and int(first['total']) >= len(first['items'])
    assert first['timeBoundary'] == '[from,to)' and first['maxOffset'] == 10000
    row = first['items'][0]
    detail = api.request('/api/observability/v1/' + kind + '/' + row['recordId'] + '?' + urlencode(scope))
    assert detail == row, (kind, 'stable ID detail mismatch')
    if len(first['items']) == 2:
        assert page(kind, limit=1)['items'][0]['recordId'] == row['recordId']
        assert page(kind, limit=1, offset=1)['items'][0]['recordId'] == first['items'][1]['recordId']
    filters = {}
    for field, parameter in [('clientIp', 'clientIp'), ('inboundProtocol', 'protocol')]:
        if row.get(field):
            filters[parameter] = row[field]
    if kind == 'sessions':
        for field, parameter in [('sessionOutcome', 'outcome'), ('connectOutcome', 'connectOutcome'), ('trafficCategory', 'trafficCategory'), ('routeId', 'route'), ('effectiveTargetHost', 'target'), ('username', 'username')]:
            if row.get(field):
                filters[parameter] = row[field]
        assert isinstance(row['uploadBytes'], str) and isinstance(row['downloadBytes'], str)
        related = {k: page(k, connectionId=row['connectionId'], limit=200) for k in ['dns', 'events', 'credential-auth']}
        for related_page in related.values():
            assert all(r.get('connectionId') == row['connectionId'] or r.get('parentConnectionId') == row['connectionId'] for r in related_page['items'])
        report['association'] = {k: v['total'] for k, v in related.items()}
    else:
        for field, parameter in [('qname', 'target'), ('qtype', 'qtype'), ('eventType', 'eventType'), ('authMethod', 'authMethod'), ('authOutcome', 'outcome'), ('outcome', 'outcome'), ('attemptedUsername', 'username')]:
            if row.get(field):
                filters[parameter] = row[field]
    filtered = page(kind, limit=200, **filters)
    assert row['recordId'] in [r['recordId'] for r in filtered['items']], (kind, filters)
    assert int(filtered['total']) <= int(first['total'])
    assert page(kind, username='no-such-user-' + str(uuid.uuid4()))['total'] == '0'
    # Inclusive start / exclusive end at an actual microsecond-resolution timestamp.
    assert page(kind, **{'from': row['occurredAt']}, limit=1)['items'][0]['occurredAt'] == row['occurredAt']
    assert all(r['recordId'] != row['recordId'] for r in page(kind, **{'to': row['occurredAt']}, limit=200)['items'])
    expect_status('/api/observability/v1/' + kind + '/' + row['recordId'] + '?' + urlencode(dict(scope, workerId='no-such-worker')), 404)
    report['types'][kind] = {'total': first['total'], 'filteredTotal': filtered['total'], 'recordId': row['recordId'], 'filters': filters}

for outcome in ['FAILURE', 'CHALLENGE', 'DENIED', 'MALFORMED']:
    auth = page('credential-auth', outcome=outcome, limit=200)
    assert auth['items'], 'Retained auth sample required for ' + outcome
    assert all(row['authOutcome'] == outcome for row in auth['items'])
    assert all('password' not in row and 'authorization' not in row for row in auth['items'])
    report.setdefault('authResults', {})[outcome] = auth['total']

# Historical failed credentials remain unverified; attempts are associated at query time.
failed_sessions = [row for row in page('sessions', outcome='ERROR_END', limit=200)['items'] if row.get('errorCode') == 'INVALID_CREDENTIALS']
assert failed_sessions, 'Retained credential failure connection required'
for row in failed_sessions:
    assert row.get('username') is None and row.get('userId') is None
    attempts = row['credentialAuthAttempts']
    assert any(a.get('attemptedUsername') is not None and a['authOutcome'] == 'FAILURE' for a in attempts)
    name = next(a['attemptedUsername'] for a in attempts if a.get('attemptedUsername') is not None)
    assert row['recordId'] in [r['recordId'] for r in page('sessions', username=name, outcome='ERROR_END', limit=200)['items']]
    # Attempts immediately preceding the summary are still visible with a narrow list window.
    narrow = page('sessions', connectionId=row['connectionId'], **{'from': row['occurredAt']})['items']
    assert narrow and narrow[0]['credentialAuthAttempts'] == attempts
    assert all(set(a) == {'authAttemptId', 'attemptedUsername', 'authOutcome', 'authMethod', 'occurredAt'} for a in attempts)
report['unverifiedAttemptedAccounts'] = {'failedConnections': len(failed_sessions), 'historicalFactsUnchanged': True, 'searchIncludesFailures': True}

# The protocol fixture is synthetic, explicitly kept apart from actual Worker facts.
probe = Path('/tmp/gateway-observability-protocol-probe.json')
if probe.exists():
    sample = json.loads(probe.read_text())
    detail = api.request('/api/observability/v1/sessions/' + sample['recordId'] + '?' + urlencode(dict(scope, workerId=sample['workerId'])))
    assert detail['uploadBytes'] == '9007199254740993'
    report['syntheticLargeInteger'] = detail['uploadBytes']

for filters in [{'limit': 201}, {'offset': 10001}, {'from': (now - timedelta(days=32)).isoformat()}, {'from': scope['to']}, {'workerId': ''}, {'qtype': 'A'}]:
    expect_status('/api/observability/v1/sessions/page?' + urlencode(dict(scope, **filters)), 400)
expect_status('/api/observability/v1/not-a-type/page?' + urlencode(scope), 400)
expect_status('/api/observability/v1/sessions/not-a-uuid?' + urlencode(scope), 400)
legacy = api.request('/api/observability/v1/sessions?' + urlencode(dict(scope, limit=2)))
assert isinstance(legacy, list) and len(legacy) == 2
assert api.request('/api/logs/access?page=1&pageSize=1') is not None
report['status'] = api.request('/api/observability/v1/status?' + urlencode({'workerId': worker}))
Path('/tmp/gateway-observability-b1-e2e.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
