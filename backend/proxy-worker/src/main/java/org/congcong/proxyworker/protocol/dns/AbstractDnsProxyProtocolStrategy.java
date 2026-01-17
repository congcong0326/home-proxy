package org.congcong.proxyworker.protocol.dns;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractDnsProxyProtocolStrategy implements ProtocolStrategy {

    private static final int MAX_PENDING = 65535;
    private static final int PENDING_TTL_SECONDS = 20;

    private final AttributeKey<Cache<Integer, Pending>> pendingKey;
    private final AttributeKey<AtomicInteger> nextIdKey;

    protected record Pending(int inboundId, DnsProxyContext ctx, ProxyContext proxyContext, ProxyTimeContext timeContext) {
    }

    protected AbstractDnsProxyProtocolStrategy(String keyPrefix) {
        this.pendingKey = AttributeKey.valueOf(keyPrefix + "PendingCache");
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

        // 第一次请求初始化暂存请求的缓存，并且注册一个响应处理器，处理返回的内容
        // 由于netty dns 服务器单线程特性，不需要担心此处的线程安全问题
        Cache<Integer, Pending> pending = outbound.attr(pendingKey).get();
        if (pending == null) {
            pending = buildPendingCache();
            outbound.attr(pendingKey).set(pending);
            outbound.pipeline().addLast(buildResponseHandler(inboundCtx.channel(), pendingKey));
        }


        AtomicInteger nextId = outbound.attr(nextIdKey).get();
        if (nextId == null) {
            nextId = new AtomicInteger();
            outbound.attr(nextIdKey).set(nextId);
        }
        // 分配一个0–65535的id
        int outboundId = allocateId(pending, nextId);
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(inboundCtx.channel(), request);
        ProxyTimeContext timeContext = ProxyContextResolver.resolveProxyTimeContext(inboundCtx.channel(), request);
        // 通过缓存维护关系：外部dns服务器----outboundId-----缓存----dnsCtx.id-----内部请求客户端
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

    protected int allocateId(Cache<Integer, Pending> pending, AtomicInteger nextId) {
        // 触发Guava把已过期的条目马上驱逐
        pending.cleanUp();
        for (int i = 0; i < MAX_PENDING; i++) {
            int candidate = nextId.getAndIncrement() & 0xFFFF;
            if (!pending.asMap().containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("No available DNS IDs");
    }

    private Cache<Integer, Pending> buildPendingCache() {
        return CacheBuilder.newBuilder()
                .maximumSize(MAX_PENDING)
                .expireAfterWrite(PENDING_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    protected abstract void sendQuery(Channel outbound, int outboundId, DnsProxyContext dnsCtx);

    protected abstract SimpleChannelInboundHandler<? extends DnsMessage>
    buildResponseHandler(Channel inbound, AttributeKey<Cache<Integer, Pending>> pendingKey);
}
