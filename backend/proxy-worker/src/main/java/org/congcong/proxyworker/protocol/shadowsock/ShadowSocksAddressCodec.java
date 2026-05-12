package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;

import java.nio.charset.StandardCharsets;

public final class ShadowSocksAddressCodec {

    private ShadowSocksAddressCodec() {
    }

    public static void writeAddress(ByteBuf out, String host, int port) {
        if (NetUtil.isValidIpV4Address(host)) {
            out.writeByte(Socks5AddressType.IPv4.byteValue());
            out.writeBytes(NetUtil.createByteArrayFromIpAddressString(host));
        } else if (NetUtil.isValidIpV6Address(host)) {
            out.writeByte(Socks5AddressType.IPv6.byteValue());
            out.writeBytes(NetUtil.createByteArrayFromIpAddressString(host));
        } else {
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            out.writeByte(Socks5AddressType.DOMAIN.byteValue());
            out.writeByte(hostBytes.length);
            out.writeBytes(hostBytes);
        }
        out.writeShort(port);
    }

    public static ParsedAddress readAddress(ByteBuf in) {
        Socks5AddressType addressType = Socks5AddressType.valueOf(in.readByte());
        String host;
        if (addressType == Socks5AddressType.IPv4) {
            byte[] address = new byte[4];
            in.readBytes(address);
            host = NetUtil.bytesToIpAddress(address);
        } else if (addressType == Socks5AddressType.IPv6) {
            byte[] address = new byte[16];
            in.readBytes(address);
            host = NetUtil.bytesToIpAddress(address);
        } else if (addressType == Socks5AddressType.DOMAIN) {
            int domainLength = in.readUnsignedByte();
            byte[] domain = new byte[domainLength];
            in.readBytes(domain);
            host = new String(domain, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
        int port = in.readUnsignedShort();
        return new ParsedAddress(host, port);
    }

    public record ParsedAddress(String host, int port) {
    }
}
