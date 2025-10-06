package org.congcong.proxyworker.server.tunnel;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;

/**
 * 通用的隧道建立请求对象，统一 SOCKS/HTTPS/SS 等协议的入站隧道参数。
 */
@Getter
public class ProxyTunnelRequest {
    private final ProtocolType protocolType;
    private final String targetHost;
    private final int targetPort;
    @Setter
    private RouteConfig routeConfig;
    @Setter
    private String country;
    @Setter
    private String city;
    @Setter
    private boolean isLocationResolveSuccess;
    private final UserConfig user;
    private final InboundConfig inboundConfig;
    private final ByteBuf initialPayload; // 可选首包载荷（某些客户端可能会在握手后立即发送）

    public ProxyTunnelRequest(ProtocolType protocolType,
                              String targetHost,
                              int targetPort,
                              UserConfig user,
                              InboundConfig inboundConfig,
                              ByteBuf initialPayload) {
        this.protocolType = protocolType;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.user = user;
        this.inboundConfig = inboundConfig;
        this.initialPayload = initialPayload;
    }

    public static ProxyTunnelRequest fromSocks5(Socks5CommandRequest cmd,
                                                InboundConfig inboundConfig,
                                                UserConfig user,
                                                ByteBuf initialPayload) {
        return new ProxyTunnelRequest(
                ProtocolType.SOCKS5,
                cmd.dstAddr(),
                cmd.dstPort(),
                user,
                inboundConfig,
                initialPayload
        );
    }

}