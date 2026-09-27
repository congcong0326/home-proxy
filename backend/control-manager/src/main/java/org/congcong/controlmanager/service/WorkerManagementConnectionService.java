package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.WorkerManagementMessage;
import org.congcong.common.dto.WorkerManagementMessageType;
import org.congcong.common.dto.WorkerManagementHeartbeat;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.net.Socket;
import javax.net.ssl.SSLEngine;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Maintains controller-initiated WSS connections to configured workers. A
 * worker sends CONFIG_POLL through its connected socket with heartbeat data
 * and buffered logs; this service ingests them and replies with a configuration
 * only when the worker's hash is stale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerManagementConnectionService {
    private static final long RECONCILE_INTERVAL_SECONDS = 3L;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 15L;

    static {
        // Must be set before java.net.http initializes its SSL hostname verifier.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private final ProxyWorkerRepository proxyWorkerRepository;
    private final AggregateConfigCacheService aggregateConfigCacheService;
    private final WorkerControlService workerControlService;
    private final LogService logService;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worker-wss-connection-manager");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HttpClient webSocketClient = createWebSocketClient();

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::safeReconcile, 0, RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Keep the scheduler alive even when a database/network/client failure escapes a callback. */
    private void safeReconcile() {
        try {
            reconcile();
        } catch (Throwable error) {
            log.error("同步 worker WSS 连接时发生未捕获异常，下一周期继续重试", error);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        connections.values().forEach(connection -> connection.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "controller stopping"));
        connections.clear();
    }

    private void reconcile() {
        try {
            Set<String> configuredWorkerIds = new HashSet<>();
            for (ProxyWorker worker : proxyWorkerRepository.findAll()) {
                if (!Boolean.TRUE.equals(worker.getEnabled()) || isBlank(worker.getManagementUrl()) || isBlank(worker.getManagementToken())) {
                    continue;
                }
                configuredWorkerIds.add(worker.getWorkerId());
                Connection existing = connections.get(worker.getWorkerId());
                boolean stale = existing != null && existing.isStale();
                if (existing != null && existing.matches(worker) && !stale
                        && !existing.webSocket().isInputClosed() && !existing.webSocket().isOutputClosed()
                        && !existing.listener().closed) continue;
                if (existing != null && connections.remove(worker.getWorkerId(), existing)) {
                    if (stale) {
                        log.warn("worker WSS 超过 {} 秒未收到心跳, workerId={}, 重新连接",
                                HEARTBEAT_TIMEOUT_SECONDS, worker.getWorkerId());
                        existing.webSocket().abort();
                    } else {
                        existing.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "worker connection changed");
                    }
                }
                connect(worker);
            }
            connections.forEach((workerId, connection) -> {
                if (configuredWorkerIds.contains(workerId)) return;
                if (connections.remove(workerId, connection)) {
                    connection.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "worker disabled or removed");
                }
            });
        } catch (Exception e) {
            log.warn("同步 worker WSS 连接失败", e);
        }
    }

    private void connect(ProxyWorker worker) {
        String workerId = worker.getWorkerId();
        if (!connecting.add(workerId)) return;
        URI uri;
        try {
            uri = URI.create(worker.getManagementUrl());
        } catch (IllegalArgumentException e) {
            connecting.remove(workerId);
            log.warn("worker {} 的管理地址无效", workerId);
            return;
        }
        String token = worker.getManagementToken();
        WorkerWebSocketListener listener = new WorkerWebSocketListener(workerId);
        webSocketClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .buildAsync(uri, listener)
                .whenComplete((socket, error) -> {
                    connecting.remove(workerId);
                    if (error != null) {
                        log.warn("连接 worker WSS 失败, workerId={}, url={}", workerId, worker.getManagementUrl(), error);
                        return;
                    }
                    // A close can race with completion. Never retain a dead socket: it
                    // would make reconcile believe the worker is connected forever.
                    if (socket == null || listener.closed) {
                        log.warn("worker WSS 握手后连接已关闭, workerId={}, 将重试", workerId);
                        return;
                    }
                    connections.put(workerId, new Connection(worker.getManagementUrl(), token, socket, listener));
                    log.info("已连接 worker WSS 管理端, workerId={}", workerId);
                });
    }

    /** Sends a direct management command to a connected worker. */
    public void sendWol(String workerId, org.congcong.common.dto.WolWakeTaskPayload payload) {
        Connection connection = connections.get(workerId);
        if (connection == null || connection.isStale() || connection.listener().closed
                || connection.webSocket().isInputClosed() || connection.webSocket().isOutputClosed()) {
            throw new IllegalStateException("worker 未连接: " + workerId);
        }
        try {
            WorkerManagementMessage message = new WorkerManagementMessage();
            message.setType(WorkerManagementMessageType.WOL_WAKE);
            message.setWorkerId(workerId);
            message.setTaskPayload(objectMapper.convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {}));
            connection.webSocket().sendText(objectMapper.writeValueAsString(message), true);
        } catch (Exception e) { throw new IllegalStateException("发送 WOL 到 worker 失败", e); }
    }

    private void handleMessage(WorkerWebSocketListener listener, WebSocket socket, String json) {
        String expectedWorkerId = listener.workerId;
        try {
            WorkerManagementMessage request = objectMapper.readValue(json, WorkerManagementMessage.class);
            if (request.getType() != WorkerManagementMessageType.CONFIG_POLL
                    || !expectedWorkerId.equals(request.getWorkerId())) {
                log.warn("收到无效 worker WSS 管理消息, workerId={}", expectedWorkerId);
                return;
            }
            listener.lastPollNanos = System.nanoTime();
            WorkerManagementHeartbeat heartbeat = request.getHeartbeat();
            if (heartbeat != null) {
                workerControlService.recordManagementPoll(expectedWorkerId, toPollRequest(heartbeat));
            } else {
                workerControlService.recordManagementPoll(expectedWorkerId, request.getConfigHash());
            }
            if (request.getAccessLogs() != null && !request.getAccessLogs().isEmpty()) {
                logService.saveAccessLogs(expectedWorkerId, request.getAccessLogs());
            }
            if (request.getAuthLogs() != null && !request.getAuthLogs().isEmpty()) {
                logService.saveAuthLogs(expectedWorkerId, request.getAuthLogs());
            }
            AggregateConfigResponse config = aggregateConfigCacheService.getAggregateConfig(expectedWorkerId);
            WorkerManagementMessage response;
            if (config.getConfigHash() != null && config.getConfigHash().equals(request.getConfigHash())) {
                response = new WorkerManagementMessage(WorkerManagementMessageType.CONFIG_UNCHANGED,
                        expectedWorkerId, config.getConfigHash(), null);
            } else {
                response = new WorkerManagementMessage(WorkerManagementMessageType.CONFIG,
                        expectedWorkerId, config.getConfigHash(), config);
            }
            socket.sendText(objectMapper.writeValueAsString(response), true);
        } catch (Exception e) {
            log.warn("处理 worker WSS 配置轮询失败, workerId={}, error={}", expectedWorkerId, e.toString());
        }
    }

    private WorkerPollRequest toPollRequest(WorkerManagementHeartbeat heartbeat) {
        WorkerPollRequest poll = new WorkerPollRequest();
        poll.setHostname(heartbeat.getHostname());
        poll.setStartedAt(parseDateTime(heartbeat.getStartedAt()));
        poll.setFetchedConfigHash(heartbeat.getFetchedConfigHash());
        poll.setAppliedConfigHash(heartbeat.getAppliedConfigHash());
        poll.setApplySuccess(heartbeat.getApplySuccess());
        poll.setApplyError(heartbeat.getApplyError());
        poll.setAppliedAt(parseDateTime(heartbeat.getAppliedAt()));
        poll.setMetrics(heartbeat.getMetrics());
        return poll;
    }

    private java.time.LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(value);
        } catch (RuntimeException error) {
            log.warn("忽略格式非法的 worker WSS 时间字段: {}", value);
            return null;
        }
    }

    private static HttpClient createWebSocketClient() {
        try {
            // Worker management certificates are generated locally at startup.
            // The bearer token is the deliberately simple controller credential.
            // Worker management endpoints use a startup-generated self-signed
            // certificate without IP SANs. An X509TrustManager is wrapped by
            // JSSE's identity checker for java.net.http WebSocket connections,
            // which still rejects an IP URL with "No subject alternative names
            // present" even when endpoint identification is disabled. Using an
            // X509ExtendedTrustManager keeps all socket/engine trust decisions in
            // this deliberately trusted private management channel.
            X509ExtendedTrustManager trustManager = new X509ExtendedTrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext).sslParameters(sslParameters).build();
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 worker WSS 客户端", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Connection(String managementUrl, String managementToken, WebSocket webSocket,
                              WorkerWebSocketListener listener) {
        boolean matches(ProxyWorker worker) {
            return managementUrl.equals(worker.getManagementUrl()) && managementToken.equals(worker.getManagementToken());
        }

        boolean isStale() {
            return System.nanoTime() - listener.lastPollNanos
                    >= TimeUnit.SECONDS.toNanos(HEARTBEAT_TIMEOUT_SECONDS);
        }
    }

    private final class WorkerWebSocketListener implements WebSocket.Listener {
        private final String workerId;
        private final StringBuilder pendingText = new StringBuilder();
        private volatile long lastPollNanos = System.nanoTime();
        private volatile boolean closed;

        private WorkerWebSocketListener(String workerId) {
            this.workerId = workerId;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            lastPollNanos = System.nanoTime();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pendingText.append(data);
            if (last) {
                String message = pendingText.toString();
                pendingText.setLength(0);
                handleMessage(this, webSocket, message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            connections.computeIfPresent(workerId, (ignored, connection) -> connection.webSocket() == webSocket ? null : connection);
            log.warn("worker WSS 已关闭, workerId={}, code={}, reason={}; 将由 reconcile 重连", workerId, statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            connections.computeIfPresent(workerId, (ignored, connection) -> connection.webSocket() == webSocket ? null : connection);
            log.warn("worker WSS 连接异常, workerId={}", workerId, error);
        }
    }
}
