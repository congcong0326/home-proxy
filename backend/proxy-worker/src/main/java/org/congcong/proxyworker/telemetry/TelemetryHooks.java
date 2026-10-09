package org.congcong.proxyworker.telemetry;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.congcong.common.telemetry.*;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

/** Additive hooks; no dependence on the legacy log-reporting flag or legacy counters. */
public final class TelemetryHooks {
    private static final AttributeKey<SessionObservation> CONTEXT = AttributeKey.valueOf("telemetrySessionV1");
    private TelemetryHooks() { }
    public static void accept(Channel channel, InboundConfig inbound) {
        if (inbound == null || inbound.getProtocol() == ProtocolType.DNS_SERVER || !TelemetryPublisher.getInstance().enabled()) return;
        SessionObservation state = new SessionObservation(TelemetryPublisher.getInstance()::publish, TelemetryPublisher.getInstance().eventsEnabled());
        SessionLogV1 record = state.summary();
        record.setInboundId(inbound.getId());
        record.setInboundName(inbound.getName());
        record.setInboundProtocol(inbound.getProtocol().name());
        record.setConfigVersion(inbound.getConfigVersion());
        if (channel.remoteAddress() instanceof InetSocketAddress peer && peer.getAddress() != null) {
            record.setTransportPeerIp(peer.getAddress().getHostAddress());
            record.setTransportPeerPort(peer.getPort());
            record.setClientIp(record.getTransportPeerIp());
            record.setClientPort(peer.getPort());
            record.setClientIpSource("TRANSPORT_PEER");
        }
        channel.attr(CONTEXT).set(state);
        channel.closeFuture().addListener(ignored -> channel.eventLoop().execute(state::close));
    }
    public static SessionObservation state(Channel channel) { return channel == null ? null : channel.attr(CONTEXT).get(); }
    private static void run(Channel channel, java.util.function.Consumer<SessionObservation> action) {
        SessionObservation state = state(channel);
        if (state == null) return;
        Runnable safe = () -> { try { action.accept(state); } catch (Exception ignored) { } };
        if (channel.eventLoop().inEventLoop()) safe.run(); else channel.eventLoop().execute(safe);
    }
    public static void routing(Channel channel, ProxyTunnelRequest request, boolean fallback) {
        run(channel, state -> {
            SessionLogV1 record = state.summary();
            var route = request.getRouteConfig();
            var old = ChannelAttributes.getProxyContext(channel);
            record.setOriginalDstIp(request.getOriginalTargetIp());
            record.setOriginalDstPort(request.getOriginalTargetIp() == null ? null : request.getTargetPort());
            record.setLogicalTargetHost(request.getTargetHost());
            record.setLogicalTargetPort(request.getTargetPort());
            record.setTargetSource(request.getLogicalTargetSource() == org.congcong.proxyworker.server.tunnel.LogicalTargetSource.FAKE_IP
                    ? "FAKE_IP_MAPPING" : request.getLogicalTargetSource().name());
            boolean override = route.getPolicy() == RoutePolicy.DESTINATION_OVERRIDE
                    && (route.getOutboundProxyHost() != null || route.getOutboundProxyPort() != null);
            record.setDestinationOverrideApplied(override);
            record.setEffectiveTargetHost(override && route.getOutboundProxyHost() != null ? route.getOutboundProxyHost() : request.getTargetHost());
            record.setEffectiveTargetPort(override && route.getOutboundProxyPort() != null ? route.getOutboundProxyPort() : request.getTargetPort());
            record.setRouteId(route.getId()); record.setRouteName(route.getName());
            record.setMatchedRuleId(request.getTelemetryMatchedRuleId());
            record.setMatchedRuleSet(request.getTelemetryMatchedRuleSet());
            record.setOutboundNodeId(route.getId() == null ? null : "route:" + route.getId());
            record.setRouteAction(route.getPolicy().name());
            record.setRouteSelection(fallback ? "DEFAULT" : "RULE_MATCH");
            record.setOutboundMode(route.getPolicy().name());
            record.setOutboundProtocol(route.getPolicy() == RoutePolicy.OUTBOUND_PROXY && route.getOutboundProxyType() != null
                    ? route.getOutboundProxyType().name() : "TCP");
            if (old != null) { record.setUserId(old.getUserId()); record.setUsername(old.getUserName()); }
            if (record.getIdentitySource() != IdentitySource.ACCOUNT_AUTH) {
                record.setIdentitySource(request.getUser() == null ? IdentitySource.UNKNOWN
                        : request.getInboundConfig().getAnonymousUser() != null
                        && request.getUser().getId().equals(request.getInboundConfig().getAnonymousUser().getId())
                        ? IdentitySource.ANONYMOUS : IdentitySource.DEVICE_IP);
            }
            if (request.getLogicalTargetSource() != org.congcong.proxyworker.server.tunnel.LogicalTargetSource.INBOUND_REQUEST) {
                state.event("TARGET_RECOVERY_RESULT", "TARGET_RECOVERY", "RECOVERED", null);
            }
        });
    }
    public static void beginConnect(Channel channel) { run(channel, SessionObservation::beginConnect); }
    public static void connected(Channel inbound, Channel outbound, ProxyTunnelRequest request) {
        run(inbound, state -> {
            var record = state.summary();
            if (outbound != null && outbound.remoteAddress() instanceof InetSocketAddress peer && peer.getAddress() != null) {
                record.setOutboundPeerIp(peer.getAddress().getHostAddress());
                record.setOutboundPeerPort(peer.getPort());
                if (request.getRouteConfig().getPolicy() != RoutePolicy.OUTBOUND_PROXY) record.setSelectedTargetIp(record.getOutboundPeerIp());
            }
            boolean proxy = request.getRouteConfig().getPolicy() == RoutePolicy.OUTBOUND_PROXY;
            record.setChannelReadyBasis(proxy ? "PROXY_CONNECTOR_READY" : "DIRECT_TCP_CONNECTED");
            record.setTargetConfirmationLevel(proxy ? "UPSTREAM_PROTOCOL_ONLY" : "LOCAL_TCP_CONNECTED");
            // Connector readiness is not universally a remote protocol ACK; leave handshake outcome unknown.
            TrafficClassifierV1.classify(record);
            state.connected();
        });
    }
    public static void connectFailed(Channel channel, Throwable error) {
        run(channel, state -> {
            boolean blocked = error instanceof org.congcong.proxyworker.outbound.block.BlockOutboundConnector.BlockedByPolicyException
                    || error instanceof org.congcong.proxyworker.security.PrivateDestinationBlockedException;
            state.connectFailed("OUTBOUND_CONNECT", blocked ? "POLICY_BLOCKED" : "CONNECT_FAILED", blocked);
        });
    }
    public static void error(Channel channel, String stage, String code) { run(channel, state -> state.error(stage, code)); }
    public static void writeStarted(Channel channel) { run(channel, SessionObservation::writeStarted); }
    public static void writeCompleted(Channel channel, boolean upload, int bytes, boolean success) {
        run(channel, state -> state.writeCompleted(upload, bytes, success));
    }
    public static void authentication(Channel channel, String method, String kind, String attemptedUsername,
            UserConfig verified, AuthOutcome outcome, String reason, Long durationUs) {
        if (!TelemetryPublisher.getInstance().authEnabled()) return;
        run(channel, state -> {
            var context = state.summary();
            CredentialAuthLogV1 record = new CredentialAuthLogV1();
            record.setAuthAttemptId(UUID.randomUUID().toString()); record.setRecordId(record.getAuthAttemptId());
            record.setConnectionId(context.getConnectionId()); record.setOccurredAt(Instant.now());
            record.setInboundId(context.getInboundId()); record.setInboundName(context.getInboundName());
            record.setInboundProtocol(context.getInboundProtocol()); record.setConfigVersion(context.getConfigVersion());
            record.setClientIp(context.getClientIp()); record.setClientPort(context.getClientPort());
            record.setTransportPeerIp(context.getTransportPeerIp()); record.setTransportPeerPort(context.getTransportPeerPort());
            record.setClientIpSource(context.getClientIpSource());
            record.setAuthMethod(method); record.setAuthEventKind(kind); record.setAuthOutcome(outcome);
            record.setReasonCode(reason); record.setAuthDurationUs(durationUs);
            if (attemptedUsername != null) {
                String sanitized = attemptedUsername.replaceAll("[\\p{Cntrl}]", "");
                record.setAttemptedUsername(sanitized.substring(0, Math.min(128, sanitized.length())));
            }
            if (outcome == AuthOutcome.SUCCESS && verified != null) {
                record.setUserId(verified.getId()); record.setVerifiedUsername(verified.getUsername());
                context.setUserId(verified.getId()); context.setUsername(verified.getUsername());
                context.setIdentitySource(IdentitySource.ACCOUNT_AUTH);
            } else if (outcome == AuthOutcome.FAILURE || outcome == AuthOutcome.DENIED || outcome == AuthOutcome.MALFORMED) {
                state.error("AUTH", reason);
            }
            TelemetryPublisher.getInstance().publish(record);
        });
    }
}
