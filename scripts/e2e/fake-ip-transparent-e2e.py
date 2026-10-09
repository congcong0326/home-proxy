#!/usr/bin/env python3
"""Fake IP + Linux TPROXY scenarios; invoked by transparent-proxy-e2e.sh.

Only the isolated controller/worker owned by that shell harness are modified.
Client probes run in its network namespace. No third party Python packages needed.
"""
import argparse
import concurrent.futures
import contextlib
import ipaddress
import json
from pathlib import Path
import secrets
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

POOL = ipaddress.ip_network('198.18.0.0/15')
SUFFIX = '.tproxy-e2e.test'
UNKNOWN = '198.19.255.254'
GREETING = b'FAKE_IP_SERVER_FIRST_E2E\n'
EVENT_LOCK = threading.Lock()


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def wire_name(name):
    return b''.join(bytes([len(label)]) + label.encode('ascii')
                    for label in name.rstrip('.').split('.')) + b'\0'


def read_name(packet, offset):
    labels, end, visited = [], None, set()
    while True:
        check(offset not in visited and offset < len(packet), 'invalid DNS name')
        visited.add(offset)
        length = packet[offset]
        if length & 0xc0 == 0xc0:
            if end is None:
                end = offset + 2
            offset = ((length & 0x3f) << 8) | packet[offset + 1]
        elif length == 0:
            return '.'.join(labels), end or offset + 1
        else:
            offset += 1
            labels.append(packet[offset:offset + length].decode('ascii'))
            offset += length


def recv_exact(client, size):
    result = b''
    while len(result) < size:
        part = client.recv(size - len(result))
        check(part, 'unexpected EOF')
        result += part
    return result


def record(config, kind, **fields):
    with EVENT_LOCK, open(config['events'], 'a', encoding='utf-8') as output:
        output.write(json.dumps({'kind': kind, 'time': time.time(), **fields}) + '\n')
        output.flush()


def dns_response(config, query, transport):
    name, end = read_name(query, 12)
    qtype, qclass = struct.unpack_from('!HH', query, end)
    name = name.lower()
    record(config, 'dns', domain=name, qtype=qtype, transport=transport)
    label = name.split('.')[0]
    if label.startswith('timeout'):
        return None
    if label.startswith('concurrent') or label.startswith('cancel'):
        time.sleep(.6)
    if label.startswith('servfail'):
        return query[:2] + struct.pack('!HHHHH', 0x8182, 1, 0, 0, 0) + query[12:end + 4]
    code = 3 if label.startswith('nxdomain') else 0
    ttl = 0 if label.startswith('zero') else (2 if label.startswith('cname') else 4)
    answers = b''
    count = 0
    negative = code == 3 or label.startswith('nodata')
    authority = b''
    if negative:
        # RFC 2308: min(SOA TTL, SOA.MINIMUM) = 2 seconds.
        soa = wire_name('ns' + SUFFIX) + wire_name('admin' + SUFFIX) + struct.pack('!IIIII', 1, 2, 2, 2, 2)
        authority = b'\xc0\x0c' + struct.pack('!HHIH', 6, 1, 2, len(soa)) + soa
    elif qtype in (1, 28):
        if label.startswith('cname'):
            alias = wire_name('alias' + SUFFIX)
            answers += b'\xc0\x0c' + struct.pack('!HHIH', 5, 1, 2, len(alias)) + alias
            owner = alias
            count += 1
        else:
            owner = b'\xc0\x0c'
        address = ('198.18.123.123' if label.startswith('poison') else config['origin']) if qtype == 1 else '::1'
        raw = ipaddress.ip_address(address).packed
        answers += owner + struct.pack('!HHIH', qtype, qclass, 30 if count else ttl, len(raw)) + raw
        count += 1
    elif qtype == 16:
        raw = b'\x0eREAL_DNS_TXT_OK'
        answers = b'\xc0\x0c' + struct.pack('!HHIH', 16, 1, 4, len(raw)) + raw
        count = 1
    flags = 0x8180 | code
    if label.startswith('truncated') and transport == 'udp':
        flags |= 0x200
    return query[:2] + struct.pack('!HHHHH', flags, 1, count, int(negative), 0) + query[12:end + 4] + answers + authority


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class UdpServer(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(config):
    class DnsUdp(socketserver.BaseRequestHandler):
        def handle(self):
            query, server = self.request
            response = dns_response(config, query, 'udp')
            if response is not None:
                server.sendto(response, self.client_address)

    class DnsTcp(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(10)
            try:
                while True:
                    query = recv_exact(self.request, struct.unpack('!H', recv_exact(self.request, 2))[0])
                    response = dns_response(config, query, 'tcp')
                    if response is not None:
                        self.request.sendall(struct.pack('!H', len(response)) + response)
            except (OSError, AssertionError):
                pass

    class Greeting(socketserver.BaseRequestHandler):
        def handle(self):
            record(config, 'greeting')
            self.request.sendall(GREETING)

    class Echo(socketserver.BaseRequestHandler):
        def handle(self):
            record(config, 'echo')
            self.request.settimeout(15)
            try:
                while data := self.request.recv(65536):
                    self.request.sendall(data)
            except OSError:
                pass

    class Socks(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(10)
            version, size = recv_exact(self.request, 2)
            check(version == 5, 'SOCKS version')
            check(0 in recv_exact(self.request, size), 'SOCKS no-auth unavailable')
            self.request.sendall(b'\x05\x00')
            version, command, _, atyp = recv_exact(self.request, 4)
            check(version == 5 and command == 1, 'SOCKS CONNECT required')
            if atyp == 3:
                domain = recv_exact(self.request, recv_exact(self.request, 1)[0]).decode()
            else:
                domain = str(ipaddress.ip_address(recv_exact(self.request, 4 if atyp == 1 else 16)))
            port = struct.unpack('!H', recv_exact(self.request, 2))[0]
            record(config, 'socks', atyp=atyp, domain=domain, port=port)
            self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            request = b''
            while b'\r\n\r\n' not in request:
                request += recv_exact(self.request, 1)
            body = b'FAKE_IP_SOCKS_DOMAIN_E2E\n'
            self.request.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body)

    servers = [UdpServer(('127.0.0.1', config['upstream']), DnsUdp),
               TcpServer(('127.0.0.1', config['upstream']), DnsTcp),
               TcpServer((config['origin'], config['greeting']), Greeting),
               TcpServer((config['origin'], config['echo']), Echo),
               TcpServer(('127.0.0.1', config['socks']), Socks)]
    try:
        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        print('Fake IP fixtures ready', flush=True)
        threading.Event().wait()
    finally:
        for server in servers:
            server.server_close()


def dns_query(config, domain, qtype=1):
    question = wire_name(domain) + struct.pack('!HH', qtype, 1)
    ident = secrets.randbelow(65536)
    query = struct.pack('!HHHHHH', ident, 0x100, 1, 0, 0, 0) + question
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
        client.settimeout(8)
        client.sendto(query, (config['gateway'], config['dns']))
        packet, peer = client.recvfrom(4096)
    rid, flags, questions, count, _, _ = struct.unpack_from('!HHHHHH', packet)
    check(peer == (config['gateway'], config['dns']), 'unexpected DNS source')
    check(rid == ident and flags & 0x8000 and questions == 1, 'DNS ID/QR/QDCOUNT mismatch')
    if qtype in (1, 28):
        check(flags & 0x100 and flags & 0x80, 'DNS RD/RA missing: ' + hex(flags))
    check(packet[12:12 + len(question)] == question, 'DNS Question changed')
    records, offset = [], 12 + len(question)
    for _ in range(count):
        _, offset = read_name(packet, offset)
        kind, cls, ttl, size = struct.unpack_from('!HHIH', packet, offset)
        offset += 10
        raw = packet[offset:offset + size]
        offset += size
        records.append({'type': kind, 'class': cls, 'ttl': ttl,
                        'address': str(ipaddress.ip_address(raw)) if kind in (1, 28) else None})
    return {'rcode': flags & 15, 'records': records}


def probe(config, args):
    action = args['action']
    if action == 'dns':
        return dns_query(config, args['domain'], args.get('qtype', 1))
    fake, port = args['ip'], args.get('port', config['http'])
    started = time.monotonic()
    with socket.create_connection((fake, port), timeout=9) as raw:
        source_port = raw.getsockname()[1]
        raw.settimeout(9)
        if action == 'closed':
            try:
                payload = args.get('payload', '').encode()
                if args.get('oversize'):
                    payload = b'GET / HTTP/1.1\r\nX-Long: ' + b'x' * 70000
                if payload:
                    raw.sendall(payload)
                data = raw.recv(1)
                check(data == b'', 'rejected request received data: ' + repr(data))
            except (ConnectionResetError, BrokenPipeError):
                pass
            # socket.timeout is deliberately a test failure, never treated as BLOCK.
            return {'sourcePort': source_port, 'elapsed': time.monotonic() - started}
        if action == 'echo':
            payload = bytes(range(256)) * 1024
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                sent = pool.submit(raw.sendall, payload)
                check(recv_exact(raw, len(payload)) == payload, 'binary relay corrupted payload')
                sent.result()
            time.sleep(4.3)
            raw.sendall(b'connection-survives-real-dns-ttl')
            check(recv_exact(raw, 32) == b'connection-survives-real-dns-ttl', 'relay failed after TTL')
            raw.shutdown(socket.SHUT_WR)
            check(raw.recv(1) == b'', 'echo close did not propagate')
            return {'sourcePort': source_port}
        if action == 'greeting':
            check(recv_exact(raw, len(GREETING)) == GREETING, 'server-first greeting mismatch')
            return {'sourcePort': source_port, 'elapsed': time.monotonic() - started}
        if action == 'cancel':
            raw.sendall(b'GET / HTTP/1.0\r\n\r\n')
            time.sleep(.15)
            return {'sourcePort': source_port}
        with contextlib.ExitStack() as stack:
            client = raw
            if action == 'tls':
                tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                tls.check_hostname = False
                tls.verify_mode = ssl.CERT_NONE
                client = stack.enter_context(tls.wrap_socket(raw, server_hostname=args['host']))
            payload = b'GET / HTTP/1.0\r\n'
            if args.get('host'):
                payload += b'Host: ' + args['host'].encode() + b'\r\n'
            payload += b'\r\n'
            if args.get('fragmented'):
                client.sendall(payload[:5])
                time.sleep(.1)
                client.sendall(payload[5:-3])
                time.sleep(.1)
                client.sendall(payload[-3:])
            else:
                client.sendall(payload)
            response = b''
            while part := client.recv(65536):
                response += part
            body = response.split(b'\r\n\r\n', 1)[-1].decode().strip()
            check(response.startswith(b'HTTP/1.') and b' 200 ' in response.split(b'\r\n')[0],
                  'HTTP request did not return 200: ' + repr(response[:200]))
            check(body == args.get('expected', 'HTTP_DIRECT_E2E'), 'unexpected origin: ' + body)
            return {'sourcePort': source_port, 'body': body}


class Suite:
    def __init__(self, config, path):
        self.config, self.path = config, path
        self.token = path.with_name('admin-token').read_text()
        self.cases, self.audit = [], []
        self.audit_details = {}
        self.observed_metrics = {}
        self.started = time.time()
        self.route_ids = {}

    def api(self, path, payload=None, method=None, expected=None):
        request = urllib.request.Request(self.config['control'] + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            method=method or ('POST' if payload is not None else 'GET'),
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, context=ssl._create_unverified_context(), timeout=10) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        allowed = (200, 201) if expected is None else (expected,)
        check(status in allowed, f'{request.method} {path}: expected {allowed}, got {status}: {body[:500]!r}')
        return json.loads(body) if body else None

    def status(self):
        return self.api('/api/workers/' + self.config['worker'] + '/status')

    def wait(self, label, predicate, timeout=90):
        deadline, last = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            last = predicate()
            if last:
                return last
            time.sleep(.5)
        raise AssertionError('timed out waiting for ' + label + ': ' + repr(last))

    def apply(self, path, payload, method='PUT'):
        previous = self.status()['appliedConfigHash']
        result = self.api(path, payload, method)
        self.wait('new worker snapshot', lambda: self.applied(previous))
        return result

    def applied(self, previous=None):
        status = self.status()
        return (status.get('online') and status.get('applySuccess')
                and status.get('runningInboundCount') == 2
                and status.get('appliedConfigHash') == status.get('fetchedConfigHash')
                and (previous is None or status.get('appliedConfigHash') != previous))

    def client(self, action, **args):
        result = subprocess.run(['ip', 'netns', 'exec', self.config['namespace'], sys.executable,
                                 str(Path(__file__).resolve()), 'client', str(self.path)],
                                input=json.dumps({'action': action, **args}), text=True,
                                capture_output=True, timeout=25)
        check(result.returncode == 0, f'client {action} {args}: {result.stderr.strip()}')
        return json.loads(result.stdout)

    def events(self, kind='dns', domain=None):
        path = Path(self.config['events'])
        rows = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [row for row in rows if row['kind'] == kind and (domain is None or row.get('domain') == domain)]

    def fake(self, domain):
        response = self.client('dns', domain=domain)
        check(response['rcode'] == 0 and len(response['records']) == 1, 'Fake IP A response: ' + repr(response))
        answer = response['records'][0]
        check(answer['type'] == 1 and answer['class'] == 1 and answer['ttl'] == 1
              and ipaddress.ip_address(answer['address']) in POOL, 'invalid Fake IP A: ' + repr(answer))
        return answer['address']

    def request(self, domain, ip, action='http', source='FAKE_IP', **args):
        version = self.status()['appliedConfigHash']
        result = self.client(action, ip=ip, **args)
        self.audit.append({'domain': domain, 'ip': ip, 'port': args.get('port', self.config['http']),
                           'sourcePort': result['sourcePort'], 'source': source,
                           'route': args.get('auditRoute'), 'finalIp': args.get('auditFinal', self.config['origin']),
                           'status': args.get('auditStatus', 200), 'version': version})
        return result

    def case(self, name, test):
        started = time.monotonic()
        try:
            test()
        except Exception as error:
            self.cases.append({'name': name, 'status': 'FAIL', 'seconds': round(time.monotonic() - started, 3),
                               'error': str(error)})
            self.report()
            raise AssertionError(name + ': ' + str(error)) from error
        self.cases.append({'name': name, 'status': 'PASS', 'seconds': round(time.monotonic() - started, 3)})
        self.report()
        print('PASS:', name, flush=True)

    def report(self):
        Path(self.config['report']).write_text(json.dumps({'worker': self.config['worker'],
            'started': self.started, 'cases': self.cases, 'audit': self.audit,
            'metricsBeforeRestart': self.observed_metrics}, ensure_ascii=False, indent=2) + '\n')

    def route(self, label, policy, **args):
        result = self.api('/api/routes', {'name': 'e2e-fake-' + label, 'policy': policy, 'status': 1,
            'rules': [{'conditionType': 'DOMAIN', 'op': 'IN', 'value': label + SUFFIX}], **args})
        self.route_ids[label] = result['id']
        return result['id']

    @staticmethod
    def inbound_payload(row):
        fields = ('workerId', 'name', 'protocol', 'listenIp', 'port', 'fallbackRouteId', 'anonymousUserId',
                  'tlsEnabled', 'sniffEnabled', 'fakeIpEnabled', 'inboundRouteBindings', 'status', 'notes', 'ssMethod')
        return json.loads(json.dumps({key: row.get(key) for key in fields}))

    def configure(self):
        rows = self.api('/api/inbounds?pageSize=200')['items']
        self.dns = next(row for row in rows if row['workerId'] == self.config['worker'] and row['protocol'] == 'DNS_SERVER')
        self.tp = next(row for row in rows if row['workerId'] == self.config['worker'] and row['protocol'] == 'TP_PROXY')
        routes = self.api('/api/routes?pageSize=200')['items']
        self.direct = next(row['id'] for row in routes if row['name'] == 'e2e-tproxy-fake-direct')
        self.dns_route = self.dns['fallbackRouteId']
        user = self.tp['anonymousUserId']
        block = self.route('fake-block', 'BLOCK')
        override = self.route('fake-override', 'DESTINATION_OVERRIDE',
                              outboundProxyHost=self.config['override'], outboundProxyPort=self.config['overrideHttp'])
        socks = self.route('fake-socks', 'OUTBOUND_PROXY', outboundProxyType='SOCKS5',
                           outboundProxyHost='127.0.0.1', outboundProxyPort=self.config['socks'])
        dns_block = self.route('dns-block', 'BLOCK')
        rewrite = self.route('dns-rewrite', 'DNS_REWRITING', outboundProxyHost=self.config['origin'])
        payload = self.inbound_payload(self.tp)
        payload['inboundRouteBindings'][0]['routeIds'] += [block, override, socks]
        self.tp = self.apply('/api/inbounds/' + str(self.tp['id']), payload)
        payload = self.inbound_payload(self.dns)
        payload['inboundRouteBindings'] = [{'userIds': [user], 'routeIds': [dns_block, rewrite]}]
        self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)

    def audit_visible(self):
        rows = self.api('/api/logs/access?' + urllib.parse.urlencode({
            'workerId': self.config['worker'], 'clientIp': self.config['client'], 'pageSize': 200,
            'sort': 'ts,desc'}))['items']
        for expected in self.audit:
            candidates = [row for row in rows if row.get('originalTargetHost') == expected['domain']]
            detail = None
            for row in candidates:
                ident = row['requestId']
                if ident not in self.audit_details:
                    self.audit_details[ident] = self.api('/api/logs/access/' + urllib.parse.quote(ident, safe=''))
                candidate = self.audit_details[ident]
                if candidate.get('clientPort') == expected['sourcePort']:
                    detail = candidate
                    break
            if detail is None:
                return False
            check(detail['status'] == expected['status'], 'audit status: ' + repr(detail))
            check(detail['originalTargetIP'] == expected['ip'] and detail['originalTargetPort'] == expected['port'],
                  'audit original destination: ' + repr(detail))
            check(detail['logicalTargetSource'] == expected['source'], 'audit target source: ' + repr(detail))
            final_ip_matches = (detail.get('finalConnectionIp') in (None, '') if expected['finalIp'] is None
                                else detail.get('finalConnectionIp') == expected['finalIp'])
            check(final_ip_matches and detail['configVersion'] == expected['version'],
                  'audit final IP/config version: ' + repr(detail))
            if expected['status'] != 200:
                check(detail['errorCode'] and detail['errorMsg'], 'failure audit lacks reason')
            if expected['route']:
                check(detail['routePolicyId'] == expected['route'], 'audit TCP route mismatch')
            if expected['finalIp'] == self.config['origin']:
                check(detail['dnsRouteId'] == self.dns_route, 'audit real DNS route mismatch')
            expected['requestId'] = detail['requestId']
        return True

    def run(self):
        c = self.config
        self.case('control-plane route and DNS policy setup', self.configure)
        direct = 'fake-direct' + SUFFIX
        fake = self.fake(direct)

        def issuance():
            before = len(self.events())
            check(self.fake(direct) == fake and self.fake(direct.upper() + '.') == fake, 'normalization changed mapping')
            for kind in (28,):
                check(self.client('dns', domain=direct, qtype=kind) == {'rcode': 0, 'records': []}, 'AAAA was not NODATA')
            check(self.fake('other' + SUFFIX) != fake, 'distinct domains share a mapping')
            check(len(self.events()) == before, 'Fake IP issuance queried real upstream')
        self.case('A/AAAA ID, Question, flags, TTL, stable normalized mapping and no upstream lookup', issuance)
        self.case('mapped HTTP without Host selects domain DIRECT route',
                  lambda: self.request(direct, fake, auditRoute=self.direct))
        self.case('mapped HTTP identity takes priority over conflicting blocked Host',
                  lambda: self.request(direct, fake, host='blocked-http' + SUFFIX, auditRoute=self.direct))
        self.case('mapped TLS identity takes priority over conflicting blocked SNI',
                  lambda: self.request(direct, fake, 'tls', host='blocked-tls' + SUFFIX, port=c['tls'], expected='TLS_DIRECT_E2E'))
        self.case('mapped server-first protocol needs no client payload',
                  lambda: self.request(direct, fake, 'greeting', port=c['greeting']))
        self.case('256 KiB binary relay and established connection survives DNS TTL',
                  lambda: self.request(direct, fake, 'echo', port=c['echo']))
        self.case('unmapped fragmented HTTP recovers Host',
                  lambda: self.request(direct, UNKNOWN, source='HTTP_HOST', host=direct, fragmented=True))
        self.case('unmapped TLS recovers SNI',
                  lambda: self.request(direct, UNKNOWN, 'tls', source='TLS_SNI', host=direct, port=c['tls'], expected='TLS_DIRECT_E2E'))
        for label, args in [('unmapped HTTP with no Host closes', {'payload': 'GET / HTTP/1.0\r\n\r\n'}),
                            ('unmapped binary closes', {'payload': '\x01\x02\x03\x04\x05'}),
                            ('unmapped idle connection closes within sniff deadline', {}),
                            ('unmapped incomplete headers over 64 KiB close', {'oversize': True})]:
            self.case(label, lambda args=args: check(self.client('closed', ip=UNKNOWN, **args)['elapsed'] < 5,
                                                   'sniff guard exceeded deadline'))

        def blocked():
            domain = 'fake-block' + SUFFIX
            address = self.fake(domain)
            before = len(self.events(domain=domain))
            # TCP BLOCK logs are deliberately suppressed by AccessLogUtil; verify closure
            # and absence of real DNS work here. DNS/connection failures are audited below.
            self.client('closed', ip=address, payload='GET / HTTP/1.0\r\n\r\n')
            check(len(self.events(domain=domain)) == before, 'BLOCK queried real DNS')
        self.case('mapped TCP BLOCK closes before real DNS resolution', blocked)
        self.case('mapped destination override reaches configured origin',
                  lambda: self.request('fake-override' + SUFFIX, self.fake('fake-override' + SUFFIX),
                                       expected='HTTP_OVERRIDE_E2E', auditRoute=self.route_ids['fake-override'], auditFinal=c['override']))

        def upstream_proxy():
            domain = 'fake-socks' + SUFFIX
            self.request(domain, self.fake(domain), expected='FAKE_IP_SOCKS_DOMAIN_E2E',
                         auditRoute=self.route_ids['fake-socks'], auditFinal='127.0.0.1')
            events = self.events('socks', domain)
            check(len(events) == 1 and events[0]['atyp'] == 3 and events[0]['port'] == c['http'],
                  'SOCKS upstream did not receive domain and original port')
            check(not self.events(domain=domain), 'upstream proxy resolved business domain locally')
        self.case('SOCKS upstream receives restored domain and no local business DNS lookup', upstream_proxy)

        def dns_policies():
            for qtype in (1, 28):
                answer = self.client('dns', domain='dns-block' + SUFFIX, qtype=qtype)
                check(answer == {'rcode': 5, 'records': []}, 'DNS BLOCK must return REFUSED')
            answer = self.client('dns', domain='dns-rewrite' + SUFFIX)
            check(answer['records'][0]['address'] == c['origin'] and answer['records'][0]['ttl'] == 60, 'rewrite was faked')
            check(self.client('dns', domain='dns-rewrite' + SUFFIX, qtype=28)['records'] == [], 'IPv4 rewrite emitted AAAA')
            check(not self.events(domain='dns-rewrite' + SUFFIX) and not self.events(domain='dns-block' + SUFFIX),
                  'DNS BLOCK/rewrite queried upstream')
            txt = self.client('dns', domain='txt' + SUFFIX, qtype=16)
            check(txt['rcode'] == 0 and txt['records'][0]['type'] == 16, 'non-address question was faked')
            check(len(self.events(domain='txt' + SUFFIX)) == 1, 'TXT did not go to real upstream')
        self.case('DNS BLOCK and rewrite take priority; TXT preserves real forwarding', dns_policies)

        def cache():
            domain = 'cache' + SUFFIX
            address = self.fake(domain)
            self.client('http', ip=address)
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 1, 'warm answer cache queried upstream')
            time.sleep(4.3)
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 2, 'expired cache was reused')
        self.case('real A cache skips upstream until TTL expires', cache)

        def concurrency():
            domain = 'concurrent' + SUFFIX
            with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
                addresses = list(pool.map(lambda _: self.fake(domain), range(12)))
            check(len(set(addresses)) == 1, 'concurrent issuance allocated duplicate mappings')
            with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
                list(pool.map(lambda _: self.client('http', ip=addresses[0]), range(12)))
            check(len(self.events(domain=domain)) == 1, 'concurrent cache misses were not merged')
        self.case('12 concurrent DNS issuances and TCP misses share mapping and one real query', concurrency)

        def zero_ttl():
            domain = 'zero' + SUFFIX
            address = self.fake(domain)
            for _ in range(2):
                self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 2, 'TTL=0 cached')
        self.case('TTL=0 real answers are never cached', zero_ttl)

        def cname():
            domain = 'cname' + SUFFIX
            address = self.fake(domain)
            self.client('http', ip=address)
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 1, 'CNAME warm cache missed')
            time.sleep(2.3)
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 2, 'CNAME shortest TTL ignored')
        self.case('CNAME final A connects and cache uses shortest chain TTL', cname)

        for label in ('nxdomain', 'nodata', 'servfail', 'poison', 'timeout'):
            def failure(label=label):
                domain = label + SUFFIX
                address = self.fake(domain)
                for _ in range(2):
                    self.request(domain, address, 'closed', payload='GET / HTTP/1.0\r\n\r\n',
                                 auditStatus=500, auditFinal=None)
                count = len(self.events(domain=domain))
                check(count == (2 if label == 'nodata' else 1 if label == 'nxdomain' else 2),
                      f'{label} cache/query count mismatch: {count}')
                if label in ('nxdomain', 'nodata'):
                    time.sleep(2.3)
                    self.client('closed', ip=address, payload='GET / HTTP/1.0\r\n\r\n')
                    check(len(self.events(domain=domain)) > count, 'negative TTL did not expire')
                check(self.client('http', ip=fake)['body'] == 'HTTP_DIRECT_E2E', 'healthy traffic failed after DNS error')
            self.case('real DNS ' + label + ': bounded failure, correct caching, subsequent traffic works', failure)

        def truncation():
            domain = 'truncated' + SUFFIX
            self.client('http', ip=self.fake(domain))
            transports = [row['transport'] for row in self.events(domain=domain)]
            check(transports == ['udp', 'tcp'], 'TC did not retry real DNS over TCP: ' + repr(transports))
        self.case('truncated UDP answer retries TCP before connecting', truncation)

        def cancellation():
            domain = 'cancel' + SUFFIX
            address = self.fake(domain)
            self.client('cancel', ip=address)
            time.sleep(.8)
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 2, 'cancelled query cached or left in-flight entry')
        self.case('client close during DNS resolution releases query and next request succeeds', cancellation)

        def refresh():
            domain = 'refresh' + SUFFIX
            address = self.fake(domain)
            self.client('http', ip=address)
            payload = self.inbound_payload(self.dns)
            payload['notes'] = 'E2E config refresh invalidates real cache but preserves Fake IP'
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            check(self.fake(domain) == address, 'config refresh lost Fake IP map')
            self.client('http', ip=address)
            check(len(self.events(domain=domain)) == 2, 'real cache reused previous config version')
        self.case('config refresh keeps Fake IP map and invalidates previous real DNS cache view', refresh)

        def real_policy_change():
            domain = 'fake-direct' + SUFFIX
            blocked = self.route('outbound-real-block', 'BLOCK')
            route = self.api('/api/routes/' + str(blocked))
            route['rules'][0]['value'] = domain
            self.api('/api/routes/' + str(blocked), route, 'PUT')
            payload = self.inbound_payload(self.dns)
            original = payload['inboundRouteBindings'][0]['routeIds'][:]
            payload['inboundRouteBindings'][0]['routeIds'].insert(0, blocked)
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            before = len(self.events(domain=domain))
            self.client('closed', ip=fake, payload='GET / HTTP/1.0\r\n\r\n')
            check(len(self.events(domain=domain)) == before, 'OUTBOUND_REAL DNS BLOCK bypassed')
            rewrite = self.route('outbound-real-rewrite', 'DNS_REWRITING', outboundProxyHost=c['origin'])
            route = self.api('/api/routes/' + str(rewrite))
            route['rules'][0]['value'] = domain
            self.api('/api/routes/' + str(rewrite), route, 'PUT')
            payload['inboundRouteBindings'][0]['routeIds'] = [rewrite] + original
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            self.client('http', ip=fake)
            check(len(self.events(domain=domain)) == before, 'OUTBOUND_REAL rewrite queried upstream')
            payload['inboundRouteBindings'][0]['routeIds'] = original
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
        self.case('existing Fake IP DIRECT honors changed real DNS BLOCK and rewrite rules', real_policy_change)

        def device_identity():
            domain = 'device' + SUFFIX
            address = self.fake(domain)
            user = self.api('/api/users', {'username': 'e2e-fake-device', 'ipAddress': c['client'], 'status': 1})
            tcp_route = self.route('device', 'DIRECT')
            dns_route = self.route('device-dns', 'DNS_REWRITING', outboundProxyHost=c['origin'])
            route = self.api('/api/routes/' + str(dns_route))
            route['rules'][0]['value'] = domain
            self.api('/api/routes/' + str(dns_route), route, 'PUT')
            old_tp, old_dns = self.inbound_payload(self.tp), self.inbound_payload(self.dns)
            payload = self.inbound_payload(self.tp)
            payload['inboundRouteBindings'].append({'userIds': [user['id']], 'routeIds': [tcp_route]})
            self.tp = self.apply('/api/inbounds/' + str(self.tp['id']), payload)
            payload = self.inbound_payload(self.dns)
            payload['inboundRouteBindings'].append({'userIds': [user['id']], 'routeIds': [dns_route]})
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            before = len(self.events(domain=domain))
            answer = self.client('dns', domain=domain)
            check(answer['records'][0]['address'] == c['origin'], 'client DNS did not use device rewrite')
            result = self.client('http', ip=address)
            check(len(self.events(domain=domain)) == before, 'OUTBOUND_REAL did not use device rewrite')

            def visible():
                rows = self.api('/api/logs/access?' + urllib.parse.urlencode({
                    'workerId': c['worker'], 'clientIp': c['client'], 'originalTargetHost': domain,
                    'pageSize': 100}))['items']
                for row in rows:
                    if row['userId'] != user['id'] or row['routePolicyId'] != tcp_route:
                        continue
                    detail = self.api('/api/logs/access/' + urllib.parse.quote(row['requestId'], safe=''))
                    if detail['clientPort'] == result['sourcePort']:
                        check(detail['dnsRouteId'] == dns_route and detail['status'] == 200,
                              'device TCP real DNS identity differs from client DNS identity')
                        return True
                return False
            self.wait('device-specific DNS/TCP audit', visible)
            self.tp = self.apply('/api/inbounds/' + str(self.tp['id']), old_tp)
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), old_dns)
        self.case('source-IP device uses same identity for client DNS, domain TCP route and real DNS rewrite', device_identity)

        def validation():
            current = self.status()['appliedConfigHash']
            for row in (self.dns, self.tp):
                payload = self.inbound_payload(row)
                payload['name'] += '-duplicate'
                payload['port'] += 100
                self.api('/api/inbounds', payload, expected=400)
            payload = self.inbound_payload(self.tp)
            payload['status'] = 0
            self.api('/api/inbounds/' + str(self.tp['id']), payload, 'PUT', expected=400)
            payload = self.inbound_payload(self.tp)
            payload['fakeIpEnabled'] = True
            self.api('/api/inbounds/' + str(self.tp['id']), payload, 'PUT', expected=400)
            payload = self.inbound_payload(self.dns)
            payload['fallbackRouteId'] = self.direct
            self.api('/api/inbounds/' + str(self.dns['id']), payload, 'PUT', expected=400)
            geo = self.api('/api/routes', {'name': 'e2e-invalid-geo', 'policy': 'DIRECT', 'status': 1,
                'rules': [{'conditionType': 'GEO', 'op': 'IN', 'value': 'CN'}]})
            payload = self.inbound_payload(self.tp)
            payload['inboundRouteBindings'][0]['routeIds'].append(geo['id'])
            self.api('/api/inbounds/' + str(self.tp['id']), payload, 'PUT', expected=400)
            for host, port in [('dns-upstream' + SUFFIX, c['upstream']), ('198.18.1.1', c['upstream']),
                               ('127.0.0.1', c['dns'])]:
                route = self.route('invalid-' + str(port) + '-' + host.replace('.', '-'), 'OUTBOUND_PROXY',
                                   outboundProxyType='DNS_SERVER', outboundProxyHost=host, outboundProxyPort=port)
                payload = self.inbound_payload(self.dns)
                payload['fallbackRouteId'] = route
                self.api('/api/inbounds/' + str(self.dns['id']), payload, 'PUT', expected=400)
            check(self.status()['appliedConfigHash'] == current, 'rejected inbound updates changed config')
            self.client('http', ip=fake)
        self.case('API rejects duplicate listeners, missing transparent inbound and recursive/invalid DNS upstreams', validation)
        self.case('success/failure audit has original Fake IP/port, domain/source, route, final IP, reason and snapshot',
                  lambda: self.wait('all successful TCP audit records', self.audit_visible))

        def metrics():
            def visible():
                m = self.status().get('metrics') or {}
                return (m.get('fakeIpAllocations', 0) >= 10 and m.get('fakeIpReverseMisses', 0) >= 6
                        and m.get('rejectedPoolAddresses', 0) >= 2
                        and sum((m.get('realDnsCacheHitsByRoute') or {}).values()) >= 1
                        and sum((m.get('realDnsTimeoutsByRoute') or {}).values()) >= 2
                        and sum((m.get('realDnsTruncationsByRoute') or {}).values()) >= 1)
            self.wait('Fake IP/cache/timeout/truncation/pool rejection metrics', visible)
            self.observed_metrics = self.status()['metrics']
        self.case('worker status exposes allocation, reverse miss, cache, timeout, truncation and pool rejection metrics', metrics)

        def disable():
            payload = self.inbound_payload(self.dns)
            payload['fakeIpEnabled'] = False
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            answer = self.client('dns', domain='plain-after-off' + SUFFIX)
            check(answer['records'][0]['address'] == c['origin'] and answer['records'][0]['ttl'] == 4,
                  'disabled Fake IP did not forward real A')
            answer = self.client('dns', domain='plain-after-off' + SUFFIX, qtype=28)
            check(answer['records'][0]['address'] == '::1', 'disabled Fake IP still suppressed AAAA')
            self.client('http', ip=fake)
            self.client('http', ip=UNKNOWN, host=direct)
            self.client('closed', ip=UNKNOWN)
        self.case('switch off restores real A/AAAA; old mapping and fallback stay guarded', disable)

        def restart():
            # Fake IP is still off: issue no addresses before checking the old lease.
            result = subprocess.run(['docker', 'restart', c['container']], capture_output=True, text=True, timeout=45)
            check(result.returncode == 0, 'worker restart failed: ' + result.stderr)
            self.wait('restarted worker listener', lambda: self.listener_alive())
            self.wait('restarted worker config', self.applied)
            self.client('closed', ip=fake, payload='GET / HTTP/1.0\r\n\r\n')
            self.client('http', ip=fake, host=direct)
            self.client('tls', ip=fake, host=direct, port=c['tls'], expected='TLS_DIRECT_E2E')
            self.client('closed', ip=fake)
            payload = self.inbound_payload(self.dns)
            payload['fakeIpEnabled'] = True
            self.dns = self.apply('/api/inbounds/' + str(self.dns['id']), payload)
            self.client('http', ip=self.fake(direct))
        self.case('worker restart drops mapping; old lease closes or recovers Host/SNI; re-enable works', restart)
        print(f'PASS: {len(self.cases)} Fake IP + TPROXY scenarios; report: {c["report"]}', flush=True)

    def listener_alive(self):
        try:
            with socket.create_connection(('127.0.0.1', self.tp['port']), timeout=.5):
                return True
        except OSError:
            return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('serve', 'client', 'run'))
    parser.add_argument('config', type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.action == 'serve':
        serve(config)
    elif args.action == 'client':
        print(json.dumps(probe(config, json.load(sys.stdin))))
    else:
        Suite(config, args.config).run()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: ' + str(error), file=sys.stderr, flush=True)
        sys.exit(1)
