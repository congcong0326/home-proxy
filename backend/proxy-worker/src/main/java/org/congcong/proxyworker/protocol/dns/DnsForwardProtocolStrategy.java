package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

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
            AttributeKey<ConcurrentMap<Integer, Pending>> pendingKey) {
        return new SimpleChannelInboundHandler<DatagramDnsResponse>() {

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsResponse resp) {
                ConcurrentMap<Integer, Pending> pending = ctx.channel().attr(pendingKey).get();
                if (pending == null) {
                    log.debug("DNS forward: missing pending map");
                    return;
                }
                Pending entry = pending.remove(resp.id());
                if (entry == null) {
                    log.debug("DNS forward: no pending entry for id={}", resp.id());
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

                AccessLogUtil.logDns(inbound, dnsCtx, clientResp.code());
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
                log.warn("DnsForward response handler exception: {}", cause.getMessage(), cause);
                ctx.close();
            }
        };
    }
}