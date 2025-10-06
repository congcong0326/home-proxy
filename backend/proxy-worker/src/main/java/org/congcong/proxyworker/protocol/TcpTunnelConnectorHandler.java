package org.congcong.proxyworker.protocol;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

@ChannelHandler.Sharable
@Slf4j
public class TcpTunnelConnectorHandler extends SimpleChannelInboundHandler<ProxyTunnelRequest>  {

    private TcpTunnelConnectorHandler() {

    }

    public static TcpTunnelConnectorHandler getInstance() {
        return TcpTunnelConnectorHandler.Holder.INSTANCE;
    }

    private static class Holder {
        private static final TcpTunnelConnectorHandler INSTANCE = new TcpTunnelConnectorHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
        ProtocolType protocol = inboundConfig.getProtocol();
        // todo 根据 ProxyTunnelRequest 中的相关内容，建立与代理服务器之间的连接
        // 需要注意 SOCKS5 在建立连接成功后根据协议会写回成功 DefaultSocks5CommandResponse
        // HTTPS_CONNECT 协议会写回 "HTTP/1.1 200 Connection Established\r\n" +
        //                "Proxy-agent: https://github.com/cong/cong\r\n" +
        //                "\r\n";
        // shadowsock不需要写回数据，但是可能需要处理 initialPayload
        // 还需要注意 initialPayload 可能并不完成，与目标服务器建立连接也是一个异步过程，是否许需要一个前置的处理器，在与目标服务器建立成功前需要持续接受 initialPayload
    }
}
