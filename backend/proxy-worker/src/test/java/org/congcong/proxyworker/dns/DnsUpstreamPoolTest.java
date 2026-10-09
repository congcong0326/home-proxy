package org.congcong.proxyworker.dns;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.dns.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.util.ReferenceCountUtil;
import org.congcong.common.enums.ProtocolType;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class DnsUpstreamPoolTest {
    @Test
    void failedWriteReleasesAllPendingQueriesAndNextQueryUsesANewChannel() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                var pending = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "pending-before-write-failure.test", DnsRecordType.A, 81L);
                upstream.receive(new DatagramPacket(new byte[512], 512));
                Channel original = upstreamChannel(pool, loop);
                // Model the Network-unreachable write seen after a WAN address change.
                loop.submit(() -> original.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                        ReferenceCountUtil.release(message);
                        promise.setFailure(new IOException("Network is unreachable"));
                    }
                })).get(2, TimeUnit.SECONDS);
                var failed = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "write-failure.test", DnsRecordType.A, 81L);
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                        () -> failed.get(2, TimeUnit.SECONDS)).getCause());
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                        () -> pending.get(2, TimeUnit.SECONDS)).getCause());
                assertTrue(original.closeFuture().await(2, TimeUnit.SECONDS));
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));

                var recovered = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "after-write-failure.test", DnsRecordType.A, 81L);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                byte[] reply = response(Arrays.copyOf(packet.getData(), packet.getLength()), false);
                upstream.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                assertEquals(java.util.List.of("203.0.113.7"), recovered.get(2, TimeUnit.SECONDS).addresses());
                assertNotSame(original, upstreamChannel(pool, loop));
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void repeatedTimeoutsRetireChannelAndTheSamePoolRecovers() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                var first = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "timeout-first.test", DnsRecordType.A, 82L);
                var second = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "timeout-second.test", DnsRecordType.A, 82L);
                upstream.receive(new DatagramPacket(new byte[512], 512));
                upstream.receive(new DatagramPacket(new byte[512], 512));
                Channel original = upstreamChannel(pool, loop);
                assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class,
                        () -> first.get(8, TimeUnit.SECONDS)).getCause());
                assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class,
                        () -> second.get(2, TimeUnit.SECONDS)).getCause());
                assertTrue(original.closeFuture().await(2, TimeUnit.SECONDS));
                assertEquals(2L, pool.timeoutsByRoute().get(82L));
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));

                var recovered = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "after-timeouts.test", DnsRecordType.A, 82L);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                byte[] reply = response(Arrays.copyOf(packet.getData(), packet.getLength()), false);
                upstream.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                assertEquals(java.util.List.of("203.0.113.7"), recovered.get(2, TimeUnit.SECONDS).addresses());
                assertNotSame(original, upstreamChannel(pool, loop));
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    private Channel upstreamChannel(DnsUpstreamPool pool, EventLoop loop) throws Exception {
        return loop.submit(() -> {
            var transportsField = DnsUpstreamPool.class.getDeclaredField("transports");
            transportsField.setAccessible(true);
            var transports = (java.util.Map<?, ?>) transportsField.get(pool);
            assertEquals(1, transports.size());
            Object transport = transports.values().iterator().next();
            var connectionField = transport.getClass().getDeclaredField("connection");
            connectionField.setAccessible(true);
            return ((io.netty.channel.ChannelFuture) connectionField.get(transport)).channel();
        }).get(2, TimeUnit.SECONDS);
    }

    @Test
    void malformedCnameCompletesQueryAndAllowsNextQuery() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                var failed = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "malformed-cname.test", DnsRecordType.A, 77L);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                // Four maximum-length labels exceed the 255-byte DNS wire limit.
                byte[] malformed = Arrays.copyOf(response(request, false), request.length + 12 + 257);
                System.arraycopy(new byte[]{(byte) 0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 3, 1, 1},
                        0, malformed, request.length, 12);
                Arrays.fill(malformed, request.length + 12, malformed.length, (byte) 'a');
                for (int index = request.length + 12; index < malformed.length - 1; index += 64) malformed[index] = 63;
                malformed[malformed.length - 1] = 0;
                upstream.send(new DatagramPacket(malformed, malformed.length, packet.getSocketAddress()));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> failed.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));

                var next = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "after-malformed.test", DnsRecordType.A, 77L);
                packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                byte[] valid = response(Arrays.copyOf(packet.getData(), packet.getLength()), false);
                upstream.send(new DatagramPacket(valid, valid.length, packet.getSocketAddress()));
                assertEquals(java.util.List.of("203.0.113.7"), next.get(2, TimeUnit.SECONDS).addresses());
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void upstreamTcpCloseCompletesPendingQuery() throws Exception {
        try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             DatagramSocket udp = new DatagramSocket(tcp.getLocalPort(), InetAddress.getLoopbackAddress())) {
            Thread udpResponder = new Thread(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                    udp.receive(packet);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    byte[] response = response(request, true);
                    udp.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            Thread tcpCloser = new Thread(() -> {
                try (Socket socket = tcp.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(input.readUnsignedShort());
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            udpResponder.setDaemon(true);
            tcpCloser.setDaemon(true);
            udpResponder.start();
            tcpCloser.start();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                assertThrows(ExecutionException.class, () -> pool.query(loop, ProtocolType.DNS_SERVER,
                        "127.0.0.1", tcp.getLocalPort(), "closed-channel.test", DnsRecordType.A, 76L)
                        .get(8, TimeUnit.SECONDS));
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    @Test
    void rootCnameTargetReturnsEmptyAnswerAndReleasesPendingQuery() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                var query = pool.query(loop, ProtocolType.DNS_SERVER, "127.0.0.1",
                        upstream.getLocalPort(), "root-cname.test", DnsRecordType.A, 78L);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                byte[] reply = Arrays.copyOf(response(request, false), request.length + 13);
                System.arraycopy(new byte[]{(byte) 0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 3, 0, 1, 0},
                        0, reply, request.length, 13);
                upstream.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                DnsResult result = query.get(2, TimeUnit.SECONDS);
                assertEquals(DnsResponseCode.NOERROR, result.code());
                assertTrue(result.addresses().isEmpty());
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void cnameChainLimitsTtlAndIgnoresUnrelatedAddress() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                    upstream.receive(packet);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    int end = 12;
                    while (request[end] != 0) end += (request[end] & 0xff) + 1;
                    end += 5;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(request, 0, 2);
                    out.write(new byte[]{(byte) 0x81, (byte) 0x80, 0, 1, 0, 3, 0, 0, 0, 0});
                    out.write(request, 12, end - 12);
                    byte[] alias = dnsName("_target-cname-test.example");
                    out.write(new byte[]{(byte) 0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 3,
                            0, (byte) alias.length});
                    out.write(alias);
                    out.write(dnsName("unrelated-cname-test.example"));
                    out.write(new byte[]{0, 1, 0, 1, 0, 0, 0, 1, 0, 4, (byte) 203, 0, 113, 8});
                    out.write(alias);
                    out.write(new byte[]{0, 1, 0, 1, 0, 0, 0, 10, 0, 4, (byte) 203, 0, 113, 7});
                    byte[] response = out.toByteArray();
                    upstream.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            responder.setDaemon(true);
            responder.start();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsResult result = new DnsUpstreamPool().query(group.next(), ProtocolType.DNS_SERVER,
                        "127.0.0.1", upstream.getLocalPort(), "source-cname-test.example",
                        DnsRecordType.A, 75L).get(4, TimeUnit.SECONDS);
                assertEquals(java.util.List.of("203.0.113.7"), result.addresses());
                assertEquals(3, result.ttlSeconds());
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    private static byte[] dnsName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.writeBytes(bytes);
        }
        out.write(0);
        return out.toByteArray();
    }

    @Test
    void cancelledQueryRemovesPendingCorrelation() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                var loop = group.next();
                CompletableFuture<DnsResult> result = pool.query(loop, ProtocolType.DNS_SERVER,
                        "127.0.0.1", upstream.getLocalPort(), "cancel.test", DnsRecordType.A, 74L);
                upstream.setSoTimeout(3_000);
                upstream.receive(new DatagramPacket(new byte[512], 512));
                assertEquals(1, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertTrue(result.cancel(false));
                assertEquals(0, pool.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    @Test
    void dotUpstreamReturnsRealAnswer() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        SslContext tls = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
        NioEventLoopGroup group = new NioEventLoopGroup(2);
        Channel listener = null;
        try {
            listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(tls.newHandler(channel.alloc()));
                            channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder());
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<DnsQuery>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, DnsQuery query) {
                                    DnsQuestion question = query.recordAt(DnsSection.QUESTION);
                                    DnsResponse response = new DefaultDnsResponse(query.id());
                                    response.setRecursionDesired(true);
                                    response.setRecursionAvailable(true);
                                    response.addRecord(DnsSection.QUESTION,
                                            new DefaultDnsQuestion(question.name(), question.type()));
                                    response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(question.name(),
                                            DnsRecordType.A, 5, ctx.alloc().buffer(4).writeBytes(
                                                    new byte[]{(byte) 203, 0, 113, 9})));
                                    ctx.writeAndFlush(response);
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) listener.localAddress()).getPort();
            DnsUpstreamPool pool = new DnsUpstreamPool();
            for (String host : new String[]{"127.0.0.1", "localhost"}) {
                DnsResult result = pool.query(group.next(), ProtocolType.DOT, host, port,
                        "dot.test", DnsRecordType.A, 73L).get(8, TimeUnit.SECONDS);
                assertEquals("203.0.113.9", result.addresses().get(0));
            }
        } finally {
            if (listener != null) listener.close().sync();
            group.shutdownGracefully().sync();
            certificate.delete();
        }
    }

    @Test
    void truncatedUdpAnswerRetriesOverTcp() throws Exception {
        try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             DatagramSocket udp = new DatagramSocket(tcp.getLocalPort(), InetAddress.getLoopbackAddress())) {
            Thread udpResponder = new Thread(() -> {
                try {
                    byte[] buffer = new byte[512];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udp.receive(packet);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    byte[] response = response(request, true);
                    udp.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            Thread tcpResponder = new Thread(() -> {
                try (Socket socket = tcp.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    byte[] request = input.readNBytes(input.readUnsignedShort());
                    byte[] response = response(request, false);
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    output.writeShort(response.length);
                    output.write(response);
                    output.flush();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            udpResponder.setDaemon(true);
            tcpResponder.setDaemon(true);
            udpResponder.start();
            tcpResponder.start();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                DnsResult result = pool.query(group.next(), ProtocolType.DNS_SERVER, "127.0.0.1",
                        tcp.getLocalPort(), "truncated.test", DnsRecordType.A, 71L).get(8, TimeUnit.SECONDS);
                assertEquals("203.0.113.7", result.addresses().get(0));
                assertEquals(1L, pool.truncationsByRoute().get(71L));
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    @Test
    void silentUpstreamTimesOutAndCompletesFuture() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                DnsUpstreamPool pool = new DnsUpstreamPool();
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        pool.query(group.next(), ProtocolType.DNS_SERVER, "127.0.0.1",
                                upstream.getLocalPort(), "timeout.test", DnsRecordType.A, 72L)
                                .get(8, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof TimeoutException);
                assertEquals(1L, pool.timeoutsByRoute().get(72L));
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    private static byte[] response(byte[] request, boolean truncated) {
        int end = 12;
        while (request[end] != 0) end += (request[end] & 0xff) + 1;
        end += 5;
        byte[] answer = truncated ? new byte[0] : new byte[]{(byte) 0xc0, 12, 0, 1, 0, 1,
                0, 0, 0, 2, 0, 4, (byte) 203, 0, 113, 7};
        byte[] response = new byte[end + answer.length];
        System.arraycopy(request, 0, response, 0, end);
        response[2] = (byte) (truncated ? 0x83 : 0x81);
        response[3] = (byte) 0x80;
        response[6] = 0;
        response[7] = (byte) (truncated ? 0 : 1);
        System.arraycopy(answer, 0, response, end, answer.length);
        return response;
    }
}
