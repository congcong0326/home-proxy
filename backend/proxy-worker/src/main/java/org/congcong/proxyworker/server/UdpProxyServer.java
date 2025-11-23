package org.congcong.proxyworker.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;

public abstract class UdpProxyServer extends AbstractProxyServer {

    /**
     * UDP 的 pipeline 初始化
     * 使用 DatagramChannel，兼容 NIO / Epoll 两种实现。
     */
    public abstract ChannelInitializer<Channel> getChannelInitializer();

    @Override
    protected void doStart() throws InterruptedException {
        // UDP 通常只需要一个 workerGroup
        if (useEpoll()) {
            workerGroup = new EpollEventLoopGroup();
        } else {
            workerGroup = new NioEventLoopGroup();
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                 // 注意：UDP 没有 childHandler，只有一个 handler
                 .handler(getChannelInitializer());

        if (useEpoll()) {
            bootstrap.channel(EpollDatagramChannel.class)
                     .option(ChannelOption.SO_REUSEADDR, true)
                    // 允许接收目的 IP 为非本地 IP 的包（TPROXY 场景）
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    // 内核通过 ancillary data 把“原始目的地址”带上来
                    .option(EpollChannelOption.IP_RECVORIGDSTADDR, true);
        } else {
            bootstrap.channel(NioDatagramChannel.class)
                     .option(ChannelOption.SO_REUSEADDR, true);
        }

        bindFuture = bootstrap.bind(getIp(), getPort()).sync();
        serverChannel = bindFuture.channel();
    }

    @Override
    protected void doClose() throws InterruptedException {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().sync();
        }
    }
}
