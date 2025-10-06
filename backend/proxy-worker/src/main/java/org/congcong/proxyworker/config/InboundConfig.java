package org.congcong.proxyworker.config;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;

import java.util.List;
import java.util.Map;

@Data
public class InboundConfig {
    private Long id;
    private String name;
    private ProtocolType protocol;
    private String listenIp;
    private Integer port;
    private Boolean tlsEnabled;
    private Boolean sniffEnabled;
    private String ssMethod;
    private List<UserConfig> allowedUsers;
    private Map<String, UserConfig> usersMap;
    private List<RouteConfig> routes;
}
