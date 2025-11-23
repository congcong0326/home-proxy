package org.congcong.proxyworker.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
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

@Slf4j
public abstract class TcpProxyServer extends AbstractProxyServer {
    // 保持原来的抽象方法签名不变
    public abstract ChannelInitializer<Channel> getChildHandler();

    @Override
    public abstract InboundConfig getInboundConfig();

    @Override
    protected void doStart() throws InterruptedException {
        if (useEpoll()) {
            bossGroup = new EpollEventLoopGroup(1);
            workerGroup = new EpollEventLoopGroup();
        } else {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(getChildHandler());

        if (useEpoll()) {
            bootstrap.channel(EpollServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(EpollChannelOption.IP_TRANSPARENT, true);
        } else {
            bootstrap.channel(NioServerSocketChannel.class);
        }
        bindFuture = bootstrap.bind(getIp(), getPort()).sync();
        serverChannel = bindFuture.channel();
    }

    @Override
    protected void doClose() throws InterruptedException {
        // 照搬原来 close() 里关闭 serverChannel 的部分
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().sync();
        }
    }
}
