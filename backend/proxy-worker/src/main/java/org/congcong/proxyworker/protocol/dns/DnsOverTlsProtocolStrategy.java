package org.congcong.proxyworker.protocol.dns;

import com.google.common.cache.Cache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;

import java.net.InetSocketAddress;

@Slf4j
public class DnsOverTlsProtocolStrategy extends AbstractDnsProxyProtocolStrategy {

    public DnsOverTlsProtocolStrategy() {
        super("dnsDot");
    }

    @Override
    protected void sendQuery(Channel outbound, int outboundId, DnsProxyContext dnsCtx) {
        DefaultDnsQuestion question = new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());
        DefaultDnsQuery dnsQuery = new DefaultDnsQuery(outboundId);
        dnsQuery.setRecursionDesired(true);
        dnsQuery.addRecord(DnsSection.QUESTION, question);

        log.debug("Send DoT query: upstream={} client={} inboundId={} outboundId={} qname={} qtype={}",
                outbound.remoteAddress(),
                dnsCtx.getClient(),
                dnsCtx.getId(),
                outboundId,
                question.name(),
                question.type().name());

        outbound.writeAndFlush(dnsQuery);
    }

    @Override
    protected SimpleChannelInboundHandler<? extends DnsMessage> buildResponseHandler(
            Channel inbound,
            AttributeKey<Cache<Integer, Pending>> pendingKey) {

        return new SimpleChannelInboundHandler<DnsResponse>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DnsResponse resp) {
                Cache<Integer, Pending> pending = ctx.channel().attr(pendingKey).get();
                if (pending == null) {
                    log.warn("DoT: pending map missing");
                    return;
                }
                handleResponse("DoT", inbound, pending, resp);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                log.warn("DotDnsResponseHandler exception: {}", cause.getMessage(), cause);
                ctx.close();
            }
        };
    }

}
