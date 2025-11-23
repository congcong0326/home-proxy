package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.socks.SocksServerInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

public class SocksProxyServer extends TcpProxyServer {

    private final InboundConfig cfg;

    public SocksProxyServer(InboundConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public int getPort() {
        return cfg.getPort();
    }

    @Override
    public String getIp() {
        return cfg.getListenIp();
    }

    @Override
    public String getServerName() {
        return cfg.getName();
    }

    @Override
    public ChannelInitializer<Channel> getChildHandler() {
        return new SocksServerInitializer(getInboundConfig());
    }

    @Override
    public InboundConfig getInboundConfig() {
        return cfg;
    }
}
