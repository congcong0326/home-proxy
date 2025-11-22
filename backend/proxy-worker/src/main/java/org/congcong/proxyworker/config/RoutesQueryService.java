package org.congcong.proxyworker.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RoutesQueryService {

    public List<RouteConfig> getRoutes(Long userId, InboundConfig inboundConfig) {
        Map<Long, List<RouteConfig>> routesMap = inboundConfig.getRoutesMap();
        List<RouteConfig> routeConfigs = routesMap.get(userId);
        if (routeConfigs == null) {
            return Collections.singletonList(inboundConfig.getDefaultRouteConfig());
        }
        return routeConfigs;
    }

}
