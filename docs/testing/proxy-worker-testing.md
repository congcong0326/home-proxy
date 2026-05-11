# proxy-worker Testing Guide

This document tracks the proxy-worker test strategy and the core regression matrix.

## Test Layers

- Unit tests: in-memory tests for handlers, strategies, factories, and utility code. Prefer Netty `EmbeddedChannel`; do not bind real ports.
- Light integration tests: local Netty client/server paths that bind ephemeral ports and exercise protocol handshakes.
- End-to-end tests: system-level validation for real deployment concerns such as TProxy, iptables, upstream DNS, DoT, and real TLS handshakes.

## Core Regression Matrix

| Area | Risk | Unit Coverage | Status |
| --- | --- | --- | --- |
| SOCKS5 over TLS pipeline | TLS handler accidentally skipped or inserted after protocol handlers | `SocksServerInitializerTest` | Covered |
| SOCKS5 auth negotiation | Clients bypass auth or get wrong negotiation response | `SocksServerHandlerTest` | Covered |
| SOCKS5 CONNECT normalization | Target host/port or user context lost before routing | `SocksServerHandlerTest` | Covered |
| Transparent HTTP sniff | HTTP Host parsing regression sends traffic to IP fallback | `ProtocolDetectHandlerTest` | Covered |
| Transparent TLS SNI sniff | TLS traffic loses domain routing context | `ProtocolDetectHandlerTest` | Covered |
| Transparent unknown payload fallback | Non-HTTP/TLS traffic must still forward to original IP | `ProtocolDetectHandlerTest` | Covered |
| Transparent tunnel request | Device IP user mapping or first-packet retention breaks | `TransparentServerHandlerTest` | Covered |
| DNS UDP query normalization | DNS id, qName, qType, client, or bytes-in context lost | `UdpDnsQueryHandlerTest` | Covered |
| DNS answer IP extraction | DNS logs lose A/AAAA answer IPs or mutate buffers | `DnsMessageUtilTest` | Covered |
| DNS rewrite | Invalid rewrite config or AAAA query gets incorrect answer | `DnsRewritingProtocolStrategyTest` | Covered |
| DNS forward id mapping | Concurrent DNS responses return to wrong client id | `DnsForwardProtocolStrategyTest` | Covered |
| DNS question validation | Cached or mismatched upstream responses cause request mix-up | `DnsForwardProtocolStrategyTest` | Covered |

## Maintenance Rules

- When changing protocol handlers under `protocol/socks`, `protocol/transparent`, or `protocol/dns`, update this matrix in the same change.
- When changing DNS pending-id mapping, question validation, rewrite behavior, or answer logging, add or update a unit test before changing production code.
- When changing TLS initialization, keep unit coverage for handler ordering and add a light integration test if the handshake behavior changes.
- When adding a new inbound protocol, add at least one normalization test that produces a `ProxyTunnelRequest` with user, target, and first-packet behavior.
- Keep unit tests deterministic: no real upstream DNS, no real proxy ports, no iptables/TProxy dependency.

## Integration Test Backlog

- SOCKS5 over TLS: bind an ephemeral worker port, complete a real TLS handshake, authenticate, and issue CONNECT.
- Transparent proxy: validate TProxy/iptables behavior in a privileged Linux test environment.
- DNS UDP forward: bind local UDP DNS worker and fake upstream, then verify concurrent query id mapping.
- DoT upstream: fake TLS DNS upstream and verify length-prefixed DNS request/response flow.
