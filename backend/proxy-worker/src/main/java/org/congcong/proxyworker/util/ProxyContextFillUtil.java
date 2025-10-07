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

        // 入站加密算法（如为 SS，解析 ssMethod 到枚举）
        ProxyEncAlgo encAlgo = null;
        String ssMethod = inboundConfig.getSsMethod();
        if (ssMethod != null) {
            String normalized = ssMethod.trim().toLowerCase().replace('-', '_');
            switch (normalized) {
                case "aes_256_gcm":
                    encAlgo = ProxyEncAlgo.aes_256_gcm;
                    break;
                case "aes_128_gcm":
                    encAlgo = ProxyEncAlgo.aes_128_gcm;
                    break;
                case "chacha20_ietf_poly1305":
                    encAlgo = ProxyEncAlgo.chacha20_ietf_poly1305;
                    break;
                default:
                    // 未知或不支持的算法，保持为空
                    break;
            }
        }
        context.setInboundProxyEncAlgo(encAlgo);
    }

}
