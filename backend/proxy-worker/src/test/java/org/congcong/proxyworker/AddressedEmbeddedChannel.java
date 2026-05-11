package org.congcong.proxyworker;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.SocketAddress;

public class AddressedEmbeddedChannel extends EmbeddedChannel {
    private final SocketAddress localAddress;
    private final SocketAddress remoteAddress;

    public AddressedEmbeddedChannel(SocketAddress localAddress,
                                    SocketAddress remoteAddress,
                                    ChannelHandler... handlers) {
        super();
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        if (handlers != null && handlers.length > 0) {
            pipeline().addLast(handlers);
        }
    }

    @Override
    public SocketAddress localAddress() {
        return localAddress == null ? super.localAddress() : localAddress;
    }

    @Override
    public SocketAddress remoteAddress() {
        return remoteAddress == null ? super.remoteAddress() : remoteAddress;
    }
}
