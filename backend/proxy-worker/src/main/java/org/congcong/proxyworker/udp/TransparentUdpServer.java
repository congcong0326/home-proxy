package org.congcong.proxyworker.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.AbstractProxyServer.RuntimeConfigSnapshot;
import org.congcong.proxyworker.server.UdpProxyServer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** A single-loop companion of a TCP transparent listener. */
@Slf4j
public final class TransparentUdpServer extends UdpProxyServer {
    private final Supplier<RuntimeConfigSnapshot> runtimeConfig;
    private final UdpRelayCounters counters = new UdpRelayCounters();
    private UdpSessionManager sessions;
    private ScheduledFuture<?> sweepTask;
    private ScheduledFuture<?> summaryTask;
    private long loggedActivity;

    public TransparentUdpServer(Supplier<RuntimeConfigSnapshot> runtimeConfig) {
        super(runtimeConfig.get().inboundConfig());
        this.runtimeConfig = runtimeConfig;
    }

    @Override public int getPort() { return getInboundConfig().getPort(); }
    @Override public String getIp() { return getInboundConfig().getListenIp(); }
    @Override public String getServerName() { return getInboundConfig().getName() + " UDP"; }
    public UdpRelayCounters counters() { return counters; }

    @Override public ChannelInitializer<Channel> getChannelInitializer() {
        return new ChannelInitializer<>() {
            @Override protected void initChannel(Channel channel) {
                UdpRelaySettings settings = UdpRelaySettings.fromSystemProperties();
                EventLoop loop = channel.eventLoop();
                sessions = new UdpSessionManager(loop, runtimeConfig, settings, new DirectUdpOutboundConnector(),
                        new ReplySocketPool(loop, counters), counters);
                channel.pipeline().addLast(new TransparentUdpInboundHandler(sessions));
                sweepTask = loop.scheduleAtFixedRate(sessions::sweep, 1, 1, TimeUnit.SECONDS);
                summaryTask = loop.scheduleAtFixedRate(() -> {
                    long activity = counters.activity();
                    if (activity != loggedActivity) {
                        log.info("UDP relay {}:{} {}", getIp(), getPort(), counters.snapshot());
                        loggedActivity = activity;
                    }
                }, 5, 5, TimeUnit.MINUTES);
            }
        };
    }

    @Override protected void doStart() throws InterruptedException {
        if (!useEpoll()) {
            log.warn("Transparent UDP listener skipped: native epoll is required");
            return;
        }
        UdpRelaySettings settings = UdpRelaySettings.fromSystemProperties();
        workerGroup = new EpollEventLoopGroup(1);
        bindFuture = new Bootstrap().group(workerGroup).channel(EpollDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(EpollChannelOption.IP_TRANSPARENT, true)
                .option(EpollChannelOption.IP_RECVORIGDSTADDR, true)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(settings.maxDatagramSize()))
                .handler(getChannelInitializer()).bind(getIp(), getPort()).sync();
        serverChannel = bindFuture.channel();
    }

    @Override protected void doClose() throws InterruptedException {
        if (workerGroup != null) {
            workerGroup.next().submit(() -> {
                if (sweepTask != null) sweepTask.cancel(false);
                if (summaryTask != null) summaryTask.cancel(false);
                if (sessions != null) {
                    sessions.close();
                    if (counters.activity() > 0) log.info("UDP relay closed {}:{} {}", getIp(), getPort(), counters.snapshot());
                }
            }).sync();
        }
        super.doClose();
    }
}
