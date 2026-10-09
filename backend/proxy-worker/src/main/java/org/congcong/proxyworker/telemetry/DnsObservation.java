package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.dns.DnsResult;
import org.congcong.common.enums.RoutePolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stage A collects the A/AAAA client-answer path; other DNS paths remain uncovered. */
public final class DnsObservation {
    private final long startedNanos = System.nanoTime();
    private final DnsQueryLogV1 record = new DnsQueryLogV1();
    private final AtomicBoolean completed = new AtomicBoolean();

    public DnsObservation(ProxyTunnelRequest request, DnsProxyContext dns) {
        record.setQueryId(UUID.randomUUID().toString()); record.setRecordId(record.getQueryId());
        record.setQueryOrigin("CLIENT"); record.setQname(dns.getQName());
        record.setQtype(dns.getQType().name()); record.setQclass("IN"); record.setTransport("UDP");
        record.setInboundId(request.getInboundConfig().getId()); record.setInboundName(request.getInboundConfig().getName());
        record.setConfigVersion(request.getInboundConfig().getConfigVersion());
        record.setClientIp(dns.getClient().getAddress().getHostAddress()); record.setClientPort(dns.getClient().getPort());
        record.setTransportPeerIp(record.getClientIp()); record.setTransportPeerPort(record.getClientPort());
        record.setClientIpSource("TRANSPORT_PEER"); record.setCacheStatus("UNKNOWN");
        if (request.getUser() != null) { record.setUserId(request.getUser().getId()); record.setUsername(request.getUser().getUsername()); }
    }
    public void complete(ProxyTunnelRequest request, DnsResult result, Throwable failure, int rcode,
            List<String> answers, boolean sent, long responseBytes) {
        if (!completed.compareAndSet(false, true)) return;
        record.setOccurredAt(Instant.now());
        record.setDurationUs(Math.max(0, (System.nanoTime() - startedNanos) / 1000));
        record.setRcode(rcode); record.setAnswerIps(answers); record.setResponseSent(sent);
        record.setResponseBytes(responseBytes);
        if (request.getProxyContext() != null) record.setRequestBytes(request.getProxyContext().getBytesIn());
        boolean block = request.getRouteConfig() != null && request.getRouteConfig().getPolicy() == RoutePolicy.BLOCK;
        boolean rewrite = request.getRouteConfig() != null && request.getRouteConfig().getPolicy() == RoutePolicy.DNS_REWRITING;
        record.setOutcome(block ? "BLOCKED" : failure != null || rcode == 1 || rcode == 2 ? "ERROR" : "ANSWERED");
        record.setAnswerMode(block ? "BLOCK" : rewrite ? "REWRITE" : result != null && result.fake() ? "FAKE_IP" : "REAL");
        if (result != null) { record.setTtlSeconds(result.ttlSeconds()); record.setDnsRouteId(result.routeId()); }
        if (answers.isEmpty()) record.setEmptyAnswerReason(block ? "POLICY_BLOCK" : "ERROR".equals(record.getOutcome()) ? "ERROR"
                : "FAKE_IP".equals(record.getAnswerMode()) && "AAAA".equals(record.getQtype()) ? "IPV6_SUPPRESSED" : "NODATA");
        if ("FAKE_IP".equals(record.getAnswerMode()) || rewrite || block) record.setCacheStatus("NOT_APPLICABLE");
        if (failure != null) record.setErrorCode("DNS_RESOLUTION_FAILED");
        if (!sent) record.setErrorCode("DNS_RESPONSE_WRITE_FAILED");
        TelemetryPublisher.getInstance().publish(record);
    }
}
