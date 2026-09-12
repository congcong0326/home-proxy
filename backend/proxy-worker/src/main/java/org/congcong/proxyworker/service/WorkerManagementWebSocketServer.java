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
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTPS/WebSocket endpoint hosted by a worker. The controller initiates the
 * connection; the worker uses that connected socket to poll for configuration.
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
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worker-management-config-poller");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Channel serverChannel;

    public WorkerManagementWebSocketServer(AggregateConfigService configService) {
        this.config = ProxyWorkerConfig.getInstance();
        this.configService = configService;
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
            pollScheduler.scheduleWithFixedDelay(this::sendConfigPoll, 0, interval, TimeUnit.MILLISECONDS);
            log.info("Worker WSS 管理端已启动, port={}, path={}", config.getManagementPort(), PATH);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        pollScheduler.shutdownNow();
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
        Channel previous = controllerChannel.getAndSet(channel);
        if (previous != null && previous != channel) previous.close();
        log.info("Controller 已连接到 worker WSS 管理端, remote={}", channel.remoteAddress());
        sendConfigPoll();
    }

    private void sendConfigPoll() {
        Channel active = controllerChannel.get();
        if (active == null || !active.isActive()) return;
        WorkerManagementMessage message = new WorkerManagementMessage(
                WorkerManagementMessageType.CONFIG_POLL,
                config.getWorkerId(),
                configService.getManagedPollingConfigHash(),
                null);
        try {
            String json = objectMapper.writeValueAsString(message);
            active.eventLoop().execute(() -> active.writeAndFlush(new TextWebSocketFrame(json)));
        } catch (Exception e) {
            log.warn("发送配置轮询消息失败: {}", e.toString());
        }
    }

    private final class ManagementFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                registerControllerChannel(ctx.channel());
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
            if (!(frame instanceof TextWebSocketFrame textFrame)) return;
            WorkerManagementMessage message = objectMapper.readValue(textFrame.text(), WorkerManagementMessage.class);
            if (!config.getWorkerId().equals(message.getWorkerId())) {
                log.warn("忽略 workerId 不匹配的管理消息");
                return;
            }
            if (message.getType() == WorkerManagementMessageType.CONFIG && message.getConfig() != null) {
                configService.acceptManagedConfig(message.getConfig());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            controllerChannel.compareAndSet(ctx.channel(), null);
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
