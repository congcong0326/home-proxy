package org.congcong.proxyworker.protocol.transparent;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.AddressedEmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProtocolDetectHandlerTest {
    private ProxyContext proxyContext;
    private AddressedEmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        proxyContext = ProxyWorkerTestFixtures.proxyContext("192.168.1.20", "93.184.216.34", 443);
        channel = new AddressedEmbeddedChannel(
                ProxyWorkerTestFixtures.socket("93.184.216.34", 443),
                ProxyWorkerTestFixtures.socket("192.168.1.20", 53000),
                new ProtocolDetectHandler());
        ChannelAttributes.setProxyContext(channel, proxyContext);
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void parsesHttpHostAndForwardsOriginalPacket() {
        ByteBuf request = Unpooled.copiedBuffer(
                "GET / HTTP/1.1\r\nHost: example.com:8443\r\nUser-Agent: test\r\n\r\nbody",
                US_ASCII);

        channel.writeInbound(request);

        ByteBuf forwarded = channel.readInbound();
        try {
            assertEquals("example.com", proxyContext.getOriginalTargetHost());
            assertEquals("GET / HTTP/1.1\r\nHost: example.com:8443\r\nUser-Agent: test\r\n\r\nbody",
                    forwarded.toString(US_ASCII));
        } finally {
            forwarded.release();
        }
    }

    @Test
    void waitsForCompleteHttpHeaderBeforeForwarding() {
        channel.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\nHost: example.com", US_ASCII));

        assertNull(channel.readInbound());
        assertNull(proxyContext.getOriginalTargetHost());

        channel.writeInbound(Unpooled.copiedBuffer("\r\n\r\npayload", US_ASCII));

        ByteBuf forwarded = channel.readInbound();
        try {
            assertEquals("example.com", proxyContext.getOriginalTargetHost());
            assertEquals("GET / HTTP/1.1\r\nHost: example.com\r\n\r\npayload", forwarded.toString(US_ASCII));
        } finally {
            forwarded.release();
        }
    }

    @Test
    void parsesTlsSniAndForwardsClientHello() {
        byte[] clientHello = tlsClientHello("tls.example.com");

        channel.writeInbound(Unpooled.wrappedBuffer(clientHello));

        ByteBuf forwarded = channel.readInbound();
        try {
            byte[] forwardedBytes = new byte[forwarded.readableBytes()];
            forwarded.getBytes(forwarded.readerIndex(), forwardedBytes);
            assertEquals("tls.example.com", proxyContext.getOriginalTargetHost());
            assertEquals(Arrays.toString(clientHello), Arrays.toString(forwardedBytes));
        } finally {
            forwarded.release();
        }
    }

    @Test
    void fallsBackToOriginalTargetIpForUnknownPayload() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[] {0x01, 0x02, 0x03});

        channel.writeInbound(payload);

        ByteBuf forwarded = channel.readInbound();
        try {
            assertEquals("93.184.216.34", proxyContext.getOriginalTargetHost());
            assertEquals(3, forwarded.readableBytes());
        } finally {
            forwarded.release();
        }
    }

    private byte[] tlsClientHello(String host) {
        byte[] hostBytes = host.getBytes(US_ASCII);
        int serverNameListLength = 1 + 2 + hostBytes.length;
        int serverNameExtensionDataLength = 2 + serverNameListLength;
        int extensionLength = 2 + 2 + serverNameExtensionDataLength;
        int extensionsLength = extensionLength;
        int bodyLength = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extensionsLength;
        int handshakeLength = 1 + 3 + bodyLength;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(5 + handshakeLength);

        buf.writeByte(0x16);
        buf.writeByte(0x03);
        buf.writeByte(0x03);
        buf.writeShort(handshakeLength);
        buf.writeByte(0x01);
        buf.writeMedium(bodyLength);
        buf.writeShort(0x0303);
        buf.writeZero(32);
        buf.writeByte(0);
        buf.writeShort(2);
        buf.writeShort(0x1301);
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeShort(extensionsLength);
        buf.writeShort(0x0000);
        buf.writeShort(serverNameExtensionDataLength);
        buf.writeShort(serverNameListLength);
        buf.writeByte(0x00);
        buf.writeShort(hostBytes.length);
        buf.writeBytes(hostBytes);

        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }
}
