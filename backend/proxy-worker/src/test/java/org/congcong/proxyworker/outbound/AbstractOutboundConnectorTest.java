package org.congcong.proxyworker.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AbstractOutboundConnectorTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("proxyworker.netty.epoll.enabled");
    }

    @Test
    void disablesEpollSocketChannelsWhenSystemPropertyIsFalse() {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");

        TestConnector connector = new TestConnector();

        assertEquals(NioSocketChannel.class, connector.socketChannel());
        assertEquals(NioDatagramChannel.class, connector.datagramChannel());
    }

    private static final class TestConnector extends AbstractOutboundConnector {
        Class<?> socketChannel() {
            return getSocketChannel();
        }

        Class<?> datagramChannel() {
            return getDatagramChannel();
        }

        @Override
        public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
            return null;
        }
    }
}
