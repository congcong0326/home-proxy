package org.congcong.proxyworker.router;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.GeoIPUtil;
import org.congcong.proxyworker.util.GeoLocation;

import java.util.List;
import java.util.Optional;

@ChannelHandler.Sharable
@Slf4j
public class RouterService extends SimpleChannelInboundHandler<ProxyTunnelRequest>  {

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
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        List<RouteConfig> routes = inboundConfig.getRoutes();
        locationLookup(proxyTunnelRequest);

        for (RouteConfig route : routes) {
            List<RouteRule> rules = route.getRules();
            for (RouteRule rule : rules) {
                RouteConditionType conditionType = rule.getConditionType();
                String value = rule.getValue();
                MatchOp op = rule.getOp();
                switch (conditionType) {
                    case GEO -> {
                        if (proxyTunnelRequest.isLocationResolveSuccess()) {
                            String country = proxyTunnelRequest.getCountry();
                            boolean matchCondition = matchGeo(country, value);
                            boolean matched = (op == MatchOp.IN) == matchCondition;
                            if (matched) {
                                proxyTunnelRequest.setRouteConfig(route);
                                channelHandlerContext.fireChannelRead(proxyTunnelRequest);
                                return;
                            }
                        }
                    }
                    //支持域名匹配，如：example.com
                    //支持通配符匹配，如：*.example.com
                    //支持子域名匹配，如：sub.example.com
                    case DOMAIN -> {
                        String host = proxyTunnelRequest.getTargetHost();
                        boolean matchCondition = matchDomain(host, value);
                        boolean matched = (op == MatchOp.IN) == matchCondition;
                        if (matched) {
                            proxyTunnelRequest.setRouteConfig(route);
                            channelHandlerContext.fireChannelRead(proxyTunnelRequest);
                            return;
                        }
                    }
                }

            }

        }

        // 未命中任何路由，继续向下传递请求
        channelHandlerContext.fireChannelRead(proxyTunnelRequest);
    }

    private void locationLookup(ProxyTunnelRequest proxyTunnelRequest) {
        Optional<GeoLocation> lookup = GeoIPUtil.getInstance().lookup(proxyTunnelRequest.getTargetHost());
        GeoLocation geoLocation = null;
        if (lookup.isPresent()) {
            geoLocation = lookup.get();
            proxyTunnelRequest.setCity(geoLocation.getCity());
            proxyTunnelRequest.setCountry(geoLocation.getCountry());
            proxyTunnelRequest.setLocationResolveSuccess(true);
        }
    }

    private boolean matchGeo(String country, String value) {
        if (country == null || value == null) {
            return false;
        }
        String normalizedCountry = normalizeCountryName(country);
        String np = normalizeCountryName(value);
        return np.equalsIgnoreCase(normalizedCountry);
    }

    private String normalizeCountryName(String input) {
        String p = input == null ? null : input.trim();
        if (p == null || p.isEmpty()) return "";
        if (p.equalsIgnoreCase("CN") || p.equalsIgnoreCase("China") || p.equalsIgnoreCase("People's Republic of China") || p.equals("中国")) {
            // 统一中国的名字到中文以适配 GeoIP zh-CN
            return "中国";
        }
        return p;
    }

    private boolean matchDomain(String host, String value) {
        if (host == null || value == null) {
            return false;
        }
        String h = host.trim().toLowerCase();

        if (value.startsWith("*.")) {
            String suffix = value.substring(1); // 包含前导点，如 .example.com
            return h.endsWith(suffix) && h.length() > suffix.length();
        } else {
            return h.equals(value);
        }
    }
}
