package org.congcong.proxyworker.router;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.DefaultRouteConfig;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.GeoIPUtil;
import org.congcong.proxyworker.util.GeoLocation;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.net.SocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(channelHandlerContext.channel());
        proxyTimeContext.setDnsStartTime(System.currentTimeMillis());
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        List<RouteConfig> routes = FindRoutes.find(proxyTunnelRequest.getUser().getId(), inboundConfig);
        locationLookupAndContextFill(channelHandlerContext, proxyTunnelRequest, routes);
        proxyTimeContext.setDnsEndTime(System.currentTimeMillis());
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
//                        if (inboundConfig.getProtocol() == ProtocolType.DNS_SERVER) {
//                            // 需要将dns查询的域名当作目标服务器地址
//                            DnsProxyContext dnsProxyContext = (DnsProxyContext) proxyTunnelRequest.getProtocolAttachment();
//                            host = dnsProxyContext.getQName();
//                        }
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

        // 配置中已经内置了兜底策略，一般不可能走到这里
        log.error("未找到任何路由策略，关闭连接");
        if (proxyTunnelRequest.getInitialPayload() != null) {
            proxyTunnelRequest.getInitialPayload().release();
        }
        channelHandlerContext.close();
    }



    private void locationLookupAndContextFill(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest, List<RouteConfig> routes) {
        Set<String> rewriteHosts = findRewriteHosts(routes);
        GeoLocation geoLocation = null;
        log.debug("target host: {}", proxyTunnelRequest.getTargetHost());
        boolean isDnsServer = proxyTunnelRequest.getInboundConfig().getProtocol() == ProtocolType.DNS_SERVER;
        if (rewriteHosts.contains(proxyTunnelRequest.getTargetHost())) {
            proxyTunnelRequest.setCity("代理重写");
            proxyTunnelRequest.setCountry("内网");
            proxyTunnelRequest.setLocationResolveSuccess(false);
        } else if (isDnsServer) {
            proxyTunnelRequest.setCity("DNS查询");
            proxyTunnelRequest.setCountry("内网");
            proxyTunnelRequest.setLocationResolveSuccess(false);
        } else {
            Optional<GeoLocation> lookup = GeoIPUtil.getInstance().lookup(proxyTunnelRequest.getTargetHost());
            if (lookup.isPresent()) {
                geoLocation = lookup.get();
                proxyTunnelRequest.setCity(geoLocation.getCity());
                proxyTunnelRequest.setCountry(geoLocation.getCountry());
                proxyTunnelRequest.setLocationResolveSuccess(true);
            }
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
        GeoLocation srcGeo = clientIp != null && !isDnsServer
                ? GeoIPUtil.getInstance().lookup(clientIp).orElse(null) : null;
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channelHandlerContext.channel());
        // 准备目标 IP（无论 GeoIP 是否成功都尽量解析）
        String dstIp = proxyContext.getOriginalTargetIP();
        if (dstIp == null && !isDnsServer) {
            if (geoLocation != null) {
                dstIp = geoLocation.getIp();
            } else {
                if (!rewriteHosts.contains(proxyTunnelRequest.getTargetHost())) {
                    dstIp = GeoIPUtil.getInstance().resolveToIp(proxyTunnelRequest.getTargetHost());
                } else {
                    dstIp = proxyTunnelRequest.getTargetHost();
                }
            }
        }
        if (srcGeo != null) {
            proxyContext.setSrcGeoCity(srcGeo.getCity());
            proxyContext.setSrcGeoCountry(srcGeo.getCountry());
        }
        if (geoLocation != null) {
            proxyContext.setDstGeoCity(geoLocation.getCity());
            proxyContext.setDstGeoCountry(geoLocation.getCountry());
        }
        if (proxyContext.getOriginalTargetHost() == null) {
            proxyContext.setOriginalTargetHost(proxyTunnelRequest.getTargetHost());
        }
        proxyContext.setOriginalTargetIP(dstIp);
        proxyContext.setOriginalTargetPort(proxyTunnelRequest.getTargetPort());

        proxyContext.setClientIp(clientIp);
        proxyContext.setClientPort(clientPort == null ? 0 : clientPort);

    }

    private Set<String> findRewriteHosts(List<RouteConfig> routes) {
        Set<String> rewriteHosts = new HashSet<>();
        for (RouteConfig route : routes) {
            if(route.getPolicy() == RoutePolicy.DESTINATION_OVERRIDE ||
                route.getPolicy() == RoutePolicy.DNS_REWRITING) {
                for (RouteRule rule : route.getRules()) {
                    if (rule.getConditionType() == RouteConditionType.DOMAIN) {
                        rewriteHosts.add(rule.getValue());
                    }
                }
            }
        }
        return rewriteHosts;
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
