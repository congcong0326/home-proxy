package org.congcong.proxyworker.config;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class InboundConfig {
    private Long id;
    private String name;
    private ProtocolType protocol;
    private String listenIp;
    private Integer port;
    private Boolean tlsEnabled;
    private Boolean sniffEnabled;
    private ProxyEncAlgo ssMethod;
    private List<UserConfig> allowedUsers;
    private Map<String, UserConfig> usersMap;
    private Map<String, UserConfig> deviceIpMapUser;
    private List<RouteConfig> routes;
    private Set<String> rewriteHosts;
}
