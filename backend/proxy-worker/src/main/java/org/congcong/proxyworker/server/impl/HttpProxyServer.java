package org.congcong.proxyworker.server.impl;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.http.HttpServerInitializer;
import org.congcong.proxyworker.server.ProxyServer;

public class HttpProxyServer extends ProxyServer {


    private final InboundConfig cfg;

    public HttpProxyServer(InboundConfig cfg) {
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
    public ChannelInitializer<SocketChannel> getChildHandler() {
        return new HttpServerInitializer(getInboundConfig());
    }
}