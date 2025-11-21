package org.congcong.proxyworker.outbound;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public abstract class AbstractOutboundConnector implements OutboundConnector {


    protected Class<? extends SocketChannel> getSocketChannel() {
        boolean available = Epoll.isAvailable();
        if (available) {
            return EpollSocketChannel.class;
        }
        return NioSocketChannel.class;
    }

}
