package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.util.ProxyContextFillUtil;

import java.util.Objects;
import java.util.function.Supplier;

public class DnsServerInitializer extends AbstractChannelInitializer {

    private final Supplier<InboundConfig> inboundConfigSupplier;


    public DnsServerInitializer(InboundConfig inboundConfig) {
        this(() -> inboundConfig);
    }

    public DnsServerInitializer(Supplier<InboundConfig> inboundConfigSupplier) {
        super(Objects.requireNonNull(inboundConfigSupplier, "inboundConfigSupplier").get());
        this.inboundConfigSupplier = inboundConfigSupplier;
    }

    @Override
    protected void init(Channel ch) {
        ch.pipeline().addLast(new DatagramDnsQueryDecoder());
        ch.pipeline().addLast(new UdpDnsQueryHandler(inboundConfigSupplier));
        ch.pipeline().addLast(new DatagramDnsResponseEncoder());
    }

    @Override
    protected void initAfterRoute(Channel channel) {
        channel.pipeline().addLast(new FakeIpDnsAnswerHandler());
    }

    @Override
    protected ChannelHandler getTerminalExceptionHandler() {
        return DnsExceptionHandler.INSTANCE;
    }

    protected void processSSL(SocketChannel socketChannel) {
        // no-op for UDP DNS
    }

    protected void pipeLineContextInit(Channel socketChannel) {
        ChannelAttributes.setInboundConfig(socketChannel, inboundConfig);
    }
}
