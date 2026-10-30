package org.congcong.proxyworker.router;

import org.congcong.proxyworker.telemetry.TelemetryHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.*;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.dns.DnsRouteSelector;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.net.InetAddress;

@ChannelHandler.Sharable
@Slf4j
public class RouterService extends SimpleChannelInboundHandler<ProxyTunnelRequest> {

    private RouterService() {

    }

    public static RouterService getInstance() {
        return RouterService.Holder.INSTANCE;
    }

    private static class Holder {
        private static final RouterService INSTANCE = new RouterService();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        TelemetryHooks.requestReady(channelHandlerContext.channel());
        TelemetryHooks.beginStage(channelHandlerContext.channel(), org.congcong.proxyworker.telemetry.SessionObservation.Stage.ROUTE_SELECTION);
        try {
            ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(channelHandlerContext.channel(), proxyTunnelRequest);
            InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
            proxyContext.setConfigVersion(inboundConfig.getConfigVersion());
            proxyContext.setLogicalTargetSource(proxyTunnelRequest.getLogicalTargetSource().name());
            if (proxyTunnelRequest.getProtocolType() != ProtocolType.DNS_SERVER) {
                proxyTunnelRequest.setDnsInboundConfig(
                        channelHandlerContext.channel().attr(ChannelAttributes.DNS_INBOUND_CONFIG).get());
            }
            // TLS SNI is an unauthenticated inbound: its routing identity is always
            // the anonymous user configured on the inbound. Keep this decision at
            // the routing boundary so bindings for that user are consistently used.
            Long routingUserId = proxyTunnelRequest.getProtocolType() == ProtocolType.TLS_SNI_TCP
                    && inboundConfig.getAnonymousUser() != null
                    ? inboundConfig.getAnonymousUser().getId()
                    : proxyTunnelRequest.getUser() == null ? null : proxyTunnelRequest.getUser().getId();
            String targetHost = proxyTunnelRequest.getTargetHost();
            RouteConfig matchedRoute;
            boolean telemetryFallback;
            if (proxyTunnelRequest.getProtocolType() == ProtocolType.DNS_SERVER) {
                String clientIp = proxyContext.getClientIp();
                matchedRoute = DnsRouteSelector.select(inboundConfig, clientIp, targetHost).route();
                telemetryFallback = matchedRoute == null;
            } else {
                RouteSelector.Selection selection = new RouteSelector().select(proxyTunnelRequest, inboundConfig, routingUserId);
                matchedRoute = selection.route();
                telemetryFallback = selection.fallback();
            }
            if (matchedRoute == null) {
                matchedRoute = fallbackRoute(inboundConfig, channelHandlerContext, proxyTunnelRequest);
                if (matchedRoute == null) return;
            }
            ProxyContextFillUtil.proxyContextRouteFill(matchedRoute, proxyContext);
            proxyTunnelRequest.setRouteConfig(matchedRoute);
            TelemetryHooks.routing(channelHandlerContext.channel(), proxyTunnelRequest, telemetryFallback);
            rejectNonPublicClientLiteral(proxyTunnelRequest, matchedRoute);
            log.info("SNI 分流: user={}, domain={}, targetPort={}, route={}, policy={}, outbound={}, destination={}:{}",
                    proxyContext.getUserName(),
                    targetHost,
                    proxyTunnelRequest.getTargetPort(),
                    matchedRoute.getName(),
                    matchedRoute.getPolicy(),
                    matchedRoute.getOutboundProxyType(),
                    proxyTunnelRequest.getFinalTargetHost(),
                    proxyTunnelRequest.getFinalTargetPort());
        } finally {
            TelemetryHooks.endStage(channelHandlerContext.channel(), org.congcong.proxyworker.telemetry.SessionObservation.Stage.ROUTE_SELECTION);
        }
        channelHandlerContext.fireChannelRead(proxyTunnelRequest);
    }

    /**
     * A destination override connects only to an administrator-configured
     * endpoint, so the original client target is not used as a destination.
     * All other protected tunnel routes reject non-public IP literals before
     * an outbound connector can perform any name resolution.
     */
    private void rejectNonPublicClientLiteral(ProxyTunnelRequest request, RouteConfig route) {
        if (!DestinationAddressPolicy.isProtectedInbound(request.getProtocolType())
                || route.getPolicy() == RoutePolicy.DESTINATION_OVERRIDE
                || route.getPolicy() == RoutePolicy.BLOCK
                || route.getPolicy() == RoutePolicy.DNS_REWRITING) {
            return;
        }
        DestinationAddressPolicy.parseLiteral(request.getTargetHost())
                .filter(address -> !DestinationAddressPolicy.isPubliclyRoutable(address))
                .ifPresent(address -> blockClientLiteral(request, address));
    }

    private void blockClientLiteral(ProxyTunnelRequest request, InetAddress address) {
        PrivateDestinationBlockedException blocked =
                PrivateDestinationBlockedException.forLiteral(request.getTargetHost(), request.getTargetPort(), address);
        request.setDestinationAddressBlocked(blocked);
        log.warn("Blocked non-public client destination: protocol={}, target={}:{}, route={}",
                request.getProtocolType(), request.getTargetHost(), request.getTargetPort(),
                request.getRouteConfig().getName());
    }

    private RouteConfig fallbackRoute(InboundConfig inboundConfig, ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) {
        RouteConfig defaultRoute = inboundConfig.getDefaultRouteConfig();
        if (defaultRoute != null) {
            return defaultRoute;
        }
        log.error("缺少内置的兜底路由策略，关闭连接");
        ByteBuf initialPayload = proxyTunnelRequest.getInitialPayload();
        if (initialPayload != null) {
            initialPayload.release();
        }
        channelHandlerContext.close();
        return null;
    }

}
