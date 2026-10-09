#!/usr/bin/env python3
"""Hold real external/internal fixture tunnels and check heartbeat concurrency. Keep resulting logs."""
import importlib.util
import json
import socket
import struct
import subprocess
import threading
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlencode
import sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('gateway-observability-stage-a-e2e.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)
api = stage.Api()
clients = []
added = []

def scope(category='ALL'):
    now = datetime.now(timezone.utc)
    return dict(workerId=stage.WORKER, **{'from': (now-timedelta(minutes=5)).isoformat(), 'to': now.isoformat()},
                timeZone='Asia/Shanghai', trafficCategory=category, username=stage.USERNAME)

def live(category='ALL'):
    return api.request('/api/observability/v1/overview/live?' + urlencode(scope(category)))

def count(category='ALL'):
    value = live(category)
    assert value['available'], value
    return int(value['count'])

def wait_count(category, expected):
    stage.wait('live ' + category + ' = ' + str(expected), lambda: count(category) == expected, timeout=15)


def connect(host):
    client = socket.create_connection(('127.0.0.1', stage.SOCKS), timeout=5)
    clients.append(client)
    client.sendall(b'\x05\x01\x02')
    assert stage.recv_exact(client, 2) == b'\x05\x02'
    user, password = stage.USERNAME.encode(), stage.PASSWORD.encode()
    client.sendall(b'\x01' + bytes([len(user)]) + user + bytes([len(password)]) + password)
    assert stage.recv_exact(client, 2) == b'\x01\x00'
    target = host.encode()
    client.sendall(b'\x05\x01\x00\x03' + bytes([len(target)]) + target + struct.pack('!H', stage.ORIGIN))
    response = stage.recv_exact(client, 4)
    assert response[:2] == b'\x05\x00', response
    stage.recv_exact(client, 6 if response[3] == 1 else 18)
    client.sendall(stage.PAYLOAD)
    assert stage.recv_exact(client, len(stage.PAYLOAD)) == stage.PAYLOAD
    return client

try:
    for address in [stage.PRIVATE_IP, stage.PUBLIC_IP]:
        addresses = json.loads(subprocess.check_output(['ip', '-j', 'addr', 'show', 'dev', 'lo']))
        if not any(item.get('local') == address for row in addresses for item in row['addr_info']):
            subprocess.run(['ip', 'addr', 'add', address+'/32', 'dev', 'lo'], check=True)
            added.append(address)
    with stage.Server(('0.0.0.0', stage.ORIGIN), stage.Echo) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        baseline = {c: count(c) for c in ['ALL', 'EXTERNAL', 'INTERNAL_REWRITE']}
        first = connect('public.telemetry.test')
        connect('public.telemetry.test')
        connect('private.telemetry.test')
        wait_count('ALL', baseline['ALL'] + 3)
        wait_count('EXTERNAL', baseline['EXTERNAL'] + 2)
        wait_count('INTERNAL_REWRITE', baseline['INTERNAL_REWRITE'] + 1)
        stage.socks(password='deliberately-invalid-fixture-password')
        wait_count('ALL', baseline['ALL'] + 3)
        first.close()
        wait_count('EXTERNAL', baseline['EXTERNAL'] + 1)
        for client in clients: client.close()
        wait_count('ALL', baseline['ALL'])
        stage.wait('ended logs reconstruct overlapping tunnels', lambda:
            int(api.request('/api/observability/v1/overview?' + urlencode(scope()))['concurrency']['peak']) >= 3, timeout=15)
    report = dict(realTunnels=True, external=2, internalRewrite=1, idleConnectionsCounted=True,
                  failedAuthenticationExcluded=True, closeDecrements=True, historicalPeakAtLeast3=True, logsRetained=True)
    Path('/tmp/gateway-observability-overview-live-e2e.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
finally:
    for client in clients: client.close()
    for address in added: subprocess.run(['ip', 'addr', 'del', address+'/32', 'dev', 'lo'], check=True)
