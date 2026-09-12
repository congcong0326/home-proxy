package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.socks.SocksServerInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

public class SocksProxyServer extends TcpProxyServer {

    public SocksProxyServer(InboundConfig cfg) {
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
        return new SocksServerInitializer(inboundConfig);
    }

}
