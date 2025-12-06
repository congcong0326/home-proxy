package org.congcong.proxyworker.outbound.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;

/**
 * 转发给上游的普通的DNS服务器
 */
@Slf4j
public class DNSConnector extends AbstractDnsUpstreamConnector {


    @Override
    protected int defaultPort() {
        return 53;
    }

    @Override
    protected ProtocolType outboundType() {
        return ProtocolType.DNS_SERVER;
    }

    @Override
    protected boolean ready(Channel ch) {
        return true;
    }

    @Override
    protected ChannelFuture create(Channel inbound, String host, int port, PoolKey key) {
        Bootstrap b = new Bootstrap();
        b.group(inbound.eventLoop())
                .channel(getDatagramChannel())
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramDnsQueryEncoder());
                        ch.pipeline().addLast(new DatagramDnsResponseDecoder());
                    }
                });
        return b.connect(host, port);
    }
}
