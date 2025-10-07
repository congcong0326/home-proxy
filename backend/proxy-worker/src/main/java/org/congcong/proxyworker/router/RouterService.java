package org.congcong.proxyworker.router;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.proxyworker.config.DefaultRouteConfig;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.GeoIPUtil;
import org.congcong.proxyworker.util.GeoLocation;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.net.SocketAddress;
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
        locationLookupAndContextFill(channelHandlerContext, proxyTunnelRequest);
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
                                log.debug("地理路由策略命中 {}", route.getName());
                                ProxyContextFillUtil.proxyContextRouteFill(route, ChannelAttributes.getProxyContext(channelHandlerContext.channel()));
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
                            log.debug("域名路由策略命中 {}", route.getName());
                            ProxyContextFillUtil.proxyContextRouteFill(route, ChannelAttributes.getProxyContext(channelHandlerContext.channel()));
                            proxyTunnelRequest.setRouteConfig(route);
                            channelHandlerContext.fireChannelRead(proxyTunnelRequest);
                            return;
                        }
                    }
                }

            }

        }

        // 未命中任何路由，继续向下传递请求
        proxyTunnelRequest.setRouteConfig(DefaultRouteConfig.getInstance());
        ProxyContextFillUtil.proxyContextRouteFill(proxyTunnelRequest.getRouteConfig(), ChannelAttributes.getProxyContext(channelHandlerContext.channel()));
        channelHandlerContext.fireChannelRead(proxyTunnelRequest);
    }



    private void locationLookupAndContextFill(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) {
        Optional<GeoLocation> lookup = GeoIPUtil.getInstance().lookup(proxyTunnelRequest.getTargetHost());
        GeoLocation geoLocation = null;
        if (lookup.isPresent()) {
            geoLocation = lookup.get();
            proxyTunnelRequest.setCity(geoLocation.getCity());
            proxyTunnelRequest.setCountry(geoLocation.getCountry());
            proxyTunnelRequest.setLocationResolveSuccess(true);
        }
        // 获取客户端源地址信息（IP/端口）
        java.net.InetSocketAddress remote = null;
        SocketAddress remoteAddr = channelHandlerContext.channel().remoteAddress();
        if (remoteAddr instanceof java.net.InetSocketAddress) {
            remote = (java.net.InetSocketAddress) remoteAddr;
        }

        String clientIp = null;
        Integer clientPort = null;
        if (remote != null) {
            clientIp = remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
            clientPort = remote.getPort();
        }

        // 解析客户端地理位置
        GeoLocation srcGeo = clientIp != null ? GeoIPUtil.getInstance().lookup(clientIp).orElse(null) : null;

        // 准备目标 IP（无论 GeoIP 是否成功都尽量解析）
        String dstIp = null;
        if (geoLocation != null) {
            dstIp = geoLocation.getIp();
        } else {
            dstIp = GeoIPUtil.getInstance().resolveToIp(proxyTunnelRequest.getTargetHost());
        }

        // 填充 ProxyContext
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channelHandlerContext.channel());
        if (srcGeo != null) {
            proxyContext.setSrcGeoCity(srcGeo.getCity());
            proxyContext.setSrcGeoCountry(srcGeo.getCountry());
        }
        if (geoLocation != null) {
            proxyContext.setDstGeoCity(geoLocation.getCity());
            proxyContext.setDstGeoCountry(geoLocation.getCountry());
        }
        proxyContext.setOriginalTargetHost(proxyTunnelRequest.getTargetHost());
        proxyContext.setOriginalTargetIP(dstIp);
        proxyContext.setOriginalTargetPort(proxyTunnelRequest.getTargetPort());

        proxyContext.setClientIp(clientIp);
        proxyContext.setClientPort(clientPort == null ? 0 : clientPort);

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

    /**
     * 支持域名匹配，如：example.com
     * 支持通配符匹配，如：*.example.com
     * 支持子域名匹配，如：sub.example.com
     * todo 这里如何设计一个全匹配
     * @param host
     * @param value
     * @return
     */
    private boolean matchDomain(String host, String value) {
        if (host == null || value == null) {
            return false;
        }
        String h = normalizeHost(host);
        String v = normalizeHost(value);

        // 全匹配：任意非空 host
        if ("*".equals(v)) {
            return !h.isEmpty();
        }

        // 后缀匹配（含主域）：".example.com" -> 匹配 example.com 与 *.example.com
        if (v.startsWith(".")) {
            String base = v.substring(1);       // "example.com"
            String suffix = v;                  // ".example.com"
            return h.equals(base) || (h.endsWith(suffix) && h.length() > suffix.length());
        }

        // 仅子域通配： "*.example.com" 不匹配主域
        if (v.startsWith("*.")) {
            String suffix = v.substring(1);     // ".example.com"
            return h.endsWith(suffix) && h.length() > suffix.length();
        }

        // 精确匹配
        return h.equals(v);
    }

    private String normalizeHost(String input) {
        String s = input.trim();
        // 去掉 FQDN 末尾点
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        // 统一小写并处理 IDN
        try {
            s = java.net.IDN.toASCII(s.toLowerCase());
        } catch (IllegalArgumentException e) {
            s = s.toLowerCase();
        }
        return s;
    }
}
