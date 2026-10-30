package org.congcong.proxyworker.udp;

/** Deployment-only settings; captured once per listener. */
public record UdpRelaySettings(int idleTimeoutSeconds, int maxSessions, int maxSessionsPerClient,
                               int pendingMaxPackets, int pendingMaxBytes, int rejectCacheSeconds,
                               int maxDatagramSize, int connectTimeoutSeconds, int failureCacheSeconds) {
    public UdpRelaySettings {
        if (idleTimeoutSeconds < 1 || maxSessions < 1 || maxSessionsPerClient < 1 || pendingMaxPackets < 1
                || pendingMaxBytes < 1 || rejectCacheSeconds < 1 || maxDatagramSize < 1 || maxDatagramSize > 65535
                || connectTimeoutSeconds < 1 || failureCacheSeconds < 1) {
            throw new IllegalArgumentException("Invalid UDP relay settings");
        }
    }

    public static UdpRelaySettings fromSystemProperties() {
        return new UdpRelaySettings(value("idleTimeoutSeconds", 60), value("maxSessions", 4096),
                value("maxSessionsPerClient", 1024), value("pendingMaxPackets", 16),
                value("pendingMaxBytes", 65536), value("rejectCacheSeconds", 30), value("maxDatagramSize", 65535),
                value("connectTimeoutSeconds", 10), value("failureCacheSeconds", 5));
    }

    private static int value(String name, int fallback) {
        return Integer.parseInt(System.getProperty("proxyworker.udp." + name, Integer.toString(fallback)));
    }
}
