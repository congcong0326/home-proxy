package org.congcong.proxyworker.udp;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.RouteConfig;

public final class UdpRouteSupport {
    private UdpRouteSupport() {}

    public static boolean supports(RouteConfig route) {
        return route != null && route.getPolicy() == RoutePolicy.DIRECT;
    }
}
