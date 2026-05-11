package org.congcong.proxyworker.integration.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeDnsServer implements AutoCloseable {
    private final DatagramSocket socket;
    private final InetAddress answer;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger queryCount = new AtomicInteger();

    private FakeDnsServer(DatagramSocket socket, InetAddress answer, ExecutorService executor) {
        this.socket = socket;
        this.answer = answer;
        this.executor = executor;
    }

    public static FakeDnsServer start(String answerIp) {
        try {
            DatagramSocket socket = new DatagramSocket(PortAllocator.udpPort(), InetAddress.getByName("127.0.0.1"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            FakeDnsServer server = new FakeDnsServer(socket, InetAddress.getByName(answerIp), executor);
            executor.execute(server::serve);
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start fake DNS server", e);
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int queryCount() {
        return queryCount.get();
    }

    private void serve() {
        byte[] buffer = new byte[512];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                queryCount.incrementAndGet();
                byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
                byte[] response = responseFor(query);
                DatagramPacket reply = new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort());
                socket.send(reply);
            } catch (IOException e) {
                if (running.get()) {
                    throw new IllegalStateException("Fake DNS server failed", e);
                }
            }
        }
    }

    private byte[] responseFor(byte[] query) throws IOException {
        int questionEnd = questionEnd(query);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(query[0]);
        out.write(query[1]);
        out.write(0x81);
        out.write(0x80);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(query, 12, questionEnd - 12);
        out.write(0xc0);
        out.write(0x0c);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x3c);
        out.write(0x00);
        out.write(0x04);
        out.write(answer.getAddress());
        return out.toByteArray();
    }

    private int questionEnd(byte[] query) {
        int i = 12;
        while (i < query.length && query[i] != 0) {
            i += (query[i] & 0xff) + 1;
        }
        return i + 5;
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        executor.shutdownNow();
    }
}
