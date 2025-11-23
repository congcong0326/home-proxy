package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.dns.DnsServerInitializer;
import org.congcong.proxyworker.server.UdpProxyServer;

public class DnsProxyServer extends UdpProxyServer {


    private final InboundConfig cfg;

    public DnsProxyServer(InboundConfig cfg) {
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
    public ChannelInitializer<Channel> getChannelInitializer() {
        return new DnsServerInitializer(getInboundConfig());
    }
}
