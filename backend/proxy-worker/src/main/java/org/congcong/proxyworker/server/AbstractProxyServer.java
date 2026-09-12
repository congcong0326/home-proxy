package org.congcong.proxyworker.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public abstract class AbstractProxyServer {

    protected volatile EventLoopGroup bossGroup;
    protected volatile EventLoopGroup workerGroup;
    protected volatile Channel serverChannel;
    protected volatile ChannelFuture bindFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<InboundConfig> inboundConfig = new AtomicReference<>();

    protected AbstractProxyServer() {
    }

    protected AbstractProxyServer(InboundConfig inboundConfig) {
        this.inboundConfig.set(inboundConfig);
    }

    public abstract int getPort();
    public abstract String getIp();
    public abstract String getServerName();

    public final InboundConfig getInboundConfig() {
        return inboundConfig.get();
    }

    /**
     * Replaces the snapshot used when accepting subsequent requests. Existing
     * channels keep the snapshot that was attached during their initialization.
     */
    public final void updateRuntimeConfig(InboundConfig newConfig) {
        if (newConfig == null) {
            throw new IllegalArgumentException("Inbound config is required");
        }
        inboundConfig.set(newConfig);
    }

    protected abstract void doStart() throws InterruptedException;
    protected abstract void doClose() throws InterruptedException;

    protected boolean useEpoll() {
        if (!Boolean.parseBoolean(System.getProperty("proxyworker.netty.epoll.enabled", "true"))) {
            log.info("native epoll disabled by system property proxyworker.netty.epoll.enabled=false");
            return false;
        }
        boolean available = Epoll.isAvailable();
        log.info("enable native epoll {}", available);
        return available;
    }

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            log.info("{} 代理服务器已经运行在 {}:{}", getServerName(), getIp(), getPort());
            return;
        }
        doStart();
        log.info("{} 代理服务器启动在 {}:{}", getServerName(), getIp(), getPort());
    }

    public void close() throws InterruptedException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            doClose();
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
            log.info("{} 代理服务器停止{}:{}", getServerName(), getIp(), getPort());
        }
    }

    public boolean isRunning() {
        return running.get();
    }

}
