package org.congcong.proxyworker.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.dns.*;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

public final class DirectUdpOutboundConnector implements UdpOutboundConnector {
    @Override
    public Future<Channel> open(UdpSession session, EventLoop eventLoop) {
        Promise<Channel> result = eventLoop.newPromise();
        String host = session.request().getTargetHost();
        var literal = DestinationAddressPolicy.parseLiteral(host);
        if (literal.isPresent()) {
            connect(session, eventLoop, literal.get(), result);
        } else if (session.request().getDnsInboundConfig() == null) {
            result.tryFailure(new IllegalStateException("UDP domain destination requires a DNS view"));
        } else {
            var dns = session.request().getDnsInboundConfig();
            DnsQueryContext context = new DnsQueryContext(session.key().client().getAddress().getHostAddress(),
                    dns, dns.getConfigVersion());
            CompletableFuture<DnsResult> lookup = OutboundAddressQuery.queryAddresses(host, context, eventLoop);
            session.dnsLookup = lookup;
            lookup.whenComplete((answer, error) -> eventLoop.execute(() -> {
                if (session.isClosed()) {
                    result.tryFailure(new IllegalStateException("UDP session closed during DNS"));
                    return;
                }
                if (error != null || answer.code() != DnsResponseCode.NOERROR || answer.addresses().isEmpty()) {
                    result.tryFailure(error != null ? error : new IllegalStateException("UDP DNS returned no address"));
                    return;
                }
                try {
                    if (answer.addresses().stream().anyMatch(FakeIpRegistry::isInPool)) {
                        throw new IllegalArgumentException("UDP DNS result belongs to Fake IP pool");
                    }
                    connect(session, eventLoop, InetAddresses.forString(answer.addresses().get(0)), result);
                } catch (Exception failure) {
                    result.tryFailure(failure);
                }
            }));
        }
        return result;
    }

    private void connect(UdpSession session, EventLoop eventLoop, InetAddress address, Promise<Channel> result) {
        if (session.isClosed() || FakeIpRegistry.isInPool(address.getHostAddress())) {
            result.tryFailure(new IllegalArgumentException("closed session or Fake IP destination"));
            return;
        }
        Bootstrap bootstrap = new Bootstrap().group(eventLoop).channel(EpollDatagramChannel.class)
                .option(ChannelOption.RCVBUF_ALLOCATOR,
                        new FixedRecvByteBufAllocator(session.manager.settings().maxDatagramSize()))
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        // Track immediately, including bind/connect failure and close-during-connect.
                        if (session.isClosed()) { channel.close(); return; }
                        channel.pipeline().addLast(new IdleStateHandler(0, 0,
                                session.manager.settings().idleTimeoutSeconds()), new DirectUdpRelayHandler(session));
                    }
                });
        InetSocketAddress local = new InetSocketAddress(address.getAddress().length == 4 ? "0.0.0.0" : "::", 0);
        ChannelFuture connecting = bootstrap.connect(new InetSocketAddress(address, session.request().getTargetPort()), local);
        Channel channel = connecting.channel();
        // Close owns the channel even before the open future succeeds.
        session.connectingChannel = channel;
        connecting.addListener((ChannelFutureListener) done -> {
            if (session.isClosed()) {
                channel.close();
                result.tryFailure(new IllegalStateException("UDP session closed during connect"));
            } else if (done.isSuccess()) {
                result.trySuccess(channel);
            } else {
                channel.close();
                result.tryFailure(done.cause());
            }
        });
    }

    private static final class DirectUdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final UdpSession session;
        private DirectUdpRelayHandler(UdpSession session) { this.session = session; }

        @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            session.manager.reply(session, packet.content().retain());
        }

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE) session.close("idle");
            else super.userEventTriggered(ctx, event);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            session.close("outbound_error");
        }
    }
}
