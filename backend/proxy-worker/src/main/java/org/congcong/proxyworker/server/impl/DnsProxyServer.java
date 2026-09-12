package org.congcong.proxyworker.server.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.dns.DnsServerInitializer;
import org.congcong.proxyworker.server.UdpProxyServer;

public class DnsProxyServer extends UdpProxyServer {


    public DnsProxyServer(InboundConfig cfg) {
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
    public ChannelInitializer<Channel> getChannelInitializer() {
        return new DnsServerInitializer(this::getInboundConfig);
    }
}
