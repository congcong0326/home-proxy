package org.congcong.proxyworker.util;

import io.netty.channel.Channel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

import java.net.SocketAddress;

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


    public static void proxyContextInitFill(Channel socketChannel, InboundConfig inboundConfig, ProxyContext context){
        // 基础入站信息
        context.setProxyId(inboundConfig.getId() == null ? 0 : inboundConfig.getId());
        context.setProxyName(inboundConfig.getName());
        context.setInboundProtocolType(inboundConfig.getProtocol());
        context.setInboundProxyEncAlgo(inboundConfig.getSsMethod());
        //源IP与端口
        java.net.InetSocketAddress remote = null;
        SocketAddress remoteAddr = socketChannel.remoteAddress();
        if (remoteAddr instanceof java.net.InetSocketAddress) {
            remote = (java.net.InetSocketAddress) remoteAddr;
        }
        String clientIp = null;
        Integer clientPort = null;
        if (remote != null) {
            clientIp = remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
            clientPort = remote.getPort();
        }
        if (clientIp != null) {
            context.setClientIp(clientIp);
            context.setClientPort(clientPort);
        }
    }

}
