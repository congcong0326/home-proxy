#!/usr/bin/env python3
"""Retained-data V1 protocol probe: durable ACK, replay, conflict and worker binding.

Synthetic fixtures are explicitly named separately from real proxy facts. No table
cleanup is performed. The fixture worker is disabled after the probe.
"""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import struct
import subprocess
import tempfile
import time
import urllib.parse
import uuid
from datetime import datetime, timezone, timedelta

spec = importlib.util.spec_from_file_location('telemetry_e2e', Path(__file__).with_name('gateway-observability-stage-a-e2e.py'))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)
WORKER = 'telemetry-a-protocol-probe'
PORT = 29544


def exact(connection, count):
    data = b''
    while len(data) < count:
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise EOFError()
        data += chunk
    return data


def send(connection, message):
    data = json.dumps(message).encode()
    length = len(data)
    header = b'\x81' + (bytes([length]) if length < 126 else b'\x7e' + struct.pack('!H', length) if length < 65536 else b'\x7f' + struct.pack('!Q', length))
    connection.sendall(header + data)


def receive(connection):
    fragments = bytearray()
    while True:
        first, second = exact(connection, 2)
        length = second & 127
        if length == 126:
            length = struct.unpack('!H', exact(connection, 2))[0]
        elif length == 127:
            length = struct.unpack('!Q', exact(connection, 8))[0]
        mask = exact(connection, 4) if second & 128 else None
        data = exact(connection, length)
        if mask:
            data = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
        opcode = first & 15
        if opcode == 8:
            raise EOFError('controller closed')
        if opcode in (1, 0):
            fragments.extend(data)
            if first & 128:
                return json.loads(fragments)


def get_type(connection, kind):
    while True:
        value = receive(connection)
        if value['type'] == kind:
            return value


def main():
    api = e2e.Api()
    token = 'hpw_' + uuid.uuid4().hex
    host = urllib.parse.urlparse(api.request('/api/workers/' + e2e.WORKER)['managementUrl']).hostname
    metadata = {'displayName': 'Stage A synthetic protocol reliability probe', 'description': 'retained test facts; no real sessions',
                'managementUrl': f'wss://{host}:{PORT}/management/ws', 'managementToken': token,
                'logReportingEnabled': True, 'logLevel': 'INFO', 'enabled': True}
    registered = False
    with tempfile.TemporaryDirectory(prefix='telemetry-protocol-probe-') as directory:
        cert, key = str(Path(directory) / 'certificate.pem'), str(Path(directory) / 'private.pem')
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key,
                        '-out', cert, '-subj', '/CN=telemetry-protocol-fixture', '-days', '1'], check=True, capture_output=True)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('0.0.0.0', PORT)); listener.listen(); listener.settimeout(20)
            existing = next((worker for worker in api.request('/api/workers') if worker['workerId'] == WORKER), None)
            if existing:
                api.request('/api/workers/' + WORKER, metadata, 'PUT')
            else:
                api.request('/api/workers', dict({key: value for key, value in metadata.items() if key != 'enabled'}, workerId=WORKER), 'POST')
            registered = True
            try:
                raw, _ = listener.accept()
                with context.wrap_socket(raw, server_side=True) as connection:
                    connection.settimeout(10)
                    request = b''
                    while not request.endswith(b'\r\n\r\n'):
                        request += exact(connection, 1)
                    headers = dict(line.split(':', 1) for line in request.decode().split('\r\n')[1:] if ':' in line)
                    headers = {key.lower(): value.strip() for key, value in headers.items()}
                    assert headers.get('authorization') == 'Bearer ' + token
                    assert headers.get('x-home-proxy-telemetry') == '1'
                    accept = base64.b64encode(hashlib.sha1((headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                    connection.sendall(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n').encode())
                    send(connection, {'type': 'CONFIG_POLL', 'workerId': WORKER})
                    send(connection, {'type': 'TELEMETRY_HELLO', 'workerId': WORKER, 'telemetryVersion': 1})
                    get_type(connection, 'TELEMETRY_ACCEPT')
                    now = datetime.now(timezone.utc)
                    identity = str(uuid.uuid4())
                    fact = {'schemaVersion': 1, 'workerId': 'spoofed-worker', 'workerInstanceId': str(uuid.uuid4()),
                            'connectionId': identity, 'recordId': identity, 'occurredAt': e2e.iso(now),
                            'startedAt': e2e.iso(now - timedelta(seconds=1)), 'connectedAt': e2e.iso(now - timedelta(milliseconds=500)), 'endedAt': e2e.iso(now),
                            'inboundName': 'SYNTHETIC_PROTOCOL_PROBE', 'connectOutcome': 'CONNECTED', 'sessionOutcome': 'NORMAL_END',
                            'uploadBytes': '9007199254740993', 'downloadBytes': '1024', 'trafficCategory': 'UNKNOWN'}
                    batch = {'protocolVersion': 1, 'batchId': str(uuid.uuid4()), 'sessions': [fact], 'dnsQueries': [], 'events': [], 'credentialAuth': []}
                    envelope = {'type': 'TELEMETRY_BATCH', 'workerId': WORKER, 'telemetryVersion': 1, 'telemetryBatch': batch}
                    query = urllib.parse.urlencode({'workerId': WORKER, 'from': e2e.iso(now - timedelta(seconds=2)), 'to': e2e.iso(now + timedelta(minutes=2)), 'connectionId': identity})
                    def rows():
                        return api.request('/api/observability/v1/sessions?' + query)
                    send(connection, envelope)
                    ack = get_type(connection, 'TELEMETRY_ACK')['telemetryAck']
                    assert ack['persisted'] and ack['batchId'] == batch['batchId'], ack
                    first = rows()
                    assert len(first) == 1 and first[0]['workerId'] == WORKER
                    assert first[0]['uploadBytes'] == '9007199254740993'
                    send(connection, envelope)  # simulates ACK loss; IDs and payload remain unchanged
                    assert get_type(connection, 'TELEMETRY_ACK')['telemetryAck']['persisted']
                    replayed = rows()
                    assert len(replayed) == 1 and replayed[0]['receivedAt'] == first[0]['receivedAt']
                    assert replayed[0]['uploadBytes'] == first[0]['uploadBytes']
                    # Inject a second identical physical row to exercise FINAL independently
                    # of the ingest replay shortcut. Both rows are retained; no cleanup.
                    duplicate_sql = ("INSERT INTO telemetry.proxy_session_log_v1 SELECT * FROM telemetry.proxy_session_log_v1 FINAL "
                                     "WHERE worker_id = 'telemetry-a-protocol-probe' AND record_id = '" + identity + "'")
                    subprocess.run(['docker', 'exec', os.environ.get('E2E_CLICKHOUSE_CONTAINER', 'home-proxy-clickhouse'),
                        'sh', '-c', 'exec clickhouse-client --password "$CLICKHOUSE_PASSWORD" --query "$1"', 'sh', duplicate_sql],
                        check=True, capture_output=True, text=True)
                    duplicated = rows()
                    assert len(duplicated) == 1 and duplicated[0]['uploadBytes'] == first[0]['uploadBytes']
                    fact['uploadBytes'] = '0'
                    send(connection, envelope)
                    rejected = get_type(connection, 'TELEMETRY_ACK')['telemetryAck']
                    assert rejected['persisted'] is False, rejected
                    assert rows()[0]['uploadBytes'] == first[0]['uploadBytes']
                    print('PASS: durable ACK, identical replay deduplication, first received_at, physical duplicate FINAL query, same-ID conflict rejection, authenticated worker binding, large UInt64 strings')
                    Path('/tmp/gateway-observability-protocol-probe.json').write_text(json.dumps({'workerId': WORKER, 'recordId': identity,
                        'batchId': batch['batchId'], 'receivedAt': first[0]['receivedAt'], 'uploadBytes': first[0]['uploadBytes'],
                        'replayUniqueRows': len(replayed), 'duplicateInsertUniqueRows': len(duplicated), 'conflictRejected': True, 'tablesPreserved': True}, indent=2) + '\n')
            finally:
                if registered:
                    api.request('/api/workers/' + WORKER, dict(metadata, enabled=False), 'PUT')


if __name__ == '__main__':
    main()
