package org.congcong.proxyworker.server.factory;

import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.ProxyServer;
import org.congcong.proxyworker.server.impl.ShadowSocksProxyServer;
import org.congcong.proxyworker.server.impl.SocksProxyServer;
import org.congcong.proxyworker.server.impl.HttpProxyServer;
import org.congcong.proxyworker.server.impl.TransparentProxyServer;

public class ProxyServerFactory {
    public static ProxyServer create(InboundConfig cfg) {
        ProtocolType type = cfg.getProtocol();
        if (type == ProtocolType.SOCKS5) {
            return new SocksProxyServer(cfg);
        } else if (type == ProtocolType.HTTPS_CONNECT) {
            return new HttpProxyServer(cfg);
        } else if (type == ProtocolType.SHADOW_SOCKS) {
            return new ShadowSocksProxyServer(cfg);
        } else if (type == ProtocolType.TP_PROXY) {
            return new TransparentProxyServer(cfg);
        }
        return null;
    }
}