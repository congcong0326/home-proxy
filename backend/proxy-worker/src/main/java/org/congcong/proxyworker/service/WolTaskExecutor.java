package org.congcong.proxyworker.service;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
public class WolTaskExecutor implements WorkerTaskExecutor {

    static final String TASK_TYPE = "WOL_WAKE";

    private final PacketSender packetSender;

    public WolTaskExecutor() {
        this(WolTaskExecutor::sendUdpWolPacket);
    }

    WolTaskExecutor(PacketSender packetSender) {
        this.packetSender = packetSender;
    }

    @Override
    public WorkerTaskResultDTO execute(WorkerTaskDTO task) {
        WorkerTaskResultDTO result = new WorkerTaskResultDTO();
        result.setTaskId(task == null ? null : task.getTaskId());
        result.setFinishedAt(LocalDateTime.now());

        if (task == null || !TASK_TYPE.equals(task.getType())) {
            result.setSuccess(false);
            result.setMessage("Unsupported task type");
            return result;
        }

        try {
            Map<String, Object> payload = task.getPayload();
            String deviceName = stringValue(payload, "deviceName", "未知设备");
            String macAddress = stringValue(payload, "macAddress", null);
            String broadcastIp = stringValue(payload, "broadcastIp", null);
            int port = intValue(payload, "port", 9);

            byte[] wolPacket = buildWolPacket(macAddress);
            packetSender.send(wolPacket, broadcastIp, port);
            result.setSuccess(true);
            result.setMessage("WOL packet sent to " + deviceName);
            log.info("WOL packet sent to {} (MAC: {}, broadcastIp: {}, port: {})",
                    deviceName, macAddress, broadcastIp, port);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
            log.warn("Failed to execute WOL task {}", result.getTaskId(), e);
        }
        return result;
    }

    static byte[] buildWolPacket(String macAddress) {
        byte[] macBytes = parseMacAddress(macAddress);
        byte[] packet = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 0; i < 16; i++) {
            System.arraycopy(macBytes, 0, packet, 6 + i * macBytes.length, macBytes.length);
        }
        return packet;
    }

    private static byte[] parseMacAddress(String macAddress) {
        if (macAddress == null) {
            throw new IllegalArgumentException("MAC address is required");
        }
        String[] hex = macAddress.split("[:\\-]");
        if (hex.length != 6) {
            throw new IllegalArgumentException("Invalid MAC address: " + macAddress);
        }

        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            try {
                bytes[i] = (byte) Integer.parseInt(hex[i], 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid MAC address: " + macAddress, e);
            }
        }
        return bytes;
    }

    private static String stringValue(Map<String, Object> payload, String key, String defaultValue) {
        if (payload == null || payload.get(key) == null) {
            if (defaultValue == null) {
                throw new IllegalArgumentException(key + " is required");
            }
            return defaultValue;
        }
        return String.valueOf(payload.get(key));
    }

    private static int intValue(Map<String, Object> payload, String key, int defaultValue) {
        if (payload == null || payload.get(key) == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static void sendUdpWolPacket(byte[] payload, String broadcastIp, int port) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            // WOL does not expect a response.
                        }
                    });

            channel = bootstrap.bind(0).sync().channel();
            InetAddress address = InetAddress.getByName(broadcastIp);
            channel.writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(payload),
                    new InetSocketAddress(address, port)
            )).sync();
        } finally {
            if (channel != null) {
                channel.close().sync();
            }
            group.shutdownGracefully();
        }
    }

    @FunctionalInterface
    interface PacketSender {
        void send(byte[] payload, String broadcastIp, int port) throws Exception;
    }
}
