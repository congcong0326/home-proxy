package org.congcong.proxyworker.outbound.reality.tls;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class TlsExtensionWriter {

    public byte[] supportedVersions() {
        ByteBuffer buffer = ByteBuffer.allocate(7);
        buffer.putShort((short) 0x002b);
        buffer.putShort((short) 0x0003);
        buffer.put((byte) 0x02);
        buffer.putShort((short) 0x0304);
        return buffer.array();
    }

    public byte[] serverName(String serverName) {
        byte[] host = serverName.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buffer = ByteBuffer.allocate(9 + host.length);
        buffer.putShort((short) 0x0000);
        buffer.putShort((short) (5 + host.length));
        buffer.putShort((short) (3 + host.length));
        buffer.put((byte) 0x00);
        buffer.putShort((short) host.length);
        buffer.put(host);
        return buffer.array();
    }

    public byte[] supportedGroups() {
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putShort((short) 0x000a);
        buffer.putShort((short) 0x0008);
        buffer.putShort((short) 0x0006);
        buffer.putShort((short) 0x001d);
        buffer.putShort((short) 0x0017);
        buffer.putShort((short) 0x0018);
        return buffer.array();
    }

    public byte[] signatureAlgorithms() {
        ByteBuffer buffer = ByteBuffer.allocate(22);
        buffer.putShort((short) 0x000d);
        buffer.putShort((short) 0x0012);
        buffer.putShort((short) 0x0010);
        buffer.putShort((short) 0x0403);
        buffer.putShort((short) 0x0804);
        buffer.putShort((short) 0x0401);
        buffer.putShort((short) 0x0503);
        buffer.putShort((short) 0x0805);
        buffer.putShort((short) 0x0501);
        buffer.putShort((short) 0x0806);
        buffer.putShort((short) 0x0601);
        return buffer.array();
    }

    public byte[] applicationLayerProtocolNegotiation() {
        byte[] h2 = "h2".getBytes(StandardCharsets.US_ASCII);
        byte[] http11 = "http/1.1".getBytes(StandardCharsets.US_ASCII);
        int protocolsLength = 1 + h2.length + 1 + http11.length;
        ByteBuffer buffer = ByteBuffer.allocate(6 + protocolsLength);
        buffer.putShort((short) 0x0010);
        buffer.putShort((short) (2 + protocolsLength));
        buffer.putShort((short) protocolsLength);
        buffer.put((byte) h2.length);
        buffer.put(h2);
        buffer.put((byte) http11.length);
        buffer.put(http11);
        return buffer.array();
    }

    public byte[] pskKeyExchangeModes() {
        ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.putShort((short) 0x002d);
        buffer.putShort((short) 0x0002);
        buffer.put((byte) 0x01);
        buffer.put((byte) 0x01);
        return buffer.array();
    }

    public byte[] keyShareX25519(byte[] publicKey) {
        ByteBuffer buffer = ByteBuffer.allocate(42);
        buffer.putShort((short) 0x0033);
        buffer.putShort((short) 0x0026);
        buffer.putShort((short) 0x0024);
        buffer.putShort((short) 0x001d);
        buffer.putShort((short) 0x0020);
        buffer.put(publicKey);
        return buffer.array();
    }

    public byte[] extensionBlock(byte[]... extensions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] extension : extensions) {
            out.write(extension, 0, extension.length);
        }
        byte[] all = out.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(2 + all.length);
        buffer.putShort((short) all.length);
        buffer.put(all);
        return buffer.array();
    }
}
