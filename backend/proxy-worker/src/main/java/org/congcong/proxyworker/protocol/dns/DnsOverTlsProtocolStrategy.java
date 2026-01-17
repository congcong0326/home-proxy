package org.congcong.proxyworker.protocol.dns;

import com.google.common.cache.Cache;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

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
                Pending entry = pending.asMap().remove(resp.id());
                if (entry == null) {
                    log.debug("DoT response id={} has no pending entry", resp.id());
                    return;
                }

                DnsProxyContext dnsCtx = entry.ctx();
                DatagramDnsResponse clientResp = new DatagramDnsResponse(
                        (InetSocketAddress) inbound.localAddress(),
                        dnsCtx.getClient(),
                        entry.inboundId()
                );

                DnsQuestion q = resp.recordAt(DnsSection.QUESTION);
                if (q != null) {
                    ReferenceCountUtil.retain(q);
                    clientResp.addRecord(DnsSection.QUESTION, q);
                }
                copySection(resp, clientResp, DnsSection.ANSWER);
                copySection(resp, clientResp, DnsSection.AUTHORITY);
                copySection(resp, clientResp, DnsSection.ADDITIONAL);
                clientResp.setCode(resp.code());

                AccessLogUtil.logDns(entry.proxyContext(), entry.timeContext(), dnsCtx, clientResp.code());
                inbound.writeAndFlush(clientResp);
            }

            private void copySection(DnsMessage from, DatagramDnsResponse to, DnsSection section) {
                int count = from.count(section);
                for (int i = 0; i < count; i++) {
                    DnsRecord r = from.recordAt(section, i);
                    ReferenceCountUtil.retain(r);
                    to.addRecord(section, r);
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                log.warn("DotDnsResponseHandler exception: {}", cause.getMessage(), cause);
                ctx.close();
            }
        };
    }

}
