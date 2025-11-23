package org.congcong.proxyworker.config;

import org.congcong.common.enums.ProtocolType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RoutesQueryService {

    public List<RouteConfig> getRoutes(Long userId, InboundConfig inboundConfig) {
        Map<Long, List<RouteConfig>> routesMap = inboundConfig.getRoutesMap();
        List<RouteConfig> routeConfigs = routesMap.get(userId);
        if (routeConfigs == null) {
            // dns 服务需要一个
            // todo不太优雅，但是dns服务不通会有问题
            if (inboundConfig.getProtocol() == ProtocolType.DNS_SERVER) {
                if (!routesMap.isEmpty()) {
                    return routesMap.values().stream().findAny().orElse(Collections.singletonList(inboundConfig.getDefaultRouteConfig()));
                }
            }
            return Collections.singletonList(inboundConfig.getDefaultRouteConfig());
        }
        return routeConfigs;
    }

}
