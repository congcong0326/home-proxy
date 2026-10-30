package org.congcong.proxyworker.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/** Event-loop-confined, send-only sockets bound to original destinations. */
public class ReplySocketPool {
    private final EventLoop eventLoop;
    private final UdpRelayCounters counters;
    private final Map<InetSocketAddress, ReplySocket> sockets = new HashMap<>();

    public static final class ReplySocket {
        private final ChannelFuture bound;
        private int references = 1;
        private ReplySocket(ChannelFuture bound) { this.bound = bound; }
        public Channel channel() { return bound.channel(); }
        public Future<Void> ready() { return bound; }
        public int references() { return references; }
    }

    public ReplySocketPool(EventLoop eventLoop, UdpRelayCounters counters) {
        this.eventLoop = eventLoop;
        this.counters = counters;
    }

    protected ChannelFuture bind(InetSocketAddress target) {
        return new Bootstrap().group(eventLoop).channel(EpollDatagramChannel.class)
                .option(EpollChannelOption.IP_TRANSPARENT, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInboundHandlerAdapter()).bind(target);
    }

    public ReplySocket acquire(InetSocketAddress target) {
        ReplySocket existing = sockets.get(target);
        if (existing != null) {
            existing.references++;
            return existing;
        }
        ReplySocket created = new ReplySocket(bind(target));
        sockets.put(target, created);
        counters.increment("reply_sockets");
        return created;
    }

    public void release(InetSocketAddress target, ReplySocket socket) {
        // Identity matters if a failed/closed socket is replaced for the same T.
        if (--socket.references == 0) {
            if (sockets.remove(target, socket)) counters.add("reply_sockets", -1);
            socket.channel().close();
        }
    }

    public int size() { return sockets.size(); }
}
