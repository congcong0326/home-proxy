package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.WolConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

@Slf4j
@Service
@RequiredArgsConstructor
public class WolService {

    private final WolConfigService wolConfigService;

    /**
     * 根据设备ID发送WOL魔术包
     */
    public String sendWolPacketById(Long configId) {
        WolConfig config = wolConfigService.getConfigById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + configId));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config.getMacAddress(), config.getSubnetMask(), config.getWolPort(), config.getName());
    }

    /**
     * 根据IP地址发送WOL魔术包
     */
    public String sendWolPacketByIp(String ipAddress) {
        WolConfig config = wolConfigService.getConfigByIpAddress(ipAddress)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到IP地址对应的WOL配置: " + ipAddress));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config.getMacAddress(), config.getSubnetMask(), config.getWolPort(), config.getName());
    }

    /**
     * 根据设备名称发送WOL魔术包
     */
    public String sendWolPacketByName(String deviceName) {
        WolConfig config = wolConfigService.getConfigByName(deviceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到设备名称对应的WOL配置: " + deviceName));
        
        if (!config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备已禁用: " + config.getName());
        }
        
        return sendWolPacket(config.getMacAddress(), config.getSubnetMask(), config.getWolPort(), config.getName());
    }

    /**
     * 发送WOL魔术包（原有方法，保持兼容性）
     */
    public String sendWolPacket(String macAddress, String broadcastIp, int port) {
        return sendWolPacket(macAddress, broadcastIp, port, "未知设备");
    }

    /**
     * 发送WOL魔术包的核心实现
     */
    private String sendWolPacket(String macAddress, String broadcastIp, int port, String deviceName) {
        String result;
        EventLoopGroup group = new NioEventLoopGroup();
        Channel channel = null;
        
        try {
            log.info("准备发送WOL魔术包到设备: {} (MAC: {}, 广播地址: {}, 端口: {})", 
                    deviceName, macAddress, broadcastIp, port);
            
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true) // 启用广播
                    .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            // 不需要处理响应
                        }
                    });

            channel = bootstrap.bind(0).sync().channel(); // Bind and wait for bind to complete

            ByteBuf wolBuffer = buildWolPacket(macAddress); // Assumes buildWolPacket is defined

            // 发送到广播地址
            InetAddress address = InetAddress.getByName(broadcastIp);

            // Write and flush, then wait for the write to complete
            channel.writeAndFlush(new DatagramPacket(wolBuffer, new InetSocketAddress(address, port))).sync();

            // If sync() didn't throw an exception, the packet was sent successfully
            result = String.format("WOL魔术包发送成功到设备: %s", deviceName);
            log.info("WOL魔术包发送成功到设备: {} (MAC: {})", deviceName, macAddress);

        } catch (UnknownHostException | InterruptedException e) {
            result = String.format("发送WOL魔术包到设备 %s 失败: %s", deviceName, e.getMessage());
            log.error("发送WOL魔术包到设备 {} 失败", deviceName, e);
            throw new RuntimeException(result, e);
        } catch (Exception e) {
            result = String.format("发送WOL魔术包到设备 %s 失败: %s", deviceName, e.getMessage());
            log.error("发送WOL魔术包到设备 {} 失败", deviceName, e);
            throw new RuntimeException(result, e);
        } finally {
            // Close the channel if it was successfully opened
            if (channel != null) {
                try {
                    channel.close().sync(); // Close the channel and wait
                } catch (InterruptedException e) {
                    log.warn("关闭WOL通道时发生异常", e);
                    Thread.currentThread().interrupt(); // Restore interrupted status
                }
            }
            // Shutdown the group
            group.shutdownGracefully();
        }
        return result;
    }

    private ByteBuf buildWolPacket(String macAddress) {
        // 将MAC地址转换为字节数组
        byte[] macBytes = parseMacAddress(macAddress);

        // 构建魔术包: 6x0xFF + 16*MAC
        ByteBuf buffer = Unpooled.buffer(6 + 16 * macBytes.length);
        for (int i = 0; i < 6; i++) {
            buffer.writeByte(0xFF);
        }
        for (int i = 0; i < 16; i++) {
            buffer.writeBytes(macBytes);
        }
        return buffer;
    }

    private byte[] parseMacAddress(String macAddress) {
        // 处理MAC地址格式（支持XX:XX:XX:XX:XX:XX或XX-XX-XX-XX-XX-XX）
        String[] hex = macAddress.split("[:\\-]");
        if (hex.length != 6) {
            throw new IllegalArgumentException("无效的MAC地址格式: " + macAddress);
        }

        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            try {
                bytes[i] = (byte) Integer.parseInt(hex[i], 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无效的MAC地址格式: " + macAddress, e);
            }
        }
        return bytes;
    }
}
