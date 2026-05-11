package org.congcong.proxyworker.outbound.reality.config;

import org.congcong.proxyworker.outbound.reality.vless.VlessFlow;

public final class RealityClientConfig {

    private final String host;
    private final int port;
    private final String serverName;
    private final String publicKey;
    private final String shortId;
    private final String uuid;
    private final int connectTimeoutMillis;
    private final VlessFlow flow;

    public RealityClientConfig(
            String host,
            int port,
            String serverName,
            String publicKey,
            String shortId,
            String uuid,
            int connectTimeoutMillis) {
        this(host, port, serverName, publicKey, shortId, uuid, connectTimeoutMillis, VlessFlow.NONE);
    }

    public RealityClientConfig(
            String host,
            int port,
            String serverName,
            String publicKey,
            String shortId,
            String uuid,
            int connectTimeoutMillis,
            VlessFlow flow) {
        this.host = host;
        this.port = port;
        this.serverName = serverName;
        this.publicKey = publicKey;
        this.shortId = shortId;
        this.uuid = uuid;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.flow = flow == null ? VlessFlow.NONE : flow;
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

    public String uuid() {
        return uuid;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public VlessFlow flow() {
        return flow;
    }
}
