package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;

public class DnsServerInitializer extends AbstractChannelInitializer {


    public DnsServerInitializer(InboundConfig inboundConfig) {
        super(inboundConfig);
    }

    @Override
    protected void init(Channel ch) {
        ch.pipeline().addLast(new DatagramDnsQueryDecoder());
        ch.pipeline().addLast(new UdpDnsQueryHandler());
        ch.pipeline().addLast(new DatagramDnsResponseEncoder());
    }

    protected void processSSL(SocketChannel socketChannel) {
        // no-op for UDP DNS
    }
}
