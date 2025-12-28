package org.congcong.proxyworker.router;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.DomainValidator;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.*;
import org.congcong.common.util.geo.*;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.context.ProxyContextResolver;
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

    private static final DomainValidator domainValidator = DomainValidator.getInstance();


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(channelHandlerContext.channel(), proxyTunnelRequest);
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        List<RouteConfig> routes = FindRoutes.find(proxyTunnelRequest.getUser().getId(), inboundConfig);
        String targetHost = proxyTunnelRequest.getTargetHost();
        boolean hostIsIp = !domainValidator.isValid(targetHost);
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
            case AD_BLOCK -> matchesAdRoute(route, op, targetHost, hostIsIp);
        };
    }

    private boolean matchesGeoRoute(RouteConfig route, String expectedCountry, MatchOp op, String targetHost, boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest) {
        ensureLocationResolved(hostIsIp, proxyTunnelRequest, targetHost);
        if (!proxyTunnelRequest.isLocationResolveSuccess()) {
            return false;
        }
        String country = proxyTunnelRequest.getCountry();
        boolean matchCondition = Objects.equals(country, expectedCountry);
        boolean matched = (op == MatchOp.IN) == matchCondition;
        if (matched) {
            log.debug("地理路由策略命中 {}", targetHost);
        }
        return matched;
    }

    private void ensureLocationResolved(boolean hostIsIp, ProxyTunnelRequest proxyTunnelRequest, String targetHost) {
        if (proxyTunnelRequest.isLocationResolveSuccess()) {
            return;
        }
        boolean foreign;
        if (hostIsIp) {
            // ip通过IP库判断
            foreign = GeoIPUtil.getInstance().isForeign(targetHost, null);
        } else {
            // 域名通过规则判断
            MatchResult foreignResult = DomainRuleEngine.match(DomainRuleType.GEO_FOREIGN, targetHost);
            if (!foreignResult.isMatched()) {
                MatchResult cnResult = DomainRuleEngine.match(DomainRuleType.GEO_CN, targetHost);
                foreign = !cnResult.isMatched();
            } else {
                foreign = true;
            }
        }
        proxyTunnelRequest.setCountry(foreign ? "NOT CN" : "CN");
        proxyTunnelRequest.setLocationResolveSuccess(true);
    }

    private boolean matchesDomainRoute(RouteConfig route, String ruleValue, MatchOp op, String targetHost) {
        MatchResult match = DomainRuleEngine.match(DomainRuleType.DOMAIN, targetHost, ruleValue);
        boolean matched = (op == MatchOp.IN) == match.isMatched();
        if (matched) {
            log.debug("域名路由策略命中 {}", targetHost);
        }
        return matched;
    }

    private boolean matchesAdRoute(RouteConfig route, MatchOp op, String targetHost, boolean hostIsIp) {
        if (hostIsIp) {
            return false;
        }
        MatchResult match = DomainRuleEngine.match(DomainRuleType.AD, targetHost);
        boolean matched = (op == MatchOp.IN) == match.isMatched();
        if (matched) {
            log.debug("广告路由策略命中 {}", targetHost);
        }
        return matched;
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
