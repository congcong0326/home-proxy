package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.transparent.TransparentServerInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

public class TransparentProxyServer extends TcpProxyServer {

    public TransparentProxyServer(InboundConfig cfg) {
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
        return new TransparentServerInitializer(inboundConfig);
    }

}
