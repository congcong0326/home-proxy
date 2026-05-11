package org.congcong.proxyworker.integration.support;

import java.util.List;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.ProxyContext;

public final class WorkerServerHarness implements AutoCloseable {
    private WorkerServerHarness() {
    }

    public static WorkerServerHarness start(InboundConfig config) {
        return start(List.of(config));
    }

    public static WorkerServerHarness start(List<InboundConfig> configs) {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        ProxyContext.getInstance().refresh(configs);
        return new WorkerServerHarness();
    }

    @Override
    public void close() {
        ProxyContext.getInstance().closeAll();
        System.clearProperty("proxyworker.netty.epoll.enabled");
    }
}
