package org.congcong.proxyworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.congcong.proxyworker.config.InboundConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AbstractProxyServerTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("proxyworker.netty.epoll.enabled");
    }

    @Test
    void disablesEpollWhenSystemPropertyIsFalse() {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");

        assertFalse(new TestProxyServer().epollEnabled());
    }

    private static final class TestProxyServer extends AbstractProxyServer {
        boolean epollEnabled() {
            return useEpoll();
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public String getIp() {
            return "127.0.0.1";
        }

        @Override
        public String getServerName() {
            return "test";
        }

        @Override
        public InboundConfig getInboundConfig() {
            return null;
        }

        @Override
        protected void doStart() {
        }

        @Override
        protected void doClose() {
        }
    }
}
