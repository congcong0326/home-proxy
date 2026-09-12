package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocksInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

public class ShadowSocksProxyServer extends TcpProxyServer {
    public ShadowSocksProxyServer(InboundConfig cfg) {
        super(cfg);
    }

    @Override
    public int getPort() {
        return getInboundConfig().getPort();
    }

    @Override
    public String getIp() {
        return getInboundConfig().getListenIp();
    }

    @Override
    public String getServerName() {
        return getInboundConfig().getName();
    }

    @Override
    protected ChannelInitializer<Channel> createChildHandler(InboundConfig inboundConfig) {
        return new ShadowSocksInitializer(inboundConfig);
    }
}
