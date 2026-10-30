#!/usr/bin/env python3
"""Probe Xray VLESS UDP command + flow over local TLS 1.3, with a real UDP echo target."""
import contextlib
import json
import os
from pathlib import Path
import socket
import ssl
import struct
import subprocess
import tempfile
import threading
import time
import uuid


def exact(client, length):
    data = b''
    while len(data) < length:
        chunk = client.recv(length - len(data))
        if not chunk:
            raise EOFError('server closed connection')
        data += chunk
    return data


def main():
    root = Path(__file__).resolve().parents[2]
    binary = Path(os.environ.get('XRAY_BIN', root / '.it-cache/xray/v26.3.27/Xray-linux-64/xray')).resolve()
    print(subprocess.check_output([str(binary), 'version'], text=True).splitlines()[0])
    with tempfile.TemporaryDirectory(prefix='home-proxy-xray-udp-') as directory, contextlib.ExitStack() as stack:
        path = Path(directory)
        subprocess.run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1',
                        '-subj', '/CN=localhost', '-keyout', str(path / 'key.pem'), '-out', str(path / 'cert.pem')],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        echo = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        echo.bind(('127.0.0.1', 0))
        echo.settimeout(.1)
        stopped = threading.Event()
        def serve():
            while not stopped.is_set():
                try:
                    data, peer = echo.recvfrom(65535)
                    echo.sendto(data, peer)
                except socket.timeout:
                    pass
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        identity = uuid.uuid4()
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1', 0))
            port = reserve.getsockname()[1]
        config = {'log': {'loglevel': 'warning'}, 'inbounds': [{'listen': '127.0.0.1', 'port': port,
            'protocol': 'vless', 'settings': {'clients': [{'id': str(identity), 'flow': 'xtls-rprx-vision'}],
            'decryption': 'none'}, 'streamSettings': {'network': 'raw', 'security': 'tls', 'tlsSettings': {
            'minVersion': '1.3', 'certificates': [{'certificateFile': str(path / 'cert.pem'),
                                                'keyFile': str(path / 'key.pem')}]}}}],
            'outbounds': [{'protocol': 'freedom'}]}
        (path / 'config.json').write_text(json.dumps(config))
        with open(path / 'xray.log', 'w+') as log:
            process = subprocess.Popen([str(binary), 'run', '-c', str(path / 'config.json')], stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 5
                while True:
                    try:
                        socket.create_connection(('127.0.0.1', port), timeout=.1).close()
                        break
                    except OSError:
                        if time.monotonic() > deadline or process.poll() is not None:
                            raise RuntimeError('Xray failed to start')
                        time.sleep(.05)
                tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                tls.check_hostname = False
                tls.verify_mode = ssl.CERT_NONE
                tls.minimum_version = ssl.TLSVersion.TLSv1_3
                for flow in ('xtls-rprx-vision', ''):
                    with socket.create_connection(('127.0.0.1', port), timeout=3) as tcp:
                        with tls.wrap_socket(tcp, server_hostname='localhost') as client:
                            addons = b'\x0a' + bytes([len(flow)]) + flow.encode() if flow else b''
                            header = b'\x00' + identity.bytes + bytes([len(addons)]) + addons + b'\x02' + \
                                     struct.pack('!H', echo.getsockname()[1]) + b'\x01\x7f\x00\x00\x01'
                            payload = b'vless-udp-command-probe'
                            client.sendall(header + struct.pack('!H', len(payload)) + payload)
                            if flow:
                                try:
                                    received = client.recv(1)
                                    assert received == b'', received
                                except ConnectionResetError:
                                    pass
                                print('PASS request flow=xtls-rprx-vision + UDP: rejected')
                            else:
                                assert exact(client, 2) == b'\x00\x00'
                                assert exact(client, struct.unpack('!H', exact(client, 2))[0]) == payload
                                print('PASS request flow=empty + UDP, account flow=vision: real echo')
                time.sleep(.1)
                log.seek(0)
                output = log.read()
                assert "doesn't support UDP" in output, output
                print("PASS server diagnostic: xtls-rprx-vision doesn't support UDP")
            finally:
                process.terminate()
                process.wait(timeout=5)
                stopped.set()
                thread.join(timeout=1)


if __name__ == '__main__':
    main()
