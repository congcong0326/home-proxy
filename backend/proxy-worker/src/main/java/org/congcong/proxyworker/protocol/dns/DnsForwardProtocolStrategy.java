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
public class DnsForwardProtocolStrategy extends AbstractDnsProxyProtocolStrategy {


    public DnsForwardProtocolStrategy() {
        super("dnsForward");
    }

    @Override
    protected void sendQuery(Channel outbound, int outboundId, DnsProxyContext dnsCtx) {
        InetSocketAddress upstream = (InetSocketAddress) outbound.remoteAddress();
        DefaultDnsQuestion q = new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());

        DatagramDnsQuery query = new DatagramDnsQuery(null, upstream, outboundId);
        query.setRecursionDesired(true);
        query.addRecord(DnsSection.QUESTION, q);

        log.debug("Send DNS query upstream={} client={} inboundId={} outboundId={} qname={} qtype={}",
                upstream, dnsCtx.getClient(), dnsCtx.getId(), outboundId, q.name(), q.type().name());

        outbound.writeAndFlush(query);
    }

    @Override
    protected SimpleChannelInboundHandler<? extends DnsMessage> buildResponseHandler(
            Channel inbound,
            AttributeKey<Cache<Integer, Pending>> pendingKey) {
        return new SimpleChannelInboundHandler<DatagramDnsResponse>() {

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsResponse resp) {
                Cache<Integer, Pending> pending = ctx.channel().attr(pendingKey).get();
                if (pending == null) {
                    log.debug("DNS forward: missing pending map");
                    return;
                }
                handleResponse("DNS forward", inbound, pending, resp);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                log.warn("DnsForward response handler exception: {}", cause.getMessage(), cause);
                ctx.close();
            }
        };
    }
}
