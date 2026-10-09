#!/usr/bin/env python3
"""Persistent local Fake IP fixture. Requires the existing home-01 worker."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import socket
import socketserver
import ssl
import struct
import subprocess
import threading
import time
import urllib.parse
import urllib.request

DOMAIN = 'fake-direct.persistent.home-proxy.test'
NAMESPACE = 'hp-fake-ip-test'
HOST_IF = 'hpfi-host'
CLIENT_IF = 'hpfi-client'
GATEWAY = '192.0.2.193'
CLIENT = '192.0.2.194'
ORIGIN = '192.0.2.202'
DNS_PORT = 15353
UPSTREAM_PORT = 15354
HTTP_PORT = 29290
GREETING_PORT = 29291
TPROXY_PORT = 29180
MARK = '0x94'
TABLE = '294'
PRIORITY = '1294'
UNIT = 'home-proxy-fake-ip-e2e-fixture'
BODY = b'PERSISTENT_FAKE_IP_E2E_OK\n'
BASE = os.environ.get('E2E_CONTROL_BASE', 'https://127.0.0.1:18443')
WORKER = os.environ.get('E2E_WORKER_ID', 'home-01')


def command(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True)


class Api:
    def __init__(self):
        self.token = None
        self.token = self.request('/admin/login', {
            'username': os.environ.get('E2E_ADMIN_USER', 'congcong'),
            'password': os.environ.get('E2E_ADMIN_PASSWORD', 'congcong'),
        })['token']

    def request(self, path, payload=None, method=None):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(
            BASE + path, headers=headers, method=method,
            data=json.dumps(payload).encode() if payload is not None else None)
        # Local controller generates a self-signed certificate at startup.
        with urllib.request.urlopen(request, context=ssl._create_unverified_context(), timeout=15) as response:
            return json.load(response)


def upsert(api, resource, name, payload):
    existing = next((row for row in api.request(resource + '?pageSize=200')['items']
                     if row['name'] == name), None)
    payload = dict(payload, name=name)
    return api.request(resource + ('/' + str(existing['id']) if existing else ''),
                       payload, 'PUT' if existing else 'POST')


def network_setup():
    if NAMESPACE not in command('ip', 'netns', 'list').stdout.split():
        command('ip', 'netns', 'add', NAMESPACE)
        command('ip', 'link', 'add', HOST_IF, 'type', 'veth', 'peer', 'name', CLIENT_IF)
        command('ip', 'link', 'set', CLIENT_IF, 'netns', NAMESPACE)
    command('ip', 'addr', 'replace', GATEWAY + '/30', 'dev', HOST_IF)
    command('ip', 'link', 'set', HOST_IF, 'up')
    command('ip', 'netns', 'exec', NAMESPACE, 'ip', 'link', 'set', 'lo', 'up')
    command('ip', 'netns', 'exec', NAMESPACE, 'ip', 'addr', 'replace', CLIENT + '/30', 'dev', CLIENT_IF)
    command('ip', 'netns', 'exec', NAMESPACE, 'ip', 'link', 'set', CLIENT_IF, 'up')
    command('ip', 'netns', 'exec', NAMESPACE, 'ip', 'route', 'replace', 'default', 'via', GATEWAY)
    command('ip', 'addr', 'replace', ORIGIN + '/32', 'dev', 'lo')
    rules = command('ip', 'rule', 'show').stdout
    own = next((line for line in rules.splitlines() if line.startswith(PRIORITY + ':')), None)
    if own and (MARK not in own or 'lookup ' + TABLE not in own):
        raise RuntimeError('Policy rule priority is occupied: ' + own)
    if not own:
        command('ip', 'rule', 'add', 'priority', PRIORITY, 'fwmark', MARK + '/' + MARK, 'lookup', TABLE)
    command('ip', 'route', 'replace', 'local', '0.0.0.0/0', 'dev', 'lo', 'table', TABLE)
    rule = ['PREROUTING', '-i', HOST_IF, '-p', 'tcp', '-d', '198.18.0.0/15',
            '-m', 'comment', '--comment', UNIT, '-j', 'TPROXY', '--on-ip', '0.0.0.0',
            '--on-port', str(TPROXY_PORT), '--tproxy-mark', MARK + '/' + MARK]
    if command('iptables', '-t', 'mangle', '-C', *rule, check=False).returncode:
        command('iptables', '-t', 'mangle', '-A', *rule)


def setup():
    if os.geteuid() != 0:
        raise RuntimeError('Run setup as root for network namespace/TPROXY configuration')
    api = Api()
    inbounds = api.request('/api/inbounds?pageSize=200')['items']
    transparent = [row for row in inbounds if row['workerId'] == WORKER
                   and row['protocol'] == 'TP_PROXY' and row['status'] == 1]
    if len(transparent) != 1 or transparent[0]['port'] != TPROXY_PORT:
        raise RuntimeError('Expected one active transparent inbound on port 29180')
    transparent = transparent[0]
    user = transparent['anonymousUserId']
    if not user:
        raise RuntimeError('Transparent inbound requires an anonymous user')
    network_setup()
    if command('systemctl', 'is-active', '--quiet', UNIT, check=False).returncode:
        command('systemctl', 'reset-failed', UNIT, check=False)
        command('systemd-run', '--unit=' + UNIT, '--property=Restart=on-failure',
                '/usr/bin/python3', '-u', str(Path(__file__).resolve()), 'serve')
    for _ in range(50):
        try:
            with socket.create_connection((ORIGIN, GREETING_PORT), timeout=1) as client:
                assert client.recv(128) == BODY
            break
        except (OSError, AssertionError):
            time.sleep(.2)
    else:
        raise RuntimeError('Fixture failed to start; inspect journalctl -u ' + UNIT)
    dns_route = upsert(api, '/api/routes', 'home-01-fake-ip-real-dns', {
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': '*'}],
        'policy': 'OUTBOUND_PROXY', 'outboundProxyType': 'DNS_SERVER',
        'outboundProxyHost': '127.0.0.1', 'outboundProxyPort': UPSTREAM_PORT,
        'status': 1, 'notes': '固定 Fake IP E2E 真实 DNS，上游仅提供测试域名'})
    direct_route = upsert(api, '/api/routes', 'home-01-fake-ip-test-direct', {
        'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': DOMAIN}],
        'policy': 'DIRECT', 'status': 1, 'notes': '固定 Fake IP E2E 测试域名直连'})
    bindings = transparent.get('inboundRouteBindings') or []
    binding = next((row for row in bindings if user in (row.get('userIds') or [])), None)
    if binding is None:
        binding = {'userIds': [user], 'routeIds': []}
        bindings.append(binding)
    if direct_route['id'] not in binding['routeIds']:
        binding['routeIds'].append(direct_route['id'])
    fields = ['workerId', 'name', 'protocol', 'listenIp', 'port', 'fallbackRouteId',
              'anonymousUserId', 'tlsEnabled', 'sniffEnabled', 'fakeIpEnabled', 'status', 'notes', 'ssMethod']
    payload = {key: transparent.get(key) for key in fields}
    payload['inboundRouteBindings'] = bindings
    api.request('/api/inbounds/' + str(transparent['id']), payload, 'PUT')
    dns = upsert(api, '/api/inbounds', 'home-01-fake-ip-dns', {
        'workerId': WORKER, 'protocol': 'DNS_SERVER', 'listenIp': GATEWAY, 'port': DNS_PORT,
        'fallbackRouteId': dns_route['id'], 'anonymousUserId': user,
        'tlsEnabled': False, 'sniffEnabled': False, 'fakeIpEnabled': True,
        'inboundRouteBindings': [], 'status': 1,
        'notes': '固定 Fake IP E2E；客户端 hp-fake-ip-test；透明 TCP 入站 29180'})
    expected_count = sum(row['workerId'] == WORKER and row['status'] == 1
                         for row in api.request('/api/inbounds?pageSize=200')['items'])
    for _ in range(60):
        status = api.request('/api/workers/' + WORKER + '/status')
        if (status.get('applySuccess') and status.get('runningInboundCount') == expected_count
                and status.get('appliedConfigHash') == status.get('fetchedConfigHash')):
            print('READY:', dns['name'], GATEWAY + ':' + str(DNS_PORT), 'domain=' + DOMAIN)
            return
        time.sleep(1)
    raise RuntimeError('Worker did not apply configuration: ' + str(status.get('applyError')))


class DnsHandler(socketserver.BaseRequestHandler):
    def handle(self):
        packet, server = self.request
        labels, offset = [], 12
        while packet[offset]:
            length = packet[offset]
            labels.append(packet[offset + 1:offset + 1 + length].decode())
            offset += length + 1
        offset += 1
        qtype = struct.unpack_from('!H', packet, offset)[0]
        question = packet[12:offset + 4]
        domain = '.'.join(labels)
        known = domain == DOMAIN
        answer = known and qtype == 1
        response = packet[:2] + struct.pack('!HHHHH', 0x8180 if known else 0x8183,
                                          1, int(answer), 0, 0) + question
        if answer:
            response += b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 30, 4) + socket.inet_aton(ORIGIN)
        server.sendto(response, self.client_address)
        print('REAL_DNS', domain, 'type=' + str(qtype), 'answer=' + (ORIGIN if answer else 'empty'), flush=True)


class HttpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        packet = b''
        while b'\r\n\r\n' not in packet:
            part = self.request.recv(4096)
            if not part:
                return
            packet += part
        print('ORIGIN_HTTP', self.client_address, repr(packet), flush=True)
        self.request.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: ' + str(len(BODY)).encode()
                             + b'\r\n\r\n' + BODY)


class GreetingHandler(socketserver.BaseRequestHandler):
    def handle(self):
        print('ORIGIN_SERVER_FIRST', self.client_address, flush=True)
        self.request.sendall(BODY)


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve():
    servers = [socketserver.ThreadingUDPServer(('127.0.0.1', UPSTREAM_PORT), DnsHandler),
               TcpServer((ORIGIN, HTTP_PORT), HttpHandler),
               TcpServer((ORIGIN, GREETING_PORT), GreetingHandler)]
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print('Fixture running: DNS', UPSTREAM_PORT, 'HTTP', HTTP_PORT, 'server-first', GREETING_PORT, flush=True)
    threading.Event().wait()


def dns_query(qtype):
    name = b''.join(bytes([len(label)]) + label.encode() for label in DOMAIN.split('.')) + b'\0'
    question = name + struct.pack('!HH', qtype, 1)
    query = struct.pack('!HHHHHH', 0x7B31, 0x100, 1, 0, 0, 0) + question
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        client.settimeout(5)
        client.sendto(query, (GATEWAY, DNS_PORT))
        packet, _ = client.recvfrom(4096)
    assert packet[:2] == query[:2] and packet[12:12 + len(question)] == question
    assert struct.unpack_from('!H', packet, 2)[0] & 15 == 0
    count = struct.unpack_from('!H', packet, 6)[0]
    if qtype == 28:
        assert count == 0
        return None
    assert count == 1
    offset = 12 + len(question)
    if packet[offset] & 0xc0 == 0xc0:
        offset += 2
    else:
        while packet[offset]:
            offset += packet[offset] + 1
        offset += 1
    rtype, _, ttl, size = struct.unpack_from('!HHIH', packet, offset)
    assert rtype == 1 and ttl == 1 and size == 4
    address = socket.inet_ntoa(packet[offset + 10:offset + 14])
    assert ipaddress.ip_address(address) in ipaddress.ip_network('198.18.0.0/15')
    return address


def client_test():
    fake = dns_query(1)
    assert fake == dns_query(1)
    dns_query(28)
    print('PASS: stable Fake IP A, TTL=1, AAAA empty:', fake)
    with socket.create_connection((fake, HTTP_PORT), timeout=8) as client:
        client.sendall(b'GET / HTTP/1.0\r\n\r\n')
        response = b''
        while True:
            part = client.recv(4096)
            if not part:
                break
            response += part
    assert BODY in response
    print('PASS: DNS -> Fake IP -> TPROXY -> real DNS -> HTTP origin, without Host')
    with socket.create_connection((fake, GREETING_PORT), timeout=8) as client:
        assert client.recv(128) == BODY
    print('PASS: server-first TCP works without any client payload')
    print('FAKE_IP=' + fake)


def test():
    api = Api()
    started = time.time()
    result = command('ip', 'netns', 'exec', NAMESPACE, '/usr/bin/python3', str(Path(__file__).resolve()), 'client')
    print(result.stdout, end='', flush=True)
    fake = next(line.split('=', 1)[1] for line in result.stdout.splitlines() if line.startswith('FAKE_IP='))
    # Production defaults batch small log sets for up to 60 seconds.
    for attempt in range(90):
        rows = api.request('/api/logs/access?' + urllib.parse.urlencode({
            'workerId': WORKER, 'clientIp': CLIENT, 'originalTargetHost': DOMAIN,
            'pageSize': 100, 'sort': 'ts,desc'}))['items']
        details = []
        for row in rows:
            timestamp = int(row['requestId'].split('-')[1]) / 1000
            if timestamp < started - 1:
                continue
            detail = api.request('/api/logs/access/' + urllib.parse.quote(row['requestId'], safe=''))
            if detail.get('logicalTargetSource') == 'FAKE_IP' and detail.get('originalTargetIP') == fake:
                assert detail['finalConnectionIp'] == ORIGIN
                assert detail['dnsRouteId'] > 0 and detail['configVersion']
                assert detail['status'] == 200
                details.append(detail)
        if {HTTP_PORT, GREETING_PORT}.issubset({row['originalTargetPort'] for row in details}):
            print('PASS: both TCP audit records persisted with Fake IP/domain/real IP/DNS route/config version')
            for row in details:
                print(json.dumps({key: row.get(key) for key in [
                    'ts', 'requestId', 'proxyName', 'clientIp', 'originalTargetHost', 'originalTargetIP',
                    'originalTargetPort', 'logicalTargetSource', 'finalConnectionIp', 'dnsRouteId', 'configVersion']},
                                 ensure_ascii=False))
            print('Fixture and controller configuration remain active for manual inspection.')
            return
        if attempt in (0, 30, 60):
            print('Waiting for controller log batch to persist...', flush=True)
        time.sleep(1)
    raise RuntimeError('TCP worked but current-run audit records were not found within 90 seconds')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'test', 'serve', 'client'])
    action = parser.parse_args().action
    try:
        {'setup': setup, 'test': test, 'serve': serve, 'client': client_test}[action]()
    except Exception as error:
        raise SystemExit('FAIL: ' + str(error)) from error
