package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.common.enums.RoutePolicy;

@Data
public class ProxyContext {

    // 主体信息
    private Long userId;
    private String userName;


    // 原访问目标
    private String originalTargetHost;
    private String originalTargetIP;
    private Integer originalTargetPort;
    // 改写目标
    private String rewriteTargetHost;
    private Integer rewriteTargetPort;

    // 源IP地址
    private String clientIp;
    private Integer clientPort;

    // 地理位置信息
    private String srcGeoCountry;
    private String srcGeoCity;
    private String dstGeoCountry;
    private String dstGeoCity;

    private long bytesIn;
    private long bytesOut;


    // 代理服务名称
    private String proxyName;
    private long proxyId;
    // 入站协议
    private ProtocolType inboundProtocolType;
    private ProtocolType outboundProtocolType;
    //路由策略
    private RoutePolicy routePolicy;
    private String routePolicyName;
    private long routePolicyId;
    // 入站加密算法
    private ProxyEncAlgo inboundProxyEncAlgo;
    // 出站加密算法
    private ProxyEncAlgo outboundProxyEncAlgo;

}
