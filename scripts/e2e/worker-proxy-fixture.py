#!/usr/bin/env python3
"""Small dependency-free HTTP target, SOCKS5 upstream, and SOCKS5 client for E2E."""

from __future__ import annotations

import argparse
import http.server
import select
import socket
import socketserver
import struct
import sys
from typing import Tuple


class _ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class _ResponseHandler(http.server.BaseHTTPRequestHandler):
    response_body = b""

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(self.response_body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(self.response_body)

    do_POST = do_GET

    def log_message(self, *_args: object) -> None:
        return


class _Socks5Handler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        client = self.request
        client.settimeout(8)
        if _read_exact(client, 1) != b"\x05":
            return
        method_count = _read_exact(client, 1)[0]
        _read_exact(client, method_count)
        client.sendall(b"\x05\x00")

        request_header = _read_exact(client, 4)
        if request_header[:3] != b"\x05\x01\x00":
            return
        atyp = request_header[3]
        if atyp == 1:
            host = socket.inet_ntoa(_read_exact(client, 4))
        elif atyp == 3:
            host = _read_exact(client, 1)[0]
            host = _read_exact(client, host).decode("idna")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, _read_exact(client, 16))
        else:
            return
        port = struct.unpack("!H", _read_exact(client, 2))[0]

        try:
            upstream = socket.create_connection((host, port), timeout=8)
        except OSError:
            client.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
            return

        with upstream:
            client.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
            _relay(client, upstream)


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise OSError("unexpected EOF")
        chunks.extend(chunk)
    return bytes(chunks)


def _relay(left: socket.socket, right: socket.socket) -> None:
    left.setblocking(False)
    right.setblocking(False)
    while True:
        readable, _, _ = select.select([left, right], [], [], 15)
        if not readable:
            return
        for source in readable:
            data = source.recv(64 * 1024)
            if not data:
                return
            destination = right if source is left else left
            destination.sendall(data)


def _run_http(args: argparse.Namespace) -> None:
    handler = type("ResponseHandler", (_ResponseHandler,), {"response_body": args.body.encode()})
    with _ThreadingTCPServer((args.bind, args.port), handler) as server:
        server.serve_forever()


def _run_socks(args: argparse.Namespace) -> None:
    with _ThreadingTCPServer((args.bind, args.port), _Socks5Handler) as server:
        server.serve_forever()


def _run_tcp_response(args: argparse.Namespace) -> None:
    class Handler(socketserver.BaseRequestHandler):
        def handle(self) -> None:
            self.request.settimeout(8)
            self.request.recv(64 * 1024)
            self.request.sendall(args.body.encode())
    with _ThreadingTCPServer((args.bind, args.port), Handler) as server:
        server.serve_forever()


def _tls_client(args: argparse.Namespace) -> None:
    host = args.sni.encode("idna")
    # TLS 1.2 ClientHello carrying a server_name extension.
    name = b"\x00" + bytes([len(host)]) + host
    ext = b"\x00\x00" + struct.pack("!H", len(name) + 2) + struct.pack("!H", len(name)) + name
    hello = b"\x03\x03" + b"\x00" * 32 + b"\x00" + b"\x00\x02\x13\x01" + b"\x01\x00" + struct.pack("!H", len(ext)) + ext
    record = b"\x16\x03\x03" + struct.pack("!H", len(hello) + 4) + b"\x01" + len(hello).to_bytes(3, "big") + hello
    with socket.create_connection((args.proxy_host, args.proxy_port), timeout=8) as sock:
        sock.settimeout(8)
        sock.sendall(record)
        response = _recv_until_close(sock)
    sys.stdout.write(response.decode())


def _recv_until_close(sock: socket.socket) -> bytes:
    chunks = []
    while True:
        chunk = sock.recv(64 * 1024)
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)


def _socks_client(args: argparse.Namespace) -> None:
    with socket.create_connection((args.proxy_host, args.proxy_port), timeout=8) as sock:
        sock.settimeout(8)
        username = args.username.encode()
        password = args.password.encode()
        sock.sendall(b"\x05\x01\x02")
        if _read_exact(sock, 2) != b"\x05\x02":
            raise OSError("SOCKS5 password negotiation failed")
        sock.sendall(b"\x01" + bytes([len(username)]) + username + bytes([len(password)]) + password)
        if _read_exact(sock, 2) != b"\x01\x00":
            raise OSError("SOCKS5 authentication failed")

        target = args.target_host.encode("idna")
        if _is_ipv4(args.target_host):
            request = b"\x05\x01\x00\x01" + socket.inet_aton(args.target_host)
        else:
            request = b"\x05\x01\x00\x03" + bytes([len(target)]) + target
        request += struct.pack("!H", args.target_port)
        sock.sendall(request)
        response = _read_exact(sock, 4)
        if response[1] != 0:
            raise OSError(f"SOCKS5 CONNECT failed with status {response[1]}")
        _skip_bound_address(sock, response[3])

        host_header = args.target_host
        request = (
            f"GET {args.path} HTTP/1.1\r\nHost: {host_header}\r\n"
            "Connection: close\r\n\r\n"
        ).encode("ascii")
        sock.sendall(request)
        response = _recv_until_close(sock)
        _, separator, body = response.partition(b"\r\n\r\n")
        if not separator:
            raise OSError("invalid HTTP response")
        sys.stdout.write(body.decode())


def _skip_bound_address(sock: socket.socket, atyp: int) -> None:
    if atyp == 1:
        _read_exact(sock, 6)
    elif atyp == 3:
        length = _read_exact(sock, 1)[0]
        _read_exact(sock, length + 2)
    elif atyp == 4:
        _read_exact(sock, 18)
    else:
        raise OSError("unknown SOCKS5 bound address type")


def _is_ipv4(value: str) -> bool:
    try:
        socket.inet_aton(value)
        return value.count(".") == 3
    except OSError:
        return False


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="mode", required=True)

    http = subparsers.add_parser("http")
    http.add_argument("--bind", required=True)
    http.add_argument("--port", type=int, required=True)
    http.add_argument("--body", required=True)
    http.set_defaults(run=_run_http)

    socks = subparsers.add_parser("socks")
    socks.add_argument("--bind", required=True)
    socks.add_argument("--port", type=int, required=True)
    socks.set_defaults(run=_run_socks)

    tcp = subparsers.add_parser("tcp-response")
    tcp.add_argument("--bind", required=True)
    tcp.add_argument("--port", type=int, required=True)
    tcp.add_argument("--body", required=True)
    tcp.set_defaults(run=_run_tcp_response)

    tls = subparsers.add_parser("tls-client")
    tls.add_argument("--proxy-host", default="127.0.0.1")
    tls.add_argument("--proxy-port", type=int, required=True)
    tls.add_argument("--sni", required=True)
    tls.set_defaults(run=_tls_client)

    client = subparsers.add_parser("client")
    client.add_argument("--proxy-host", default="127.0.0.1")
    client.add_argument("--proxy-port", type=int, required=True)
    client.add_argument("--target-host", required=True)
    client.add_argument("--target-port", type=int, required=True)
    client.add_argument("--path", required=True)
    client.add_argument("--username", required=True)
    client.add_argument("--password", required=True)
    client.set_defaults(run=_socks_client)
    return parser


if __name__ == "__main__":
    arguments = _parser().parse_args()
    try:
        arguments.run(arguments)
    except (OSError, TimeoutError) as error:
        print(f"worker-proxy fixture: {error}", file=sys.stderr)
        sys.exit(1)
