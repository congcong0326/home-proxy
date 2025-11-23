package org.congcong.proxyworker.protocol.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DnsOverTlsProtocolStrategy implements ProtocolStrategy {


    private static final AttributeKey<ConcurrentMap<Integer, PendingEntry>> DOT_PENDING_MAP =
            AttributeKey.valueOf("dotPendingMap");
    private static final AttributeKey<AtomicInteger> DOT_NEXT_ID =
            AttributeKey.valueOf("dotNextId");
    private record PendingEntry(int inboundId, DnsProxyContext ctx) {}

    @Override
    public boolean needRelay() {
        // DoT 不是流式转发，而是请求/响应
        return false;
    }

    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundCtx,
                                 Channel outboundChannel,
                                 ProxyTunnelRequest request) {
        Object attachment = request.getProtocolAttachment();
        if (!(attachment instanceof DnsProxyContext dnsCtx)) {
            log.warn("DnsOverTlsProtocolStrategy: missing DnsProxyContext");
            return;
        }

        ConcurrentMap<Integer, PendingEntry> pending =
                outboundChannel.attr(DOT_PENDING_MAP).get();
        // 出站outboundChannel维护DNS查询的映射关系
        if (pending == null) {
            pending = new ConcurrentHashMap<>();
            outboundChannel.attr(DOT_PENDING_MAP).set(pending);
            outboundChannel.pipeline().addLast(
                    new DotDnsResponseHandler(inboundCtx.channel(), DOT_PENDING_MAP)
            );
        }

        AtomicInteger nextId = outboundChannel.attr(DOT_NEXT_ID).get();
        if (nextId == null) {
            nextId = new AtomicInteger(0);
            outboundChannel.attr(DOT_NEXT_ID).set(nextId);
        }

        int outboundId = allocateId(pending, nextId);
        // 代理服务器分配的查询ID ---> 原始查询ID
        pending.put(outboundId, new PendingEntry(dnsCtx.getId(), dnsCtx));

        DefaultDnsQuestion question =
                new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());

        DefaultDnsQuery dnsQuery = new DefaultDnsQuery(outboundId);
        dnsQuery.setRecursionDesired(true);
        dnsQuery.addRecord(DnsSection.QUESTION, question);

        log.debug("Send DoT query: upstream={} client={} inboundId={} outboundId={} qname={} qtype={}",
                outboundChannel.remoteAddress(),          // DoT 上游地址
                dnsCtx.getClient(),                       // 原始客户端地址
                dnsCtx.getId(),                           // 原始 DNS ID
                outboundId,                               // 分配给 DoT 的 ID
                question.name(),                          // 查询域名
                question.type().name());                  // 查询类型
        outboundChannel.writeAndFlush(dnsQuery);
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundCtx,
                                 Channel outboundChannel,
                                 ProxyTunnelRequest request,
                                 Throwable cause) {
        Object attachment = request.getProtocolAttachment();
        if (attachment instanceof DnsProxyContext dnsCtx) {
            Channel inboundChannel = inboundCtx.channel();
            InetSocketAddress client = dnsCtx.getClient();

            DefaultDnsQuestion question =
                    new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());

            DatagramDnsResponse resp = new DatagramDnsResponse(
                    (InetSocketAddress) inboundChannel.localAddress(),
                    client,
                    dnsCtx.getId()
            );
            resp.addRecord(DnsSection.QUESTION, question);
            resp.setCode(DnsResponseCode.SERVFAIL);

            inboundChannel.writeAndFlush(resp);
        }
        log.warn("DoT upstream connect failed: {}", cause.getMessage(), cause);
    }

    private int allocateId(ConcurrentMap<Integer, PendingEntry> pending, AtomicInteger nextId) {
        for (int i = 0; i < 0x10000; i++) {
            int candidate = nextId.getAndIncrement() & 0xFFFF;
            if (!pending.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available DNS IDs");
    }

    private static class DotDnsResponseHandler extends SimpleChannelInboundHandler<DnsResponse> {

        private final Channel inboundChannel; // UDP 客户端 channel
        private final AttributeKey<ConcurrentMap<Integer, PendingEntry>> pendingKey;

        public DotDnsResponseHandler(Channel inboundChannel,
                                     AttributeKey<ConcurrentMap<Integer, PendingEntry>> pendingKey) {
            this.inboundChannel = inboundChannel;
            this.pendingKey = pendingKey;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DnsResponse resp) throws Exception {
            ConcurrentMap<Integer, PendingEntry> pending = ctx.channel().attr(pendingKey).get();
            if (pending == null) {
                log.warn("No pending map on DoT channel");
                return;
            }

            int outboundId = resp.id();
            PendingEntry entry = pending.remove(outboundId);
            if (entry == null) {
                log.debug("DoT response id={} has no pending context", outboundId);
                return;
            }

            int inboundId = entry.inboundId();
            DnsProxyContext dnsCtx = entry.ctx();
            InetSocketAddress client = dnsCtx.getClient();


//            int qd = resp.count(DnsSection.QUESTION);
//            int an = resp.count(DnsSection.ANSWER);
//            int ns = resp.count(DnsSection.AUTHORITY);
//            int ar = resp.count(DnsSection.ADDITIONAL);

//            StringBuilder answersSb = new StringBuilder();
//            for (int i = 0; i < an; i++) {
//                DnsRecord r = resp.recordAt(DnsSection.ANSWER, i);
//                answersSb.append(r);
//                if (r instanceof DefaultDnsRawRecord raw) {
//                    // 尝试把 A / AAAA 解析成人类可读 IP
//                    try {
//                        ByteBuf content = raw.content().duplicate();
//                        byte[] addr = new byte[content.readableBytes()];
//                        content.getBytes(content.readerIndex(), addr);
//                        InetAddress inetAddress = InetAddress.getByAddress(addr);
//                        answersSb.append(" [ip=").append(inetAddress.getHostAddress()).append("]");
//                    } catch (Exception ignore) {
//                    }
//                }
//                answersSb.append(" ; ");
//            }
//
//            log.debug("DoT response detail: client={} qname={} qtype={} inboundId={} outboundId={} " +
//                            "rcode={} qd={} an={} ns={} ar={} answers={}",
//                    client,
//                    dnsCtx.getQName(),
//                    dnsCtx.getQType(),
//                    inboundId,
//                    outboundId,
//                    resp.code(), qd, an, ns, ar,
//                    answersSb.toString());

            DatagramDnsResponse clientResp = new DatagramDnsResponse(
                    (InetSocketAddress) inboundChannel.localAddress(),
                    client,
                    inboundId
            );

            DnsQuestion q = resp.recordAt(DnsSection.QUESTION);
            if (q != null) {
                // retain 一次，给 clientResp 使用
                ReferenceCountUtil.retain(q);
                clientResp.addRecord(DnsSection.QUESTION, q);
            }

            copySectionWithRetain(resp, clientResp, DnsSection.ANSWER);
            copySectionWithRetain(resp, clientResp, DnsSection.AUTHORITY);
            copySectionWithRetain(resp, clientResp, DnsSection.ADDITIONAL);

            clientResp.setCode(resp.code());

            inboundChannel.writeAndFlush(clientResp);
        }

        private void copySectionWithRetain(DnsMessage from, DatagramDnsResponse to, DnsSection section) {
            int count = from.count(section);
            for (int i = 0; i < count; i++) {
                DnsRecord r = from.recordAt(section, i);
                // retain 给 to 持有
                ReferenceCountUtil.retain(r);
                to.addRecord(section, r);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("DotDnsResponseHandler exception: {}", cause.getMessage(), cause);
            ctx.close();
        }
    }
}
