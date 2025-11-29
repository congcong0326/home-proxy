package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.FindUser;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.InetSocketAddress;

@Slf4j
public class UdpDnsQueryHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
        InetSocketAddress client = query.sender();

        // 取第一个 Question（一般就一个）
        DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        if (question == null) {
            return;
        }

        String qName = question.name();
        int dnsId = query.id();
        DnsRecordType qType = question.type();

        log.debug("DNS query from {} name={} type={}", client, qName, qType);


        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        String clientIp = client.getAddress().getHostAddress();
        UserConfig userConfig = FindUser.find(clientIp, inboundConfig);
        proxyContext.setUserName(userConfig.getUsername());
        proxyContext.setUserId(userConfig.getId());
        proxyContext.setClientIp(clientIp);

        DnsProxyContext dnsCtx = new DnsProxyContext(dnsId, qName, qType, client);

        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.DNS_SERVER,
                qName,
                53,
                userConfig,
                inboundConfig,
                dnsCtx
        );

        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(ctx.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        ctx.fireChannelRead(tunnelRequest);
    }

}
