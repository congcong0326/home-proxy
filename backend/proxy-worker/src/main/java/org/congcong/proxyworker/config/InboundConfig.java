package org.congcong.proxyworker.config;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class InboundConfig {
    // 服务器主体
    private Long id;
    private String name;
    private ProtocolType protocol;
    private String listenIp;
    private Integer port;
    private Boolean tlsEnabled;
    private Boolean sniffEnabled;
    private ProxyEncAlgo ssMethod;

    // 路由规则，基于设备
    // deviceA -> routeA
    // deviceB -> routeB
    // shadow socks 一个设备一个端口，所以只有一个路由策略
    // socks https 透明代理，多个设备共享同一个端口，所以会存在不同设备到不同路由策略之间的建模
    // 用户名称/ip 到用户的映射
    private Map<String, UserConfig> usersMap;
    private Map<String, UserConfig> deviceIpMapUser;
    // 用户到路由的映射
    private Map<Long, List<RouteConfig>> routesMap;

    // 兜底的用户
    private UserConfig anonymousUser;
    private RouteConfig defaultRouteConfig;
}
