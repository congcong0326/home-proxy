package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocksInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

public class ShadowSocksProxyServer extends TcpProxyServer {
    private final InboundConfig cfg;

    public ShadowSocksProxyServer(InboundConfig cfg) {
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
    public InboundConfig getInboundConfig() {
        return cfg;
    }

    @Override
    public ChannelInitializer<Channel> getChildHandler() {
        return new ShadowSocksInitializer(getInboundConfig());
    }
}
