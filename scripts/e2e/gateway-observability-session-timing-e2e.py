#!/usr/bin/env python3
"""Retain real home-01 timing facts for review; use only explicitly named local fixtures.

Never clears tables/volumes. Temporary inbounds are disabled after collection;
route/user metadata and all facts remain. Xray private keys and TLS keys stay in
an automatically removed temporary directory and never enter the report.
"""
import base64
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import socketserver
import ssl
import struct
import subprocess
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone, timedelta
from urllib.parse import urlencode

spec = importlib.util.spec_from_file_location('stage_a', Path(__file__).with_name('gateway-observability-stage-a-e2e.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
WORKER = os.environ.get('E2E_WORKER_ID', 'home-01')
PREFIX = WORKER + '-timing-e2e'
SOCKS, SNI, ORIGIN, TLS_ORIGIN = 29680, 29682, 29690, 29691
UPSTREAMS = {'http': 29684, 'socks': 29685, 'ss': 29686, 'reality': 29687, 'reject': 29688}
PAYLOAD = b'timing-e2e-controlled-payload\n'
BULK_SIZE = 16 * 1024 * 1024
TIMINGS = ['sessionDurationUs', 'channelSetupDurationUs', 'clientHandshakeDurationUs', 'targetRecoveryDurationUs',
           'routeSelectionDurationUs', 'dnsDurationUs', 'outboundTcpDurationUs', 'outboundTlsDurationUs',
           'proxyHandshakeDurationUs', 'uploadBackpressureDurationUs', 'downloadBackpressureDurationUs']


class LocalServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Origin(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(15)
        try:
            first = self.request.recv(65536)
            if first.startswith(b'DOWNLOAD\n'):
                self.request.sendall(b'd' * BULK_SIZE)
            elif first.startswith(b'UPLOAD\n'):
                # Deliberately pause the target's receive side to exercise upload backpressure.
                time.sleep(1)
                count = len(first) - len(b'UPLOAD\n')
                while count < BULK_SIZE:
                    part = self.request.recv(min(65536, BULK_SIZE - count))
                    if not part: return
                    count += len(part)
                self.request.sendall(str(count).encode() + b'\n')
            else:
                self.request.sendall(first)
                while True:
                    part = self.request.recv(65536)
                    if not part: return
                    self.request.sendall(part)
        except (OSError, ssl.SSLError):
            return


class TlsOrigin(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with self.server.ssl_context.wrap_socket(self.request, server_side=True) as client:
                client.settimeout(15)
                packet = b''
                while b'\r\n\r\n' not in packet:
                    part = client.recv(65536)
                    if not part: return
                    packet += part
                body = b'TIMING_HTTPS_OK\n'
                client.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: ' + str(len(body)).encode() + b'\r\nConnection: close\r\n\r\n' + body)
        except (OSError, ssl.SSLError):
            return


class Reject(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        packet = b''
        while b'\r\n\r\n' not in packet:
            part = self.request.recv(4096)
            if not part: return
            packet += part
        self.request.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n')


def start_server(stack, port, handler, tls=None):
    server = stack.enter_context(LocalServer(('127.0.0.1', port), handler))
    if tls: server.ssl_context = tls
    threading.Thread(target=server.serve_forever, daemon=True).start()
    stack.callback(server.shutdown)
    return server


def new_material(directory):
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat, NoEncryption
    private = X25519PrivateKey.generate()
    encoded_private = base64.urlsafe_b64encode(private.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())).decode().rstrip('=')
    public = base64.urlsafe_b64encode(private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode().rstrip('=')
    key, cert = str(Path(directory) / 'tls.key'), str(Path(directory) / 'tls.crt')
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert,
                    '-days', '1', '-subj', '/CN=localhost'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(cert, key)
    return encoded_private, public, context


def start_xray(stack, directory, private, identity, short_id, password):
    path = Path(os.environ.get('XRAY_BIN', '.it-cache/xray/v26.3.27/Xray-linux-64/xray')).resolve()
    assert path.is_file(), 'Cached Xray required; run WorkerOutboundProxyIT once or set XRAY_BIN'
    inbounds = []
    for kind, protocol, settings in [
        ('http', 'http', {}), ('socks', 'socks', {'auth': 'noauth', 'udp': False}),
        ('ss', 'shadowsocks', {'method': 'aes-128-gcm', 'password': password, 'network': 'tcp'}),
        ('reality', 'vless', {'clients': [{'id': identity, 'flow': 'xtls-rprx-vision'}], 'decryption': 'none'})]:
        inbound = {'tag': kind, 'listen': '127.0.0.1', 'port': UPSTREAMS[kind], 'protocol': protocol, 'settings': settings}
        if kind == 'reality':
            inbound['streamSettings'] = {'network': 'tcp', 'security': 'reality', 'realitySettings': {
                'show': False, 'dest': '127.0.0.1:' + str(TLS_ORIGIN), 'serverNames': ['localhost'],
                'privateKey': private, 'shortIds': [short_id]}}
        inbounds.append(inbound)
    config = {'log': {'loglevel': 'warning'}, 'inbounds': inbounds,
              'outbounds': [{'tag': 'echo', 'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:' + str(ORIGIN)}},
                            {'tag': 'tls', 'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:' + str(TLS_ORIGIN)}}],
              'routing': {'rules': [{'type': 'field', 'inboundTag': ['reality'], 'outboundTag': 'tls'}]}}
    config_file = Path(directory) / 'xray.json'
    config_file.write_text(json.dumps(config))
    config_file.chmod(0o600)
    log = stack.enter_context(open(Path(directory) / 'xray.log', 'w'))
    process = subprocess.Popen([str(path), 'run', '-config', str(config_file)], stdout=log, stderr=subprocess.STDOUT)
    def stop():
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    stack.callback(stop)
    def ready():
        assert process.poll() is None, 'Xray exited before fixture readiness'
        for kind in ['http', 'socks', 'ss', 'reality']:
            with socket.create_connection(('127.0.0.1', UPSTREAMS[kind]), timeout=1): pass
        return True
    a.wait('Xray fixture listening', ready, 10)


def provision(api, public, identity, short_id, password):
    users = api.request('/api/users?pageSize=200')['items']
    current = next((row for row in users if row['username'] == PREFIX), None)
    user_payload = {'username': PREFIX, 'credential': password, 'status': 1, 'remark': 'local timing E2E fixture; logs retained'}
    user = api.request('/api/users' + ('/' + str(current['id']) if current else ''), user_payload, 'PUT' if current else 'POST')
    routes = []
    for kind in ['http', 'socks', 'ss', 'reality', 'reject']:
        protocol = {'http': 'HTTPS_CONNECT', 'socks': 'SOCKS5', 'ss': 'SHADOW_SOCKS', 'reality': 'VLESS_REALITY', 'reject': 'HTTPS_CONNECT'}[kind]
        payload = {'policy': 'OUTBOUND_PROXY', 'outboundProxyType': protocol, 'outboundProxyHost': '127.0.0.1',
                   'outboundProxyPort': UPSTREAMS[kind], 'status': 1,
                   'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': kind + '.timing.home-proxy.test'}]}
        if kind == 'ss': payload.update(outboundProxyEncAlgo='aes_128_gcm', outboundProxyPassword=password)
        if kind == 'reality': payload['outboundProxyConfig'] = {'serverName': 'localhost', 'publicKey': public,
                'shortId': short_id, 'uuid': identity, 'flow': 'xtls-rprx-vision'}
        routes.append(a.fixture.upsert(api, '/api/routes', PREFIX + '-' + kind, payload))
    direct = a.fixture.upsert(api, '/api/routes', PREFIX + '-direct', {'policy': 'DESTINATION_OVERRIDE',
        'outboundProxyHost': '127.0.0.1', 'outboundProxyPort': ORIGIN, 'status': 1,
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': '*.timing.home-proxy.test'}]})
    sni_route = a.fixture.upsert(api, '/api/routes', PREFIX + '-sni', {'policy': 'DESTINATION_OVERRIDE',
        'outboundProxyHost': '127.0.0.1', 'outboundProxyPort': TLS_ORIGIN, 'status': 1,
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': '*'}]})
    inbounds = []
    for protocol, port, fallback, route_ids in [('SOCKS5', SOCKS, direct['id'], [r['id'] for r in routes] + [direct['id']]),
                                               ('TLS_SNI_TCP', SNI, sni_route['id'], [])]:
        inbounds.append(a.fixture.upsert(api, '/api/inbounds', PREFIX + '-' + protocol.lower(), {
            'workerId': WORKER, 'protocol': protocol, 'listenIp': '127.0.0.1', 'port': port,
            'fallbackRouteId': fallback, 'anonymousUserId': user['id'], 'tlsEnabled': False, 'sniffEnabled': False,
            'inboundRouteBindings': [{'userIds': [user['id']], 'routeIds': route_ids}] if route_ids else [],
            'status': 1, 'notes': 'local timing E2E fixture; disabled after collection; logs retained'}))
    expected = api.request('/api/workers/' + WORKER + '/effective-config')['configHash']
    a.wait('Worker applied timing fixtures', lambda: api.request('/api/workers/' + WORKER + '/status').get('appliedConfigHash') == expected)
    return inbounds


def connect_socks(host, password, reject=False):
    client = socket.socket()
    client.settimeout(15)
    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 131072)
    try:
        client.connect(('127.0.0.1', SOCKS))
        client.sendall(b'\x05\x01\x02')
        assert a.recv_exact(client, 2) == b'\x05\x02'
        name, secret = PREFIX.encode(), password.encode()
        client.sendall(b'\x01' + bytes([len(name)]) + name + bytes([len(secret)]) + secret)
        assert a.recv_exact(client, 2) == b'\x01\x00'
        encoded = host.encode()
        client.sendall(b'\x05\x01\x00\x03' + bytes([len(encoded)]) + encoded + struct.pack('!H', ORIGIN))
        response = a.recv_exact(client, 4)
        assert response[1] != 0 if reject else response[1] == 0
        if not reject:
            if response[3] == 1: a.recv_exact(client, 6)
            elif response[3] == 4: a.recv_exact(client, 18)
            else: a.recv_exact(client, a.recv_exact(client, 1)[0] + 2)
        return client
    except Exception:
        client.close();raise


def tls_request(client):
    with ssl._create_unverified_context().wrap_socket(client, server_hostname='localhost') as secured:
        secured.sendall(b'GET /timing-e2e HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n')
        response = b''
        while True:
            try:
                part = secured.recv(65536)
            except ssl.SSLError as error:
                raise AssertionError(f'TLS failed after {len(response)} response bytes; cipher={secured.cipher()[0]}') from error
            if not part: break
            response += part
            headers, separator, body = response.partition(b'\r\n\r\n')
            if separator:
                length = next((int(line.split(b':', 1)[1]) for line in headers.split(b'\r\n') if line.lower().startswith(b'content-length:')), None)
                if length is not None and len(body) >= length: break
        assert b'TIMING_HTTPS_OK' in response


def traffic(password):
    for kind in ['http', 'socks', 'ss']:
        with connect_socks(kind + '.timing.home-proxy.test', password) as client:
            client.sendall(PAYLOAD);assert a.recv_exact(client, len(PAYLOAD)) == PAYLOAD
    tls_request(connect_socks('reality.timing.home-proxy.test', password))
    with connect_socks('reject.timing.home-proxy.test', password, True): pass
    with connect_socks('download.timing.home-proxy.test', password) as client:
        client.sendall(b'DOWNLOAD\n')
        time.sleep(1)  # Pause the receiver while the origin produces 16 MiB.
        payload = a.recv_exact(client, BULK_SIZE)
        assert len(payload) == BULK_SIZE and hashlib.sha256(payload).hexdigest() == hashlib.sha256(b'd' * BULK_SIZE).hexdigest()
    with connect_socks('upload.timing.home-proxy.test', password) as client:
        client.sendall(b'UPLOAD\n' + b'u' * BULK_SIZE)
        assert a.recv_exact(client, len(str(BULK_SIZE)) + 1) == str(BULK_SIZE).encode() + b'\n'
    tls_request(socket.create_connection(('127.0.0.1', SNI), timeout=15))


def audit(api, started, expected=8):
    scope = {'workerId': WORKER, 'from': a.iso(started), 'to': a.iso(datetime.now(timezone.utc) + timedelta(minutes=2)), 'limit': 200}
    def rows(kind):
        return [r for r in api.request('/api/observability/v1/' + kind + '?' + urlencode(scope)) if (r.get('inboundName') or '').startswith(PREFIX)]
    sessions = a.wait('new timing connection summaries persisted', lambda: (lambda r: r if len(r) >= expected else None)(rows('sessions')))
    assert len(sessions) == expected, 'Unexpected fixture session count'
    for row in sessions:
        assert all(key not in row for key in ['rateLimitWaitUs', 'rateLimitPolicyId'])
        for field in TIMINGS:
            value = row.get(field)
            assert value is None or isinstance(value, str) and value.isdecimal(), (row['recordId'], field)
        assert row.get('routeSelectionDurationUs') is not None
        assert row['targetRecoveryDurationUs'] is not None if row['inboundProtocol'] == 'TLS_SNI_TCP' else row['clientHandshakeDurationUs'] is not None
        assert row['outboundTcpDurationUs'] is not None
        assert row['sessionDurationUs'] is not None and row['channelSetupDurationUs'] is not None
        assert int(row['sessionDurationUs']) >= int(row['channelSetupDurationUs'])
        # All temporary target and upstream endpoints are IP literals; the separate Fake-IP E2E verifies DNS.
        assert row['dnsDurationUs'] is None
    by_host = {r.get('logicalTargetHost'): r for r in sessions if r['inboundProtocol'] == 'SOCKS5'}
    for kind in ['http', 'socks', 'ss', 'reality']:
        row = by_host[kind + '.timing.home-proxy.test']
        assert row['connectOutcome'] == 'CONNECTED' and row['sessionOutcome'] == 'NORMAL_END'
        assert row['proxyHandshakeDurationUs'] is not None
        assert row['proxyHandshakeOutcome'] == ('SUCCESS' if kind in ['http', 'socks'] else 'LOCAL_REQUEST_SENT')
        assert row['outboundTlsDurationUs'] is not None if kind == 'reality' else row['outboundTlsDurationUs'] is None
        if kind != 'reality': assert row['uploadBytes'] == row['downloadBytes'] == str(len(PAYLOAD))
    rejected = by_host['reject.timing.home-proxy.test']
    assert rejected['connectOutcome'] == 'FAILED' and rejected['proxyHandshakeOutcome'] == 'FAILED'
    assert rejected['proxyHandshakeDurationUs'] is not None and rejected['uploadBytes'] == rejected['downloadBytes'] == '0'
    download = by_host['download.timing.home-proxy.test']
    upload = by_host['upload.timing.home-proxy.test']
    assert download['downloadBytes'] == str(BULK_SIZE)
    assert upload['uploadBytes'] == str(BULK_SIZE + len(b'UPLOAD\n'))
    assert int(download['downloadBackpressureDurationUs']) > 0, 'Download backpressure was not exercised'
    assert int(upload['uploadBackpressureDurationUs']) > 0, 'Upload backpressure was not exercised'
    assert all(r['targetRecoveryDurationUs'] is None for r in by_host.values())
    sni = next(r for r in sessions if r['inboundProtocol'] == 'TLS_SNI_TCP')
    assert sni['clientHandshakeDurationUs'] is None and sni['outboundTlsDurationUs'] is None
    for row in sessions:
        detail = api.request('/api/observability/v1/sessions/' + row['recordId'] + '?' + urlencode({k:v for k,v in scope.items() if k != 'limit'}))
        assert all(detail.get(key) == row.get(key) for key in TIMINGS)
    events = rows('events')
    for event in events:
        if event['eventType'] == 'CONNECT_RESULT':
            session = next(r for r in sessions if r['connectionId'] == event['connectionId'])
            assert event['durationUs'] == session['channelSetupDurationUs']
    status = api.request('/api/observability/v1/status?workerId=' + WORKER)
    assert status['worker']['dropped'] == status['worker']['serializationFailures'] == 0
    return {'workerId': WORKER, 'from': scope['from'], 'to': scope['to'], 'counts': {'sessions': len(sessions), 'events': len(events)},
            'sessionSamples': [{k:r.get(k) for k in ['recordId', 'connectionId', 'workerInstanceId', 'inboundName', 'inboundProtocol',
             'logicalTargetHost', 'outboundProtocol', 'connectOutcome', 'sessionOutcome', 'proxyHandshakeOutcome',
             'uploadBytes', 'downloadBytes', 'startedAt', 'connectedAt', 'endedAt'] + TIMINGS} for r in sessions],
            'tablesPreserved': True, 'checks': {'protocolTimings': True, 'failureTimings': True, 'actualTls': True,
             'sniRecovery': True, 'positiveUploadBackpressure': True, 'positiveDownloadBackpressure': True,
             'detailConsistent': True, 'uint64Strings': True, 'removedLimiterFields': True, 'noDrops': True}}


def main():
    api = a.Api()
    inbounds = []
    report_path = Path(os.environ.get('E2E_TIMING_REPORT', '/tmp/gateway-observability-session-timing-e2e.json'))
    with contextlib.ExitStack() as stack:
        directory = stack.enter_context(tempfile.TemporaryDirectory(prefix='session-timing-e2e-'))
        private, public, tls = new_material(directory)
        password, identity, short_id = 'local-timing-fixture-' + uuid.uuid4().hex[:12], str(uuid.uuid4()), uuid.uuid4().hex[:16]
        start_server(stack, ORIGIN, Origin)
        start_server(stack, TLS_ORIGIN, TlsOrigin, tls)
        start_server(stack, UPSTREAMS['reject'], Reject)
        start_xray(stack, directory, private, identity, short_id, password)
        try:
            inbounds = provision(api, public, identity, short_id, password)
            started = datetime.now(timezone.utc) - timedelta(seconds=1)
            traffic(password)
            report = audit(api, started)
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps({'workerId': WORKER, 'counts': report['counts'], 'checks': report['checks'], 'report': str(report_path)}, ensure_ascii=False, indent=2))
        finally:
            if not inbounds:
                inbounds = [r for r in api.request('/api/inbounds?pageSize=200')['items'] if r.get('workerId') == WORKER and (r.get('name') or '').startswith(PREFIX + '-') and r.get('status') == 1]
            for inbound in inbounds:
                payload = dict(inbound, status=0)
                api.request('/api/inbounds/' + str(inbound['id']), payload, 'PUT')
            if inbounds:
                expected = api.request('/api/workers/' + WORKER + '/effective-config')['configHash']
                a.wait('Worker disabled temporary test listeners', lambda: api.request('/api/workers/' + WORKER + '/status').get('appliedConfigHash') == expected)


if __name__ == '__main__':
    main()
