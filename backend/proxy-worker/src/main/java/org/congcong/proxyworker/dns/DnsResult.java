package org.congcong.proxyworker.dns;

import io.netty.handler.codec.dns.DnsResponseCode;

import java.util.List;

public record DnsResult(DnsResponseCode code, List<String> addresses, int ttlSeconds,
                        int negativeTtlSeconds, boolean fake, Long routeId) {
    public DnsResult {
        addresses = List.copyOf(addresses);
    }

    public static DnsResult blocked(Long routeId) {
        return new DnsResult(DnsResponseCode.REFUSED, List.of(), 0, 0, false, routeId);
    }
}
