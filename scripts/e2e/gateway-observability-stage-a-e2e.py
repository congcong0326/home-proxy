#!/usr/bin/env python3
"""Stage A real proxy -> bounded memory buffer -> negotiated WSS -> ClickHouse/API E2E.

The script NEVER deletes/truncates log tables or removes Docker volumes. Fixtures
and resulting telemetry rows are kept for manual inspection. Only local sockets
and addresses introduced by this invocation are released.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import socketserver
import struct
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone, timedelta

spec = importlib.util.spec_from_file_location('persistent_fixture', Path(__file__).with_name('fake-ip-persistent-e2e.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
Api = fixture.Api
WORKER = os.environ.get('E2E_WORKER_ID', 'home-01')
PREFIX = WORKER + '-telemetry-a'
SOCKS, HTTP, DNS, ORIGIN = 29480, 29481, 29453, 29490
DNS_ADDRESS = '127.0.0.1'
PRIVATE_IP, PUBLIC_IP = '10.254.94.1', '93.184.216.250'
USERNAME, PASSWORD = PREFIX, 'telemetry-a-fixture-pass'
PAYLOAD = b'telemetry-stage-a-controlled-payload-0123456789\n'


def iso(value):
    return value.isoformat().replace('+00:00', 'Z')


def wait(label, fn, timeout=180):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = fn()
            if value:
                return value
        except Exception as error:
            last = error
        time.sleep(.5)
    raise AssertionError(f'{label}: timed out; last error={last}')


def provision(api):
    users = api.request('/api/users?pageSize=200')['items']
    user = next((row for row in users if row['username'] == USERNAME), None)
    payload = {'username': USERNAME, 'credential': PASSWORD, 'status': 1, 'remark': 'persistent telemetry stage A E2E'}
    user = api.request('/api/users' + ('/' + str(user['id']) if user else ''), payload, 'PUT' if user else 'POST')
    routes = []
    for suffix, target in [('private', PRIVATE_IP), ('public', PUBLIC_IP), ('unknown', '127.0.0.1')]:
        routes.append(fixture.upsert(api, '/api/routes', PREFIX + '-' + suffix, {
            'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': suffix + '.telemetry.test'}],
            'policy': 'DESTINATION_OVERRIDE', 'outboundProxyHost': target, 'outboundProxyPort': ORIGIN,
            'status': 1, 'notes': 'stage A controlled origin'}))
    blocked = fixture.upsert(api, '/api/routes', PREFIX + '-block', {
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': '*'}], 'policy': 'BLOCK', 'status': 1})
    dns_route = fixture.upsert(api, '/api/routes', PREFIX + '-dns', {
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': '*'}],
        'policy': 'OUTBOUND_PROXY', 'outboundProxyType': 'DNS_SERVER',
        'outboundProxyHost': '127.0.0.1', 'outboundProxyPort': 15354, 'status': 1})
    existing_dns = [row for row in api.request('/api/inbounds?pageSize=200')['items'] if row['workerId'] == WORKER and row['protocol'] == 'DNS_SERVER' and row['status'] == 1]
    protocols = [('SOCKS5', SOCKS), ('HTTPS_CONNECT', HTTP)]
    if not existing_dns:
        protocols.append(('DNS_SERVER', DNS))
    for protocol, port in protocols:
        fixture.upsert(api, '/api/inbounds', PREFIX + '-' + protocol.lower(), {
            'workerId': WORKER, 'name': PREFIX + '-' + protocol.lower(), 'protocol': protocol,
            'listenIp': '127.0.0.1', 'port': port, 'fallbackRouteId': dns_route['id'] if protocol == 'DNS_SERVER' else blocked['id'],
            'anonymousUserId': user['id'], 'tlsEnabled': False, 'sniffEnabled': False,
            'fakeIpEnabled': protocol == 'DNS_SERVER',
            'inboundRouteBindings': [] if protocol == 'DNS_SERVER' else [{'userIds': [user['id']], 'routeIds': [r['id'] for r in routes]}],
            'status': 1, 'notes': 'persistent telemetry stage A E2E'})
    expected = api.request(f'/api/workers/{WORKER}/effective-config')['configHash']
    wait('worker applied fixture', lambda: (lambda status: status.get('applySuccess') and status.get('appliedConfigHash') == expected)(api.request(f'/api/workers/{WORKER}/status')))


def recv_exact(client, length):
    data = b''
    while len(data) < length:
        chunk = client.recv(length - len(data))
        if not chunk:
            raise AssertionError('unexpected EOF')
        data += chunk
    return data


def socks(host=None, password=PASSWORD, methods=b'\x02'):
    with socket.create_connection(('127.0.0.1', SOCKS), timeout=5) as client:
        client.sendall(b'\x05' + bytes([len(methods)]) + methods)
        response = recv_exact(client, 2)
        if methods == b'\x00':
            assert response == b'\x05\xff'
            return
        assert response == b'\x05\x02', response
        username = USERNAME.encode()
        secret = password.encode()
        client.sendall(b'\x01' + bytes([len(username)]) + username + bytes([len(secret)]) + secret)
        auth = recv_exact(client, 2)
        assert auth[0] == 1 and (auth[1] == 0 if password == PASSWORD else auth[1] != 0), auth
        if password != PASSWORD or host is None:
            return
        encoded = host.encode()
        client.sendall(b'\x05\x01\x00\x03' + bytes([len(encoded)]) + encoded + struct.pack('!H', ORIGIN))
        response = recv_exact(client, 4)
        if host.startswith('blocked'):
            assert response[1] != 0
            return
        assert response[1] == 0, response
        if response[3] == 1:
            recv_exact(client, 6)
        elif response[3] == 4:
            recv_exact(client, 18)
        else:
            recv_exact(client, recv_exact(client, 1)[0] + 2)
        client.sendall(PAYLOAD)
        assert recv_exact(client, len(PAYLOAD)) == PAYLOAD


def http_auth(header, expected):
    with socket.create_connection(('127.0.0.1', HTTP), timeout=5) as client:
        request = 'CONNECT private.telemetry.test:29490 HTTP/1.1\r\nHost: private.telemetry.test\r\n'
        if header is not None:
            request += 'Proxy-Authorization: ' + header + '\r\n'
        client.sendall((request + '\r\n').encode())
        response = client.recv(4096)
        assert response.startswith(('HTTP/1.1 ' + str(expected)).encode()), response[:100]


def dns_query(qtype):
    question = b''.join(bytes([len(part)]) + part.encode() for part in 'stage-a.telemetry.test'.split('.')) + b'\x00' + struct.pack('!HH', qtype, 1)
    transaction = uuid.uuid4().int & 65535
    packet = struct.pack('!HHHHHH', transaction, 0x0100, 1, 0, 0, 0) + question
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        client.settimeout(5)
        client.sendto(packet, (DNS_ADDRESS, DNS))
        response = client.recv(4096)
        identity, flags, _, answers, _, _ = struct.unpack('!HHHHHH', response[:12])
        assert identity == transaction and flags & 15 == 0
        assert answers == (1 if qtype == 1 else 0)


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        while True:
            data = self.request.recv(65536)
            if not data:
                return
            self.request.sendall(data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def run(api, legacy_only=False):
    global DNS, DNS_ADDRESS
    inbound = next(row for row in api.request('/api/inbounds?pageSize=200')['items'] if row['workerId'] == WORKER and row['protocol'] == 'DNS_SERVER' and row['status'] == 1)
    assert inbound['fakeIpEnabled'], 'Stage A DNS probes require an enabled Fake IP DNS inbound'
    DNS, DNS_ADDRESS = inbound['port'], inbound['listenIp']
    if DNS_ADDRESS == '0.0.0.0':
        DNS_ADDRESS = '127.0.0.1'
    from_time = datetime.now(timezone.utc) - timedelta(seconds=1)
    added = []
    try:
        for address in [PRIVATE_IP, PUBLIC_IP]:
            result = subprocess.run(['ip', '-j', 'addr', 'show', 'dev', 'lo'], capture_output=True, text=True, check=True)
            if not any(item.get('local') == address for row in json.loads(result.stdout) for item in row['addr_info']):
                subprocess.run(['ip', 'addr', 'add', address + '/32', 'dev', 'lo'], check=True)
                added.append(address)
        with Server(('0.0.0.0', ORIGIN), Echo) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            for host in ['private', 'public', 'unknown']:
                socks(host + '.telemetry.test')
            socks('blocked.telemetry.test')
            socks(None)  # verified credentials, then client closes without a tunnel
            socks(password='deliberately-invalid-fixture-password')
            socks(methods=b'\x00')
            http_auth(None, 407)
            http_auth('Basic !!invalid!!', 400)
            import base64
            http_auth('Basic ' + base64.b64encode((USERNAME + ':wrong').encode()).decode(), 407)
            http_auth('Basic ' + base64.b64encode((USERNAME + ':' + PASSWORD).encode()).decode(), 200)
            dns_query(1)
            dns_query(28)
            server.shutdown()
        if legacy_only:
            print('PASS: legacy-compatible forwarding and authentication probes')
            return
        status = api.request('/api/observability/v1/status?workerId=' + WORKER)
        assert status['delivery'] == 'BEST_EFFORT_WITH_ACK', status
        assert status['worker']['bufferMode'] == 'MEMORY', status
        assert status['worker']['restartRecoverySupported'] is False, status
        query = {'workerId': WORKER, 'from': iso(from_time), 'to': iso(datetime.now(timezone.utc) + timedelta(minutes=2)), 'limit': 200}
        import urllib.parse
        def rows(kind):
            return [row for row in api.request('/api/observability/v1/' + kind + '?' + urllib.parse.urlencode(query)) if (row.get('inboundName') or '').startswith(PREFIX) or (kind == 'dns' and row.get('qname', '').rstrip('.') == 'stage-a.telemetry.test')]
        sessions = wait('all connection summaries persisted', lambda: (lambda data: data if len(data) >= 11 else None)(rows('sessions')))
        auth = wait('all credential attempts persisted', lambda: (lambda data: data if len(data) >= 11 else None)(rows('credential-auth')))
        dns = wait('A/AAAA query facts persisted', lambda: (lambda data: data if len(data) >= 2 else None)(rows('dns')))
        events = wait('connect events persisted', lambda: rows('events'))
        assert len({row['recordId'] for row in sessions}) == len(sessions)
        for host, category in [('private', 'INTERNAL_REWRITE'), ('public', 'EXTERNAL'), ('unknown', 'UNKNOWN')]:
            matched = [row for row in sessions if row.get('logicalTargetHost') == host + '.telemetry.test' and row['inboundProtocol'] == 'SOCKS5']
            assert len(matched) == 1, matched
            row = matched[0]
            assert row['trafficCategory'] == category, row
            assert row['destinationOverrideApplied'] is True
            assert row['connectOutcome'] == 'CONNECTED' and row['sessionOutcome'] == 'NORMAL_END', row
            assert row['uploadBytes'] == str(len(PAYLOAD)) and row['downloadBytes'] == str(len(PAYLOAD)), row
            assert row['dnsDurationUs'] is None
            assert row['recordId'] == row['connectionId']
        assert any(row['connectOutcome'] == 'BLOCKED' and row['uploadBytes'] == '0' for row in sessions)
        outcomes = {row['authOutcome'] for row in auth}
        assert {'SUCCESS', 'FAILURE', 'DENIED', 'CHALLENGE', 'MALFORMED'} <= outcomes, outcomes
        for row in auth:
            assert row['workerId'] == WORKER
            assert row['analysis']['geoLookupStatus'] == 'PRIVATE', row
            assert row['analysis']['sourceCountry'] is None
            if row['authOutcome'] != 'SUCCESS':
                assert row['verifiedUsername'] is None and row['userId'] is None
            assert PASSWORD not in json.dumps(row) and 'deliberately-invalid' not in json.dumps(row)
        no_tunnel = [row for row in auth if row['authOutcome'] == 'SUCCESS' and not next(s for s in sessions if s['connectionId'] == row['connectionId'])['logicalTargetHost']]
        assert no_tunnel, 'successful credential check without a target must be retained'
        assert any(row['qtype'] == 'A' and row['answerMode'] == 'FAKE_IP' and row['answerIps'] for row in dns)
        assert any(row['qtype'] == 'AAAA' and row['emptyAnswerReason'] == 'IPV6_SUPPRESSED' and row['rcode'] == 0 for row in dns)
        assert all(row['responseSent'] is True for row in dns)
        assert all(row['inboundProtocol'] == 'DNS_SERVER' and row['transport'] == 'UDP' for row in dns)
        assert any(row['eventType'] == 'CONNECT_RESULT' and row['outcome'] == 'CONNECTED' for row in events)
        for event in events:
            session = next(row for row in sessions if row['connectionId'] == event['connectionId'])
            for field in ['inboundId', 'inboundName', 'inboundProtocol', 'clientIp', 'clientPort',
                          'clientIpSource', 'transportPeerIp', 'transportPeerPort', 'username', 'userId', 'identitySource']:
                assert event.get(field) == session.get(field), ('event context mismatch', field, event['recordId'])
        report = {'workerId': WORKER, 'from': iso(from_time), 'to': query['to'], 'counts': {kind: len(data) for kind, data in [('sessions', sessions), ('credential-auth', auth), ('dns', dns), ('events', events)]},
                  'controlledPayloadBytesPerDirection': len(PAYLOAD), 'tablesPreserved': True, 'bufferMode': 'MEMORY', 'restartRecoverySupported': False,
                  'dnsProtocolCollected': True, 'eventContextCollected': True,
                  'sessionSamples': [{k: row.get(k) for k in ['connectionId', 'logicalTargetHost', 'connectOutcome', 'sessionOutcome', 'trafficCategory', 'selectedTargetIp', 'uploadBytes', 'downloadBytes']} for row in sessions if row.get('logicalTargetHost')]}
        path = Path(os.environ.get('E2E_REPORT', '/tmp/gateway-observability-stage-a-e2e.json'))
        path.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
        print('PASS: new facts validated through independent API; data retained. Report:', path)
    finally:
        for address in added:
            subprocess.run(['ip', 'addr', 'del', address + '/32', 'dev', 'lo'], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--provision', action='store_true')
    parser.add_argument('--legacy-only', action='store_true')
    args = parser.parse_args()
    api = wait('control API', Api)
    if args.provision:
        provision(api)
    run(api, args.legacy_only)


if __name__ == '__main__':
    main()
