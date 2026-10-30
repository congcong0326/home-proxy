package org.congcong.proxyworker.server.impl;

import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.udp.TransparentUdpServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.transparent.TransparentServerInitializer;
import org.congcong.proxyworker.server.TcpProxyServer;

@Slf4j
public class TransparentProxyServer extends TcpProxyServer {

    private TransparentUdpServer udpServer;
    private volatile boolean udpFailed;

    public TransparentProxyServer(InboundConfig cfg) {
        super(cfg);
    }

    @Override
    public int getPort() {
        return getInboundConfig().getPort();
    }

    @Override
    public String getIp() {
        return getInboundConfig().getListenIp();
    }

    @Override
    public String getServerName() {
        return getInboundConfig().getName();
    }

    @Override
    protected ChannelInitializer<Channel> createChildHandler(InboundConfig inboundConfig) {
        return new TransparentServerInitializer(inboundConfig);
    }

    @Override
    protected void doStart() throws InterruptedException {
        super.doStart();
        startUdp(false);
    }

    /** A failed UDP listener is retried on reload; TCP is never restarted for it. */
    @Override
    protected void onRuntimeConfigUpdated() {
        if (udpFailed) startUdp(true);
    }

    public synchronized boolean isUdpRunning() {
        return udpServer != null && udpServer.isRunning();
    }

    private synchronized void startUdp(boolean retry) {
        if (retry && (!udpFailed || !isRunning())) return;
        udpServer = new TransparentUdpServer(this::getRuntimeConfigSnapshot);
        try {
            udpServer.start();
            udpFailed = false;
            if (retry) log.info("Transparent UDP listener recovered on {}:{}", getIp(), getPort());
        } catch (Exception failure) {
            udpFailed = true;
            log.error("Transparent UDP startup failed; TCP listener remains available, retrying on next config update",
                    failure);
            closeUdp();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private synchronized void closeUdp() {
        if (udpServer == null) return;
        try {
            udpServer.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void doClose() throws InterruptedException {
        try {
            closeUdp();
        } finally {
            super.doClose();
        }
    }

}
