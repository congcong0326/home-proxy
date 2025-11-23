package org.congcong.proxyworker.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractProxyServer {

    protected volatile EventLoopGroup bossGroup;
    protected volatile EventLoopGroup workerGroup;
    protected volatile Channel serverChannel;
    protected volatile ChannelFuture bindFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // === 原来就有的抽象方法，保持不变 ===
    public abstract int getPort();
    public abstract String getIp();
    public abstract String getServerName();

    // 如果 UDP 也要用，可以放这
    public abstract InboundConfig getInboundConfig();

    // === 模板方法：留给子类真正启动 / 关闭 ===
    protected abstract void doStart() throws InterruptedException;
    protected abstract void doClose() throws InterruptedException;

    protected boolean useEpoll() {
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
