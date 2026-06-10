package org.congcong.proxyworker.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
