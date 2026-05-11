package org.congcong.proxyworker.outbound.reality.config;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.reality.vless.VlessFlow;

public final class VlessRealityOutboundConfig {
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;
    private static final Pattern SHORT_ID_HEX = Pattern.compile("^[0-9a-fA-F]{0,16}$");

    private final String host;
    private final int port;
    private final String serverName;
    private final String publicKey;
    private final String shortId;
    private final UUID uuid;
    private final VlessFlow flow;
    private final int connectTimeoutMillis;

    private VlessRealityOutboundConfig(
            String host,
            int port,
            String serverName,
            String publicKey,
            String shortId,
            UUID uuid,
            VlessFlow flow,
            int connectTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.serverName = serverName;
        this.publicKey = publicKey;
        this.shortId = shortId;
        this.uuid = uuid;
        this.flow = flow;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public static VlessRealityOutboundConfig from(RouteConfig route) {
        if (route == null) {
            throw new IllegalArgumentException("route is required");
        }
        String host = requireText(route.getOutboundProxyHost(), "VLESS REALITY outboundProxyHost is required");
        Integer port = route.getOutboundProxyPort();
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("VLESS REALITY outboundProxyPort must be between 1 and 65535");
        }
        Map<String, Object> config = route.getOutboundProxyConfig();
        if (config == null) {
            throw new IllegalArgumentException("VLESS REALITY outboundProxyConfig is required");
        }

        String serverName = requireText(config.get("serverName"), "VLESS REALITY serverName is required");
        String publicKey = requireText(config.get("publicKey"), "VLESS REALITY publicKey is required");
        String shortId = requireText(config.get("shortId"), "VLESS REALITY shortId is required").toLowerCase();
        if (!SHORT_ID_HEX.matcher(shortId).matches() || (shortId.length() % 2) != 0) {
            throw new IllegalArgumentException("Invalid REALITY shortId hex: " + shortId);
        }
        UUID uuid = parseUuid(requireText(config.get("uuid"), "VLESS REALITY uuid is required"));
        VlessFlow flow = VlessFlow.fromWireName(textOrDefault(config.get("flow"), VlessFlow.XTLS_RPRX_VISION.wireName()));
        int connectTimeoutMillis = intOrDefault(config.get("connectTimeoutMillis"), DEFAULT_CONNECT_TIMEOUT_MILLIS);
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("VLESS REALITY connectTimeoutMillis must be positive");
        }

        return new VlessRealityOutboundConfig(
                host,
                port,
                serverName,
                publicKey,
                shortId,
                uuid,
                flow,
                connectTimeoutMillis);
    }

    public RealityClientConfig toRealityClientConfig() {
        return new RealityClientConfig(
                host,
                port,
                serverName,
                publicKey,
                shortId,
                uuid.toString(),
                connectTimeoutMillis,
                flow);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String serverName() {
        return serverName;
    }

    public String publicKey() {
        return publicKey;
    }

    public String shortId() {
        return shortId;
    }

    public UUID uuid() {
        return uuid;
    }

    public VlessFlow flow() {
        return flow;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid VLESS uuid: " + value, e);
        }
    }

    private static String requireText(Object value, String message) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static String textOrDefault(Object value, String defaultValue) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isEmpty() ? defaultValue : text;
    }

    private static int intOrDefault(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
