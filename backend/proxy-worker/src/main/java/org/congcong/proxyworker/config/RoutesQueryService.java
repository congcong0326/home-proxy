package org.congcong.proxyworker.config;

import java.util.List;
import java.util.Map;

public class RoutesQueryService {

    public List<RouteConfig> getRoutes(Long userId, InboundConfig inboundConfig) {
        Map<Long, List<RouteConfig>> routesMap = inboundConfig.getRoutesMap();
        List<RouteConfig> routeConfigs = routesMap == null ? null : routesMap.get(userId);
        if (routeConfigs != null) return routeConfigs;
        RouteConfig fallback = inboundConfig.getDefaultRouteConfig();
        return fallback == null ? List.of() : List.of(fallback);
    }

}
