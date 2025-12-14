package org.congcong.proxyworker.router;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.DomainValidator;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.*;
import org.congcong.common.util.geo.*;
import org.congcong.proxyworker.config.FindRoutes;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

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

    private static final DomainValidator domainValidator = DomainValidator.getInstance();


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        ProxyTimeContext proxyTimeContext = ProxyContextResolver.resolveProxyTimeContext(channelHandlerContext.channel(), proxyTunnelRequest);
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(channelHandlerContext.channel(), proxyTunnelRequest);
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        List<RouteConfig> routes = FindRoutes.find(proxyTunnelRequest.getUser().getId(), inboundConfig);


        // dns 解析
        proxyTimeContext.setDnsStartTime(System.currentTimeMillis());
        boolean isDnsServer = proxyTunnelRequest.getInboundConfig().getProtocol() == ProtocolType.DNS_SERVER;
        boolean hostIsIp = false;
        Set<String> rewriteHosts = findRewriteHosts(routes);
        // 这是代理服务器需要连接的目标地址，可能是域名也可能是IP
        String targetHost = proxyTunnelRequest.getTargetHost();
        // 非DNS服务器记录下DNS解析耗时
        // 目标改写的域名就不要解析
        InetAddress inetAddress = null;
        if (!isDnsServer && !rewriteHosts.contains(targetHost)) {
            inetAddress = dnsInetAddress(targetHost);
            if (inetAddress != null) {
                proxyTunnelRequest.setTargetIp(inetAddress.getHostAddress());
            }
            // 不是域名，则是IP
            else {
                hostIsIp = true;
                proxyTunnelRequest.setTargetIp(targetHost);
            }
        }
        proxyTimeContext.setDnsEndTime(System.currentTimeMillis());
        // dns解析完毕
        for (RouteConfig route : routes) {
            List<RouteRule> rules = route.getRules();
            for (RouteRule rule : rules) {
                RouteConditionType conditionType = rule.getConditionType();
                String value = rule.getValue();
                MatchOp op = rule.getOp();
                switch (conditionType) {
                    case GEO -> {
                        if (!proxyTunnelRequest.isLocationResolveSuccess()) {
                            // 域名和IP相等，说明是IP
                            boolean foreign;
                            if (hostIsIp) {
                                 foreign = GeoIPUtil.getInstance().isForeign(targetHost, inetAddress);
                            }
                            // 否则按照域名解析
                            else {
                                MatchResult foreignResult = DomainRuleEngine.match(DomainRuleType.GEO_FOREIGN, targetHost);
                                if (!foreignResult.isMatched()) {
                                    MatchResult cnResult = DomainRuleEngine.match(DomainRuleType.GEO_CN, targetHost);
                                    if (!cnResult.isMatched()) {
                                        foreign = GeoIPUtil.getInstance().isForeign(targetHost, inetAddress);
                                    } else {
                                        foreign = false;
                                    }
                                } else {
                                    foreign = true;
                                }
                            }
                            if (foreign) {
                                proxyTunnelRequest.setCountry("NOT CN");
                            } else {
                                proxyTunnelRequest.setCountry("CN");
                            }
                            proxyTunnelRequest.setLocationResolveSuccess(true);
                        }
                        if (proxyTunnelRequest.isLocationResolveSuccess()) {
                            String country = proxyTunnelRequest.getCountry();
                            boolean matchCondition = Objects.equals(country, value);
                            boolean matched = (op == MatchOp.IN) == matchCondition;
                            if (matched) {
                                log.info("地理路由策略命中 {}", route.getName());
                                ProxyContextFillUtil.proxyContextRouteFill(route, proxyContext);
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
                        MatchResult match = DomainRuleEngine.match(DomainRuleType.DOMAIN, targetHost, value);
                        boolean matched = (op == MatchOp.IN) == match.isMatched();
                        if (matched) {
                            log.info("域名路由策略命中 {}", route.getName());
                            ProxyContextFillUtil.proxyContextRouteFill(route, proxyContext);
                            proxyTunnelRequest.setRouteConfig(route);
                            channelHandlerContext.fireChannelRead(proxyTunnelRequest);
                            return;
                        }
                    }
                    case AD_BLOCK -> {
                        // 非域名再做判断
                        if (!hostIsIp) {
                            MatchResult match = DomainRuleEngine.match(DomainRuleType.AD, targetHost);
                            boolean matched = (op == MatchOp.IN) == match.isMatched();
                            if (matched) {
                                log.info("广告路由策略命中 {}", route.getName());
                                ProxyContextFillUtil.proxyContextRouteFill(route, proxyContext);
                                proxyTunnelRequest.setRouteConfig(route);
                                channelHandlerContext.fireChannelRead(proxyTunnelRequest);
                                return;
                            }
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

    private InetAddress dnsInetAddress(String targetHost) {
        boolean isDomain = domainValidator.isValid(targetHost);
        if (isDomain) {
            try {
                return InetAddress.getByName(targetHost);
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
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
}
