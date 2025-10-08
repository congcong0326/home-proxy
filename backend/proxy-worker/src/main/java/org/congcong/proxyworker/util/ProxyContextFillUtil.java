package org.congcong.proxyworker.util;

import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

public class ProxyContextFillUtil {


    public static void proxyContextRouteFill(RouteConfig route, ProxyContext context) {
        context.setRoutePolicyName(route.getName());
        context.setRoutePolicyId(route.getId());
        context.setRoutePolicy(route.getPolicy());
        context.setRewriteTargetHost(route.getOutboundProxyHost());
        context.setRewriteTargetPort(route.getOutboundProxyPort());
        context.setOutboundProtocolType(route.getOutboundProxyType());
        context.setOutboundProxyEncAlgo(route.getOutboundProxyEncAlgo());
    }

    public static void proxyContextInitFill(InboundConfig inboundConfig, ProxyContext context){
        // 基础入站信息
        context.setProxyId(inboundConfig.getId() == null ? 0 : inboundConfig.getId());
        context.setProxyName(inboundConfig.getName());
        context.setInboundProtocolType(inboundConfig.getProtocol());
        context.setInboundProxyEncAlgo(inboundConfig.getSsMethod());
    }

}
