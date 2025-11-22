package org.congcong.proxyworker.config;

import java.util.List;

public class FindRoutes {

    private static final RoutesQueryService delegate = new RoutesQueryService();

    public static List<RouteConfig> find(Long userId, InboundConfig inboundConfig) {
        return delegate.getRoutes(userId, inboundConfig);
    }
}
