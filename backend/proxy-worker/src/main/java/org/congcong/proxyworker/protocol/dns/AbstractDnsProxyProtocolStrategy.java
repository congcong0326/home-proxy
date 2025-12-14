package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractDnsProxyProtocolStrategy implements ProtocolStrategy {

    private final AttributeKey<ConcurrentMap<Integer, Pending>> pendingKey;
    private final AttributeKey<AtomicInteger> nextIdKey;

    protected record Pending(int inboundId, DnsProxyContext ctx, ProxyContext proxyContext, ProxyTimeContext timeContext) {
    }

    protected AbstractDnsProxyProtocolStrategy(String keyPrefix) {
        this.pendingKey = AttributeKey.valueOf(keyPrefix + "Pending");
        this.nextIdKey = AttributeKey.valueOf(keyPrefix + "NextId");
    }

    @Override
    public boolean needRelay() {
        return false;
    }

    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundCtx,
                                 Channel outbound,
                                 ProxyTunnelRequest request) {
        DnsProxyContext dnsCtx = (DnsProxyContext) request.getProtocolAttachment();
        if (dnsCtx == null) {
            log.warn("{}: missing DnsProxyContext", getClass().getSimpleName());
            return;
        }

        ConcurrentMap<Integer, Pending> pending = outbound.attr(pendingKey).get();
        if (pending == null) {
            pending = new ConcurrentHashMap<>();
            outbound.attr(pendingKey).set(pending);
            outbound.pipeline().addLast(buildResponseHandler(inboundCtx.channel(), pendingKey));
        }
        AtomicInteger nextId = outbound.attr(nextIdKey).get();
        if (nextId == null) {
            nextId = new AtomicInteger();
            outbound.attr(nextIdKey).set(nextId);
        }

        int outboundId = allocateId(pending, nextId);
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(inboundCtx.channel(), request);
        ProxyTimeContext timeContext = ProxyContextResolver.resolveProxyTimeContext(inboundCtx.channel(), request);
        pending.put(outboundId, new Pending(dnsCtx.getId(), dnsCtx, proxyContext, timeContext));

        sendQuery(outbound, outboundId, dnsCtx);
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundCtx,
                                 Channel outbound,
                                 ProxyTunnelRequest request,
                                 Throwable cause) {
        if (request.getProtocolAttachment() instanceof DnsProxyContext dnsCtx) {
            Channel inbound = inboundCtx.channel();
            InetSocketAddress client = dnsCtx.getClient();
            DefaultDnsQuestion q = new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());
            DatagramDnsResponse resp = new DatagramDnsResponse(
                    (InetSocketAddress) inbound.localAddress(), client, dnsCtx.getId());
            resp.addRecord(DnsSection.QUESTION, q);
            resp.setCode(DnsResponseCode.SERVFAIL);
            inbound.writeAndFlush(resp);
            AccessLogUtil.logDns(request.getProxyContext(), request.getProxyTimeContext(), dnsCtx, resp.code());
        }
        log.warn("{} upstream connect failed: {}", getClass().getSimpleName(), cause.getMessage(), cause);
    }

    protected int allocateId(ConcurrentMap<Integer, Pending> pending, AtomicInteger nextId) {
        for (int i = 0; i < 0x10000; i++) {
            int candidate = nextId.getAndIncrement() & 0xFFFF;
            if (!pending.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("No available DNS IDs");
    }

    protected abstract void sendQuery(Channel outbound, int outboundId, DnsProxyContext dnsCtx);

    protected abstract SimpleChannelInboundHandler<? extends DnsMessage>
    buildResponseHandler(Channel inbound, AttributeKey<ConcurrentMap<Integer, Pending>> pendingKey);
}
