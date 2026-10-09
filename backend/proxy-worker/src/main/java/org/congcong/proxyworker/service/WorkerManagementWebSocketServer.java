package org.congcong.proxyworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerManagementMessage;
import org.congcong.common.dto.WorkerManagementMessageType;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerManagementHeartbeat;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTPS/WebSocket endpoint hosted by a worker. The controller initiates the
 * connection; the worker uses that connected socket to poll for configuration,
 * publish heartbeats and flush buffered logs.
 */
@Slf4j
public final class WorkerManagementWebSocketServer {
    private static final String PATH = "/management/ws";
    private static final int MAX_HTTP_CONTENT_LENGTH = 64 * 1024;
    private static final int MAX_FRAME_SIZE = 4 * 1024 * 1024;

    private final ProxyWorkerConfig config;
    private final AggregateConfigService configService;
    private final String token;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Channel> controllerChannel = new AtomicReference<>();
    private final WorkerMetricsCollector metricsCollector;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worker-management-config-poller");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService telemetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worker-telemetry-sender");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Channel serverChannel;
    private static final io.netty.util.AttributeKey<Boolean> TELEMETRY_CAPABLE = io.netty.util.AttributeKey.valueOf("telemetryControllerV1");
    private volatile boolean telemetryAccepted;
    private volatile String pendingBatchId;
    private volatile long nextTelemetrySend;
    private volatile long telemetryBackoffMs = 2000;


    public WorkerManagementWebSocketServer(AggregateConfigService configService) {
        this.config = ProxyWorkerConfig.getInstance();
        this.configService = configService;
        this.metricsCollector = new WorkerMetricsCollector(configService::getManagedPollingConfigHash);
        this.token = ManagementTokenStore.loadOrCreate(config.getManagementTokenFile());
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void start() {
        try {
            SslContext sslContext = createSelfSignedSslContext();
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                            channel.pipeline().addLast(new UpgradeAuthorizationHandler(token));
                            channel.pipeline().addLast(new WebSocketServerProtocolHandler(PATH, null, true, MAX_FRAME_SIZE));
                            channel.pipeline().addLast(new ManagementFrameHandler());
                        }
                    });
            serverChannel = bootstrap.bind(config.getManagementPort()).syncUninterruptibly().channel();
            long interval = Math.max(500L, config.getManagementPollIntervalMs());
            pollScheduler.scheduleWithFixedDelay(this::safeSendConfigPoll, 0, interval, TimeUnit.MILLISECONDS);
            telemetryScheduler.scheduleWithFixedDelay(this::safeSendTelemetry, 0, interval, TimeUnit.MILLISECONDS);
            log.info("Worker WSS 管理端已启动, port={}, path={}", config.getManagementPort(), PATH);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        pollScheduler.shutdownNow();
        telemetryScheduler.shutdownNow();
        Channel active = controllerChannel.getAndSet(null);
        if (active != null) active.close();
        Channel server = serverChannel;
        if (server != null) server.close().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
    }

    private SslContext createSelfSignedSslContext() {
        SelfSignedCertificate certificate = null;
        try {
            certificate = new SelfSignedCertificate("home-proxy-worker");
            return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 worker WSS 自签名证书", e);
        } finally {
            if (certificate != null) certificate.delete();
        }
    }

    private void registerControllerChannel(Channel channel) {
        telemetryAccepted = false;
        pendingBatchId = null;
        nextTelemetrySend = 0;
        Channel previous = controllerChannel.getAndSet(channel);
        if (previous != null && previous != channel) previous.close();
        log.info("Controller 已连接到 worker WSS 管理端, remote={}", channel.remoteAddress());
        safeSendConfigPoll();
    }

    private void safeSendConfigPoll() {
        try {
            sendConfigPoll();
        } catch (Throwable error) {
            log.error("发送 worker WSS 心跳失败，下一周期继续重试", error);
        }
    }

    private void sendConfigPoll() {
        Channel active = controllerChannel.get();
        if (active == null || !active.isActive()) return;
        WorkerManagementHeartbeat heartbeat = new WorkerManagementHeartbeat();
        heartbeat.setHostname(metricsCollector.getHostname());
        heartbeat.setStartedAt(metricsCollector.getStartedAt().toString());
        heartbeat.setFetchedConfigHash(configService.getFetchedConfigHash());
        WorkerControlService.ConfigApplicationStatus applicationStatus = configService.getApplicationStatus();
        if (applicationStatus != null) {
            heartbeat.setAppliedConfigHash(applicationStatus.appliedConfigHash());
            heartbeat.setApplySuccess(applicationStatus.success());
            heartbeat.setApplyError(applicationStatus.errorSummary());
            heartbeat.setAppliedAt(applicationStatus.appliedAt() == null ? null : applicationStatus.appliedAt().toString());
        }
        WorkerMetricsDTO metrics = metricsCollector.collect();
        heartbeat.setMetrics(metrics);

        // CONFIG_POLL carries only config/heartbeat; legacy logs are never sent.
        WorkerManagementMessage message = new WorkerManagementMessage(
                WorkerManagementMessageType.CONFIG_POLL,
                config.getWorkerId(),
                configService.getManagedPollingConfigHash(),
                null,
                heartbeat,
                null,
                null);
        try {
            String json = objectMapper.writeValueAsString(message);
            active.eventLoop().execute(() -> active.writeAndFlush(new TextWebSocketFrame(json)));
        } catch (Exception e) {
            log.warn("发送配置轮询消息失败: {}", e.toString());
        }
    }

    private void safeSendTelemetry() {
        try { sendTelemetry(); }
        catch (Exception error) { log.warn("独立 telemetry 发送失败，保留内存批次等待重试: {}", error.toString()); }
    }

    private void sendTelemetry() throws Exception {
        Channel active = controllerChannel.get();
        var publisher = org.congcong.proxyworker.telemetry.TelemetryPublisher.getInstance();
        if (active == null || !active.isActive() || !Boolean.TRUE.equals(active.attr(TELEMETRY_CAPABLE).get()) || !publisher.enabled()) return;
        long now = System.currentTimeMillis();
        if (now < nextTelemetrySend) return;
        WorkerManagementMessage message = new WorkerManagementMessage();
        message.setWorkerId(config.getWorkerId());
        message.setTelemetryVersion(1);
        if (!telemetryAccepted) {
            message.setType(WorkerManagementMessageType.TELEMETRY_HELLO);
            message.setTelemetryStatus(publisher.status());
        } else {
            var batch = publisher.peek();
            if (batch == null) {
                message.setType(WorkerManagementMessageType.TELEMETRY_HELLO);
                message.setTelemetryStatus(publisher.status());
            } else {
                message.setType(WorkerManagementMessageType.TELEMETRY_BATCH);
                message.setTelemetryBatch(batch);
                message.setTelemetryStatus(publisher.status());
                if (batch.getBatchId().equals(pendingBatchId)) publisher.retried();
                pendingBatchId = batch.getBatchId();
            }
        }
        nextTelemetrySend = now + Math.max(5000, telemetryBackoffMs);
        String json = objectMapper.writeValueAsString(message);
        active.eventLoop().execute(() -> {
            // A config update can disable reporting after this frame was prepared.
            if (publisher.enabled()) active.writeAndFlush(new TextWebSocketFrame(json));
        });
    }

    private final class ManagementFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final StringBuilder pendingText = new StringBuilder();
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                registerControllerChannel(ctx.channel());
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
            // java.net.http may fragment large outbound text messages. Netty delivers
            // the first part as TextWebSocketFrame and the remaining parts as
            // ContinuationWebSocketFrame, so parse only after the final fragment.
            if (frame instanceof TextWebSocketFrame textFrame) {
                pendingText.setLength(0);
                pendingText.append(textFrame.text());
            } else if (frame instanceof ContinuationWebSocketFrame continuation) {
                pendingText.append(continuation.content().toString(StandardCharsets.UTF_8));
            } else {
                return;
            }
            if (!frame.isFinalFragment()) return;

            String json = pendingText.toString();
            pendingText.setLength(0);
            WorkerManagementMessage message = objectMapper.readValue(json, WorkerManagementMessage.class);
            if (!config.getWorkerId().equals(message.getWorkerId())) {
                log.warn("忽略 workerId 不匹配的管理消息");
                return;
            }
            if (message.getType() == WorkerManagementMessageType.TELEMETRY_ACCEPT && Integer.valueOf(1).equals(message.getTelemetryVersion())
                    && Boolean.TRUE.equals(ctx.channel().attr(TELEMETRY_CAPABLE).get())) {
                telemetryAccepted = true;
                nextTelemetrySend = 0;
                return;
            }
            if (message.getType() == WorkerManagementMessageType.TELEMETRY_ACK && message.getTelemetryAck() != null) {
                var ack = message.getTelemetryAck();
                if (ack.getBatchId() != null && ack.getBatchId().equals(pendingBatchId)) {
                    if (ack.isPersisted()) {
                        org.congcong.proxyworker.telemetry.TelemetryPublisher.getInstance().acknowledge(ack);
                        pendingBatchId = null;
                        telemetryBackoffMs = 2000;
                        nextTelemetrySend = 0;
                    } else {
                        telemetryBackoffMs = Math.min(60000, telemetryBackoffMs * 2);
                        nextTelemetrySend = System.currentTimeMillis() + telemetryBackoffMs;
                    }
                }
                return;
            }
            if (message.getType() == WorkerManagementMessageType.WOL_WAKE && message.getTaskPayload() != null) {
                new WolTaskExecutor().execute(new org.congcong.common.dto.WorkerTaskDTO(null, WolTaskExecutor.TASK_TYPE, message.getTaskPayload()));
            } else if (message.getType() == WorkerManagementMessageType.CONFIG && message.getConfig() != null) {
                configService.acceptManagedConfig(message.getConfig());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (controllerChannel.compareAndSet(ctx.channel(), null)) {
                telemetryAccepted = false;
                log.info("Controller WSS 管理连接已断开, remote={}", ctx.channel().remoteAddress());
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("worker WSS 管理连接异常: {}", cause.toString());
            ctx.close();
        }
    }

    private static final class UpgradeAuthorizationHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final byte[] expectedToken;

        private UpgradeAuthorizationHandler(String token) {
            this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!PATH.equals(pathOf(request.uri())) || !isAuthorized(request.headers().get(HttpHeaderNames.AUTHORIZATION))) {
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        PATH.equals(pathOf(request.uri())) ? HttpResponseStatus.UNAUTHORIZED : HttpResponseStatus.NOT_FOUND);
                ctx.writeAndFlush(response).addListener(future -> ctx.close());
                return;
            }
            ctx.channel().attr(TELEMETRY_CAPABLE).set("1".equals(request.headers().get("X-Home-Proxy-Telemetry")));
            ctx.fireChannelRead(ReferenceCountUtil.retain(request));
        }

        private boolean isAuthorized(String authorization) {
            String prefix = "Bearer ";
            if (authorization == null || !authorization.startsWith(prefix)) return false;
            return MessageDigest.isEqual(expectedToken, authorization.substring(prefix.length()).getBytes(StandardCharsets.UTF_8));
        }

        private static String pathOf(String uri) {
            int queryStart = uri.indexOf('?');
            return queryStart < 0 ? uri : uri.substring(0, queryStart);
        }
    }
}
