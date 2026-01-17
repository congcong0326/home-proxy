package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetSocketAddress;

public class DnsRewritingProtocolStrategy implements ProtocolStrategy {


    @Override
    public boolean needRelay() {
        // DoT 不是流式转发，而是请求/响应
        return false;
    }

    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundCtx, Channel outboundChannel, ProxyTunnelRequest request) {
        // dns 查询请求
        DnsProxyContext dnsCtx = (DnsProxyContext) request.getProtocolAttachment();
        InetSocketAddress client = dnsCtx.getClient();
        String answerIp = request.getRouteConfig().getOutboundProxyHost(); // 仅支持 IPv4
        DefaultDnsQuestion question =
                new DefaultDnsQuestion(dnsCtx.getQName(), dnsCtx.getQType());

        DatagramDnsResponse resp = new DatagramDnsResponse(
                (InetSocketAddress) inboundCtx.channel().localAddress(),
                client,
                dnsCtx.getId());
        resp.addRecord(DnsSection.QUESTION, question);

        try {
            byte[] addr = NetUtil.createByteArrayFromIpAddressString(answerIp);
            if (addr != null) {
                if (addr.length == 4 && dnsCtx.getQType() == DnsRecordType.A) {
                    resp.addRecord(DnsSection.ANSWER,
                            new DefaultDnsRawRecord(dnsCtx.getQName(), DnsRecordType.A, 60,
                                    inboundCtx.alloc().buffer(addr.length).writeBytes(addr)));
                } // 其他类型可选返回空答案
                resp.setCode(DnsResponseCode.NOERROR);
            } else {
                resp.setCode(DnsResponseCode.SERVFAIL);
            }
        } catch (Exception e) {
            resp.setCode(DnsResponseCode.SERVFAIL);
        }

        inboundCtx.channel().writeAndFlush(resp);
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause) {

    }
}
