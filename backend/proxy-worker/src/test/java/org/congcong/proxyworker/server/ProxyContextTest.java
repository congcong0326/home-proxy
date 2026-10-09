package org.congcong.proxyworker.server;

import io.netty.channel.DefaultEventLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyContextTest {

    private final ProxyContext proxyContext = ProxyContext.getInstance();

    @AfterEach
    void tearDown() {
        proxyContext.resetActiveConnectionCountForTest();
    }

    @Test
    void tracksActiveTcpConnections() {
        proxyContext.resetActiveConnectionCountForTest();

        proxyContext.incrementActiveConnectionCount();
        proxyContext.incrementActiveConnectionCount();

        assertEquals(2, proxyContext.getActiveConnectionCount());

        proxyContext.decrementActiveConnectionCount();

        assertEquals(1, proxyContext.getActiveConnectionCount());
    }

    @Test
    void activeConnectionCountDoesNotGoBelowZero() {
        proxyContext.resetActiveConnectionCountForTest();

        proxyContext.decrementActiveConnectionCount();

        assertEquals(0, proxyContext.getActiveConnectionCount());
    }

    @Test
    void eventLoopCanReadDnsSnapshotWhileLifecycleMonitorIsHeld() throws Exception {
        DefaultEventLoop eventLoop = new DefaultEventLoop();
        try {
            synchronized (proxyContext) {
                var expected = proxyContext.getEnabledDnsInbound();
                // refresh()/closeAll() hold this monitor while waiting for event
                // loops. This read must finish before the monitor is released.
                assertEquals(expected, eventLoop.submit(proxyContext::getEnabledDnsInbound)
                        .get(2, TimeUnit.SECONDS));
            }
        } finally {
            eventLoop.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }
}
