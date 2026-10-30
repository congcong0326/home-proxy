package org.congcong.proxyworker.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone privileged probe, invoked only in isolated namespaces by the shell harness. */
public final class UdpOriginalDestinationProbe {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        EpollEventLoopGroup group = new EpollEventLoopGroup(1);
        CountDownLatch received = new CountDownLatch(2);
        UdpRelayCounters counters = new UdpRelayCounters();
        ReplySocketPool pool = new ReplySocketPool(group.next(), counters);
        try {
            Channel listener = new Bootstrap().group(group).channel(EpollDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    .option(EpollChannelOption.IP_RECVORIGDSTADDR, true)
                    .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(65535))
                    .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                            if (!packet.recipient().equals(new InetSocketAddress("198.18.2.3", port))) {
                                throw new AssertionError("wrong original target: " + packet.recipient());
                            }
                            System.out.println("recipient=" + packet.recipient() + " bytes=" + packet.content().readableBytes());
                            ReplySocketPool.ReplySocket reply = pool.acquire(packet.recipient());
                            DatagramPacket response = new DatagramPacket(packet.content().retain(), packet.sender());
                            reply.ready().addListener(bound -> {
                                if (!bound.isSuccess()) { response.release(); throw new AssertionError(bound.cause()); }
                                reply.channel().writeAndFlush(response).addListener(sent -> {
                                    if (!sent.isSuccess()) throw new AssertionError(sent.cause());
                                    received.countDown();
                                });
                            });
                        }
                    }).bind("0.0.0.0", 39992).sync().channel();
            System.out.println("READY");
            if (!received.await(15, TimeUnit.SECONDS)) throw new AssertionError("probe timed out");
            if (pool.size() != 1) throw new AssertionError("reply socket was not shared");
            listener.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
