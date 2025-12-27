package org.congcong.proxyworker.protocol.transparent;

import io.netty.channel.Channel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

import java.net.InetSocketAddress;

public class TransparentServerInitializer extends AbstractChannelInitializer {

    public TransparentServerInitializer(InboundConfig inboundConfig) {
        super(inboundConfig);
    }

    @Override
    protected void init(Channel socketChannel) {
        socketChannel.pipeline().addLast(new ProtocolDetectHandler());
        socketChannel.pipeline().addLast(new TransparentServerHandler());
    }


    protected void processSSL(Channel socketChannel) {
        // 啥都不做
    }

    protected void pipeLineContextInit(Channel socketChannel) {
        super.pipeLineContextInit(socketChannel);
        // 父类已经初始化过一部分 proxyContext
        // 根据透明代理的特性，好需要设置IP端口
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(socketChannel);
        InetSocketAddress originalDst = (InetSocketAddress) socketChannel.localAddress();
        String targetHost = originalDst.getAddress().getHostAddress();
        int targetPort = originalDst.getPort();
        proxyContext.setOriginalTargetIP(targetHost);
        proxyContext.setOriginalTargetPort(targetPort);
    }

}
