package org.congcong.common.telemetry;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;

/** Literal-only classification. Reserved addresses must never become NAS traffic. */
public final class TrafficClassifierV1 {
    public static final int VERSION = 1;
    private TrafficClassifierV1() { }

    public static String addressClass(String literal) {
        if (literal == null || !InetAddresses.isInetAddress(literal)) return "UNKNOWN";
        InetAddress address = InetAddresses.forString(literal);
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) return "RFC1918";
            if (a == 198 && (b == 18 || b == 19)) return "BENCHMARK";
            if (a == 100 && b >= 64 && b <= 127) return "CGNAT";
            if (a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254)
                    || (a == 192 && b == 0) || (a == 198 && b == 51 && (bytes[2] & 255) == 100)
                    || (a == 203 && b == 0 && (bytes[2] & 255) == 113)) return "SPECIAL";
            return "PUBLIC_UNICAST";
        }
        if ((a & 254) == 252) return "IPV6_ULA";
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()) return "SPECIAL";
        // Only the allocated global-unicast range is treated as public; documentation excluded.
        if ((a & 224) != 32 || (a == 32 && b == 1 && (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184)) return "SPECIAL";
        return "PUBLIC_UNICAST";
    }

    public static NetworkScope scope(String literal) {
        return switch (addressClass(literal)) {
            case "RFC1918", "IPV6_ULA" -> NetworkScope.INTERNAL;
            case "PUBLIC_UNICAST" -> NetworkScope.EXTERNAL;
            default -> NetworkScope.UNKNOWN;
        };
    }

    public static void classify(SessionLogV1 record) {
        record.setTrafficClassificationVersion(VERSION);
        record.setTargetAddressClass(addressClass(record.getSelectedTargetIp()));
        record.setTargetNetworkScope(scope(record.getSelectedTargetIp()));
        record.setClientNetworkScope(scope(record.getClientIp()));
        record.setOutboundPeerNetworkScope(scope(record.getOutboundPeerIp()));
        if ("OUTBOUND_PROXY".equals(record.getRouteAction()) && !Boolean.TRUE.equals(record.getDestinationOverrideApplied())) {
            record.setTrafficCategory(TrafficCategory.EXTERNAL);
            record.setTrafficCategoryBasis("OUTBOUND_PROXY_ROUTE");
        } else if (record.getTargetNetworkScope() == NetworkScope.INTERNAL) {
            boolean override = Boolean.TRUE.equals(record.getDestinationOverrideApplied());
            record.setTrafficCategory(override ? TrafficCategory.INTERNAL_REWRITE : TrafficCategory.OTHER_INTERNAL);
            record.setTrafficCategoryBasis(override ? "OVERRIDE_PRIVATE_TARGET" : "NON_OVERRIDE_PRIVATE_TARGET");
        } else if (record.getTargetNetworkScope() == NetworkScope.EXTERNAL) {
            record.setTrafficCategory(TrafficCategory.EXTERNAL);
            record.setTrafficCategoryBasis("PUBLIC_TARGET");
        } else {
            record.setTrafficCategory(TrafficCategory.UNKNOWN);
            record.setTrafficCategoryBasis("UNRESOLVED");
        }
    }
}
