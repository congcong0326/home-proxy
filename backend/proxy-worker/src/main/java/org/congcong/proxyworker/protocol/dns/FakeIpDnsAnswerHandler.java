package org.congcong.proxyworker.protocol.dns;

import com.google.common.net.InetAddresses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.dns.DnsQueryContext;
import org.congcong.proxyworker.dns.DnsQueryService;
import org.congcong.proxyworker.dns.DnsResult;
import org.congcong.proxyworker.dns.QueryMode;
import org.congcong.proxyworker.protocol.dns.util.DnsMessageUtil;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Encodes shared DNS service results for client A/AAAA queries. */
@Slf4j
final class FakeIpDnsAnswerHandler extends SimpleChannelInboundHandler<ProxyTunnelRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyTunnelRequest request) {
        if (!(request.getProtocolAttachment() instanceof DnsProxyContext dns)
                || (dns.getQType() != DnsRecordType.A && dns.getQType() != DnsRecordType.AAAA)) {
            ctx.fireChannelRead(request);
            return;
        }
        CompletableFuture<DnsResult> pending;
        try {
            String clientIp = request.getProxyContext() == null ? dns.getClient().getAddress().getHostAddress()
                    : request.getProxyContext().getClientIp();
            DnsQueryContext queryContext = new DnsQueryContext(clientIp, request.getInboundConfig(),
                    request.getInboundConfig().getConfigVersion());
            pending = DnsQueryService.getInstance().query(dns.getQName(), dns.getQType(), QueryMode.CLIENT_FAKE,
                    queryContext, ctx.channel().eventLoop());
        } catch (RuntimeException failure) {
            // Validation and route selection can throw before returning a Future.
            // Fail this question, never the shared listening channel.
            log.warn("DNS 请求处理失败: name={}, reason={}", dns.getQName(), failure.toString());
            DnsResponseCode code = failure instanceof IllegalArgumentException
                    ? DnsResponseCode.FORMERR : DnsResponseCode.SERVFAIL;
            writeResponse(ctx, request, dns, new DnsResult(code, List.of(), 0, 0, false, null), null);
            return;
        }
        ChannelFutureListener cancelOnClose = closed -> pending.cancel(false);
        ctx.channel().closeFuture().addListener(cancelOnClose);
        pending.whenComplete((result, failure) -> {
            ctx.channel().closeFuture().removeListener(cancelOnClose);
            if (ctx.channel().isActive()) writeResponse(ctx, request, dns, result, failure);
        });
    }

    private void writeResponse(ChannelHandlerContext ctx, ProxyTunnelRequest request,
                               DnsProxyContext dns, DnsResult result, Throwable failure) {
        DatagramDnsResponse response = new DatagramDnsResponse(
                (InetSocketAddress) ctx.channel().localAddress(), dns.getClient(), dns.getId());
        response.setRecursionDesired(true);
        response.setRecursionAvailable(true);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(dns.getQName(), dns.getQType()));
        List<String> answerIps = failure == null ? result.addresses() : List.of();
        if (failure == null) {
            if (request.getProxyContext() != null) request.getProxyContext().setDnsRouteId(result.routeId());
            response.setCode(result.code());
            for (String address : result.addresses()) {
                byte[] bytes = InetAddresses.forString(address).getAddress();
                response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(dns.getQName(), dns.getQType(),
                        result.ttlSeconds(), ctx.alloc().buffer(bytes.length).writeBytes(bytes)));
            }
        } else {
            response.setCode(DnsResponseCode.SERVFAIL);
        }
        if (request.getProxyContext() != null) {
            request.getProxyContext().setBytesOut(DnsMessageUtil.estimateMessageSize(response));
        }
        long telemetryResponseBytes = DnsMessageUtil.estimateMessageSize(response);
        int telemetryRcode = response.code().intValue();
        ctx.channel().writeAndFlush(response).addListener(sent -> {
            if (request.getTelemetryDnsObservation() != null) {
                request.getTelemetryDnsObservation().complete(request, result, failure, telemetryRcode,
                        answerIps, sent.isSuccess(), telemetryResponseBytes);
            }
        });
        try {
            AccessLogUtil.logDns(request.getProxyContext(), request.getProxyTimeContext(), dns,
                    response.code(), answerIps);
        } catch (RuntimeException | LinkageError auditFailure) {
            log.warn("DNS response audit failed for qname={}: {}", dns.getQName(), auditFailure.toString());
        }
    }
}
