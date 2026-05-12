package org.congcong.proxyworker.protocol;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Promise;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

class ProxyTunnelConnectorHandlerTest {

    @Test
    void removesRequestAppendHandlerWhenRelayIsReady() {
        RequestAppendHandler appendHandler = new RequestAppendHandler(1024);
        EmbeddedChannel inboundChannel = new EmbeddedChannel(appendHandler);
        EmbeddedChannel outboundChannel = new EmbeddedChannel();
        ChannelHandlerContext ctx = inboundChannel.pipeline().context(appendHandler);
        ProxyTunnelRequest request = request();

        Promise<Channel> relayPromise = ProxyTunnelConnectorHandler.getInstance()
                .getRelayPromise(ctx, request);

        assertNotNull(inboundChannel.pipeline().get(RequestAppendHandler.class));

        relayPromise.setSuccess(outboundChannel);
        inboundChannel.runPendingTasks();

        assertNull(inboundChannel.pipeline().get(RequestAppendHandler.class));
        inboundChannel.finishAndReleaseAll();
        outboundChannel.finishAndReleaseAll();
    }

    private ProxyTunnelRequest request() {
        InboundConfig inboundConfig = new InboundConfig();
        inboundConfig.setProtocol(ProtocolType.SHADOW_SOCKS);
        return new ProxyTunnelRequest(
                ProtocolType.SHADOW_SOCKS,
                "example.com",
                443,
                new UserConfig(),
                inboundConfig,
                null);
    }
}
