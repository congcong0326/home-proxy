package org.congcong.proxyworker.config;

import lombok.Data;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.common.enums.RoutePolicy;

import java.util.List;

@Data
public class RouteConfig {

    private Long id;

    private String name;

    private List<RouteRule> rules;

    private RoutePolicy policy;

    private String outboundTag;

    private ProtocolType outboundProxyType;

    private String outboundProxyHost;

    private Integer outboundProxyPort;

    private String outboundProxyUsername;

    private String outboundProxyPassword;

    private ProxyEncAlgo outboundProxyEncAlgo;


}
