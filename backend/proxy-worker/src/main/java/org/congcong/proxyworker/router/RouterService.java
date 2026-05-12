package org.congcong.proxyworker.router;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.*;
import org.congcong.common.util.geo.*;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.rules.RuleSetRegistry;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

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

    private static final InetAddressValidator inetAddressValidator = InetAddressValidator.getInstance();


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(channelHandlerContext.channel(), proxyTunnelRequest);
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        List<RouteConfig> routes = FindRoutes.find(proxyTunnelRequest.getUser().getId(), inboundConfig);
        String targetHost = proxyTunnelRequest.getTargetHost();
        boolean hostIsIp = isIp(targetHost);
        RouteConfig matchedRoute = selectMatchedRoute(routes, targetHost, hostIsIp, proxyTunnelRequest);
        if (matchedRoute == null) {
            matchedRoute = fallbackRoute(inboundConfig, channelHandlerContext, proxyTunnelRequest);
            if (matchedRoute == null) {
                return;
            }
        }
        ProxyContextFillUtil.proxyContextRouteFill(matchedRoute, proxyContext);
        proxyTunnelRequest.setRouteConfig(matchedRoute);
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
        for (RouteRule rule : route.getRules()) {
            if (ruleMatches(route, rule, targetHost, hostIsIp, proxyTunnelRequest)) {
                return true;
            }
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
        return value != null && inetAddressValidator.isValid(value);
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
