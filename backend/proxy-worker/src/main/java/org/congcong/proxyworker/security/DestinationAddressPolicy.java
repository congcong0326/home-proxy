package org.congcong.proxyworker.security;

import io.netty.util.NetUtil;
import org.congcong.common.enums.ProtocolType;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Policy for destinations supplied by proxy clients.
 *
 * <p>The literal parser deliberately uses Netty's syntax parser plus
 * {@link InetAddress#getByAddress(byte[])}. Neither operation performs a DNS
 * lookup, which is important for the early literal-address check.</p>
 */
public final class DestinationAddressPolicy {

    private DestinationAddressPolicy() {
    }

    /**
     * Parses an IPv4 or IPv6 literal without consulting a name service.
     * Bracketed IPv6 values are accepted because HTTP CONNECT commonly uses
     * that authority form.
     */
    public static Optional<InetAddress> parseLiteral(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String candidate = host;
        if (candidate.length() >= 2 && candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(candidate);
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByAddress(bytes));
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("Netty returned an invalid IP literal", impossible);
        }
    }

    public static boolean isProtectedInbound(ProtocolType protocolType) {
        return protocolType == ProtocolType.SOCKS5
                || protocolType == ProtocolType.HTTPS_CONNECT
                || protocolType == ProtocolType.SHADOW_SOCKS
                || protocolType == ProtocolType.TLS_SNI_TCP;
    }

    /**
     * Returns whether an address is an Internet-routable unicast address.
     */
    public static boolean isPubliclyRoutable(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (!(address instanceof Inet6Address) || bytes.length != 16) {
            return false;
        }

        // IPv4-mapped and IPv4-compatible literals must inherit IPv4 policy.
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
        }
        // IPv4-compatible IPv6 (::/96 except ::ffff/96) is deprecated and
        // has no public unicast use that this proxy needs to support.
        if (isIpv4Compatible(bytes)) {
            return false;
        }

        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        if (isAllZero(bytes) || isIpv6Loopback(bytes)) {
            return false;
        }
        // Unique local fc00::/7.
        if ((first & 0xfe) == 0xfc) {
            return false;
        }
        // Link-local fe80::/10 and deprecated site-local fec0::/10.
        if (first == 0xfe && (second & 0xc0) == 0x80) {
            return false;
        }
        if (first == 0xfe && (second & 0xc0) == 0xc0) {
            return false;
        }
        // Documentation range 2001:db8::/32.
        if (first == 0x20 && second == 0x01 && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8) {
            return false;
        }
        // Other IANA special-purpose prefixes that are not globally
        // routable: IPv4/IPv6 translation local use, discard-only, IPv6
        // benchmarking, and deprecated ORCHID.
        if (first == 0x00 && second == 0x64 && unsigned(bytes[2]) == 0xff
                && unsigned(bytes[3]) == 0x9b && unsigned(bytes[4]) == 0x00 && unsigned(bytes[5]) == 0x01) {
            return false;
        }
        if (first == 0x01 && second == 0x00 && isZeroPrefix(bytes, 2, 8)) {
            return false;
        }
        if (first == 0x20 && second == 0x01 && unsigned(bytes[2]) == 0x00
                && unsigned(bytes[3]) == 0x02 && unsigned(bytes[4]) == 0x00 && unsigned(bytes[5]) == 0x00) {
            return false;
        }
        if (first == 0x20 && second == 0x01 && unsigned(bytes[2]) == 0x00
                && (unsigned(bytes[3]) & 0xf0) == 0x10) {
            return false;
        }
        // IPv6 multicast ff00::/8.
        if (first == 0xff) {
            return false;
        }
        return true;
    }

    private static boolean isPublicIpv4(byte[] address) {
        if (address.length != 4) {
            return false;
        }
        int a = unsigned(address[0]);
        int b = unsigned(address[1]);
        int c = unsigned(address[2]);

        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return false;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        // IANA special-use, documentation, and benchmarking networks.
        if (a == 192 && b == 0 && c == 0) {
            return false;
        }
        if (a == 192 && b == 0 && c == 2) {
            return false;
        }
        if (a == 192 && b == 88 && c == 99) {
            return false;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false;
        }
        if (a == 198 && b == 51 && c == 100) {
            return false;
        }
        return !(a == 203 && b == 0 && c == 113);
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    private static boolean isIpv4Compatible(byte[] address) {
        for (int i = 0; i < 12; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(byte[] address) {
        for (byte value : address) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZeroPrefix(byte[] address, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Loopback(byte[] address) {
        for (int i = 0; i < 15; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[15] == 1;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
