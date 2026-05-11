package org.congcong.proxyworker.outbound.reality.vless;

import java.util.UUID;

public final class VlessRequest {

    private final UUID uuid;
    private final String host;
    private final int port;
    private final VlessFlow flow;

    public VlessRequest(UUID uuid, String host, int port) {
        this(uuid, host, port, VlessFlow.NONE);
    }

    public VlessRequest(UUID uuid, String host, int port, VlessFlow flow) {
        this.uuid = uuid;
        this.host = host;
        this.port = port;
        this.flow = flow == null ? VlessFlow.NONE : flow;
    }

    public UUID uuid() {
        return uuid;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public VlessFlow flow() {
        return flow;
    }
}
