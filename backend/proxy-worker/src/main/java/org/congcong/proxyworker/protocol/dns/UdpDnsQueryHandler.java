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
import org.congcong.proxyworker.util.ProxyContextFillUtil;
import org.congcong.proxyworker.protocol.dns.util.DnsMessageUtil;

import java.net.InetSocketAddress;

@Slf4j
public class UdpDnsQueryHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
        ProxyTimeContext proxyTimeContext = new ProxyTimeContext();
        InetSocketAddress client = query.sender();

        // 取第一个 Question（一般就一个）
        DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        if (question == null) {
            return;
        }

        String qName = question.name();
        // DNS 常用 UDP（无连接），同一时刻可能并发发出多条查询
        // 服务器返回响应时会带上同一个 Transaction ID，客户端/解析器用它把“这条响应”匹配回“哪一条请求”。
        int dnsId = query.id();
        DnsRecordType qType = question.type();

        log.debug("DNS query from {} name={} type={}", client, qName, qType);


        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        String clientIp = client.getAddress().getHostAddress();
        UserConfig userConfig = FindUser.find(clientIp, inboundConfig);

        ProxyContext proxyContext = new ProxyContext();
        proxyContext.setProxyId(inboundConfig.getId() == null ? 0 : inboundConfig.getId());
        proxyContext.setProxyName(inboundConfig.getName());
        proxyContext.setInboundProtocolType(inboundConfig.getProtocol());
        proxyContext.setInboundProxyEncAlgo(inboundConfig.getSsMethod());
        proxyContext.setUserName(userConfig.getUsername());
        proxyContext.setUserId(userConfig.getId());
        proxyContext.setClientIp(clientIp);
        proxyContext.setClientPort(client.getPort());

        DnsProxyContext dnsCtx = new DnsProxyContext(dnsId, qName, qType, client);

        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.DNS_SERVER,
                qName,
                53,
                userConfig,
                inboundConfig,
                dnsCtx
        );
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        ProxyContextFillUtil.proxyContextInitFill(ctx.channel(), inboundConfig, proxyContext);
        proxyContext.setBytesIn(DnsMessageUtil.estimateMessageSize(query));
        tunnelRequest.setProxyContext(proxyContext);
        tunnelRequest.setProxyTimeContext(proxyTimeContext);
        ctx.fireChannelRead(tunnelRequest);
    }

}
