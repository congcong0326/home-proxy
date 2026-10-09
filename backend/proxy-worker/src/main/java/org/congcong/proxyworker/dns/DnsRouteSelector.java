package org.congcong.proxyworker.dns;

import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.util.geo.DomainMatcher;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.FindUser;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.rules.RuleSetRegistry;

import java.util.List;

/** DNS routing shared by client datagrams and worker-originated lookups. */
public final class DnsRouteSelector {
    private DnsRouteSelector() {}

    public static Selection select(InboundConfig dnsInbound, String clientIp, String domain) {
        UserConfig user = FindUser.find(clientIp, dnsInbound);
        if (user == null) throw new IllegalStateException("DNS 入站缺少匹配设备和匿名用户");
        List<RouteConfig> routes = FindRoutes.find(user.getId(), dnsInbound);
        for (RouteConfig route : routes) {
            if (route != null && route.getRules() != null) {
                for (RouteRule rule : route.getRules()) {
                    if (matches(rule, domain)) return new Selection(user, route);
                }
            }
        }
        RouteConfig fallback = dnsInbound.getDefaultRouteConfig();
        if (fallback == null) throw new IllegalStateException("DNS 入站缺少兜底路由");
        return new Selection(user, fallback);
    }

    private static boolean matches(RouteRule rule, String domain) {
        if (rule == null || rule.getConditionType() == null || rule.getOp() == null) return false;
        RouteConditionType type = rule.getConditionType();
        if (type != RouteConditionType.DOMAIN && type != RouteConditionType.RULE_SET) return false;
        boolean matched = type == RouteConditionType.DOMAIN
                ? DomainMatcher.matches(domain, rule.getValue())
                : RuleSetRegistry.match(rule.getValue(), domain);
        return (rule.getOp() == MatchOp.IN) == matched;
    }

    public record Selection(UserConfig user, RouteConfig route) {}
}
