package org.congcong.proxyworker.dns;

import org.congcong.proxyworker.config.InboundConfig;

/** The DNS identity and configuration view captured at request start. */
public record DnsQueryContext(String clientIp, InboundConfig dnsInbound, String configVersion) {}
