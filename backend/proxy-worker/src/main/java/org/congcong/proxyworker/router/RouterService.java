package org.congcong.proxyworker.router;

import org.congcong.proxyworker.telemetry.TelemetryHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.*;
import org.congcong.common.util.geo.*;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.dns.DnsRouteSelector;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.rules.RuleSetRegistry;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.net.InetAddress;
import java.util.*;

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
        if (proxyTunnelRequest.getProtocolType() == ProtocolType.DNS_SERVER) {
            String clientIp = proxyContext.getClientIp();
            matchedRoute = DnsRouteSelector.select(inboundConfig, clientIp, targetHost).route();
        } else {
            List<RouteConfig> routes = FindRoutes.find(routingUserId, inboundConfig);
            boolean hostIsIp = isIp(targetHost);
            matchedRoute = selectMatchedRoute(routes, targetHost, hostIsIp, proxyTunnelRequest);
        }
        boolean telemetryFallback = matchedRoute == null;
        if (matchedRoute == null) {
            matchedRoute = fallbackRoute(inboundConfig, channelHandlerContext, proxyTunnelRequest);
            if (matchedRoute == null) {
                return;
            }
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
        channelHandlerContext.fireChannelRead(proxyTunnelRequest);
    }

    private RouteConfig selectMatchedRoute(List<RouteConfig> routes, String targetHost, boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest) {
        for (RouteConfig route : routes) {
            if (routeMatches(route, targetHost, hostIsIp, proxyTunnelRequest)) {
                return route;
            }
        }
        return null;
    }

    private boolean routeMatches(RouteConfig route, String targetHost, boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest) {
        int telemetryRuleIndex = 0;
        for (RouteRule rule : route.getRules()) {
            if (ruleMatches(route, rule, targetHost, hostIsIp, proxyTunnelRequest)) {
                proxyTunnelRequest.setTelemetryMatchedRuleId(proxyTunnelRequest.getInboundConfig().getConfigVersion()
                        + ":" + route.getId() + ":" + telemetryRuleIndex);
                proxyTunnelRequest.setTelemetryMatchedRuleSet(rule.getConditionType() == RouteConditionType.RULE_SET ? rule.getValue() : null);
                return true;
            }
            telemetryRuleIndex++;
        }
        return false;
    }

    private boolean ruleMatches(RouteConfig route, RouteRule rule, String targetHost, boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest) {
        RouteConditionType conditionType = rule.getConditionType();
        String value = rule.getValue();
        MatchOp op = rule.getOp();
        return switch (conditionType) {
            case GEO -> matchesGeoRoute(route, value, op, targetHost, hostIsIp, proxyTunnelRequest);
            case DOMAIN -> matchesDomainRoute(route, value, op, targetHost);
            case AD_BLOCK -> false;
            case RULE_SET -> matchesRuleSetRoute(value, op, targetHost, hostIsIp);
        };
    }

    private boolean matchesGeoRoute(RouteConfig route, String expectedCountry, MatchOp op, String targetHost, boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest) {
        String geoTarget = geoLookupTarget(proxyTunnelRequest, targetHost, hostIsIp);
        if (geoTarget == null) {
            return false;
        }
        ensureLocationResolved(proxyTunnelRequest, geoTarget);
        if (!proxyTunnelRequest.isLocationResolveSuccess()) {
            return false;
        }
        String country = proxyTunnelRequest.getCountry();
        boolean matchCondition = Objects.equals(country, expectedCountry);
        boolean matched = (op == MatchOp.IN) == matchCondition;
        if (matched) {
            log.debug("地理 {} {} 路由策略命中 {}", op, expectedCountry, targetHost);
        }
        return matched;
    }

    private void ensureLocationResolved(ProxyTunnelRequest proxyTunnelRequest, String targetIp) {
        if (proxyTunnelRequest.isLocationResolveSuccess()) {
            return;
        }
        boolean foreign = GeoIPUtil.getInstance().isForeign(targetIp, null);
        proxyTunnelRequest.setCountry(foreign ? "NOT CN" : "CN");
        proxyTunnelRequest.setLocationResolveSuccess(true);
    }

    private boolean matchesDomainRoute(RouteConfig route, String ruleValue, MatchOp op, String targetHost) {
        boolean matched = (op == MatchOp.IN) == DomainMatcher.matches(targetHost, ruleValue);
        if (matched) {
            log.debug("域名路由 {} 策略命中 {}", ruleValue, targetHost);
        }
        return matched;
    }

    private boolean matchesRuleSetRoute(String ruleSetKey, MatchOp op, String targetHost, boolean hostIsIp) {
        if (hostIsIp) {
            return false;
        }
        boolean match = RuleSetRegistry.match(ruleSetKey, targetHost);
        boolean matched = (op == MatchOp.IN) == match;
        if (matched) {
            log.debug("规则集 {} 策略命中 {}", ruleSetKey, targetHost);
        }
        return matched;
    }

    private String geoLookupTarget(ProxyTunnelRequest proxyTunnelRequest, String targetHost, boolean hostIsIp) {
        if (hostIsIp) {
            return targetHost;
        }
        String targetIp = proxyTunnelRequest.getTargetIp();
        if (isIp(targetIp)) {
            return targetIp;
        }
        return null;
    }

    private boolean isIp(String value) {
        return DestinationAddressPolicy.parseLiteral(value).isPresent();
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
