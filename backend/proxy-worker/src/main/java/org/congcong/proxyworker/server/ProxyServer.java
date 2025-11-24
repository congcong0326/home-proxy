package org.congcong.proxyworker.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class ProxyServer {
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private volatile ChannelFuture bindFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public abstract int getPort();

    public abstract String getIp();

    public abstract String getServerName();

    public abstract ChannelInitializer<SocketChannel> getChildHandler();

    public abstract InboundConfig getInboundConfig();

    protected boolean useEpoll() {
        boolean available = Epoll.isAvailable();
        log.info("enable native epoll {}", available);
        return Epoll.isAvailable();
    }

    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            log.info("{} 代理服务器已经运行在 {}:{}", getServerName(), getIp(), getPort());
            return;
        }
        if (useEpoll()) {
            bossGroup =  new EpollEventLoopGroup(1);
            workerGroup = new EpollEventLoopGroup();
        } else {
            bossGroup = new NioEventLoopGroup(1);
            // 默认cpu核心数量*2
            workerGroup = new NioEventLoopGroup();
        }
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(getChildHandler());
        if (useEpoll()) {
            bootstrap.channel(EpollServerSocketChannel.class)
                    // 开启透明代理能力
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    .childOption(EpollChannelOption.IP_TRANSPARENT, true);
        } else {
            bootstrap.channel(NioServerSocketChannel.class);
        }
        bindFuture = bootstrap.bind(getIp(), getPort()).sync();
        serverChannel = bindFuture.channel();
        log.info("{} 代理服务器启动在 {}:{}", getServerName(), getIp(), getPort());
    }

    public void close() throws InterruptedException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close().sync();
            }
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
