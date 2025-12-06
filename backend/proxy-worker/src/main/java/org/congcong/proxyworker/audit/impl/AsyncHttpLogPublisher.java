package org.congcong.proxyworker.audit.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.proxyworker.audit.LogPublisher;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步HTTP日志发布器
 * - 将日志对象入队，后台线程定期批量发送到管理端
 * - 简化实现：两条队列分别处理认证日志与访问日志
 */
public class AsyncHttpLogPublisher implements LogPublisher {
    private static final Logger log = LoggerFactory.getLogger(AsyncHttpLogPublisher.class);

    // 队列与批量参数
    private final BlockingQueue<AuthLog> authQueue;
    private final BlockingQueue<AccessLog> accessQueue;
    private final int batchSize;
    private final long flushIntervalMs;

    // 发送依赖
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProxyWorkerConfig config;

    // 调度线程
    private final ScheduledExecutorService scheduler;

    public AsyncHttpLogPublisher(int queueCapacity, int batchSize, long flushIntervalMs) {
        this.authQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.accessQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(200, flushIntervalMs);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.config = ProxyWorkerConfig.getInstance();

        this.scheduler = Executors.newScheduledThreadPool(2);
        // 定时任务：按时间窗口发送
        //this.scheduler.scheduleAtFixedRate(this::drainAndSendAuth, this.flushIntervalMs, this.flushIntervalMs, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::drainAndSendAccess, this.flushIntervalMs, this.flushIntervalMs, TimeUnit.MILLISECONDS);
        log.info("AsyncHttpLogPublisher started: batchSize={}, flushIntervalMs={}", batchSize, flushIntervalMs);
    }

    @Override
    public void publishAuth(AuthLog logItem) {
        if (logItem == null) return;
        boolean offered = authQueue.offer(logItem);
        if (!offered) {
            // 队列满时丢弃并记录；可升级为本地回退文件
            log.warn("Auth log queue full, dropping log");
        }
    }

    @Override
    public void publishAccess(AccessLog logItem) {
        if (logItem == null) return;
        boolean offered = accessQueue.offer(logItem);
        if (!offered) {
            log.warn("Access log queue full, dropping log");
        }
    }

    private void drainAndSendAuth() {
        List<AuthLog> batch = drainBatch(authQueue, batchSize);
        if (batch.isEmpty()) return;
        sendBatch(batch, config.getAuthLogUrl());
    }

    private void drainAndSendAccess() {
        List<AccessLog> batch = drainBatch(accessQueue, batchSize);
        if (batch.isEmpty()) return;
        sendBatch(batch, config.getAccessLogUrl());
    }

    private <T> List<T> drainBatch(BlockingQueue<T> queue, int max) {
        List<T> list = new ArrayList<>(max);
        queue.drainTo(list, max);
        return list;
    }

    private <T> void sendBatch(List<T> batch, String url) {
        try {
            String body = objectMapper.writeValueAsString(batch);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(5000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.debug("Sent {} logs to {}", batch.size(), url);
            } else {
                log.warn("Failed to send logs: status={}, url={}, bodyLen={}", resp.statusCode(), url, body.length());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Error sending logs: {}", e.toString());
        }
    }



    @Override
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("AsyncHttpLogPublisher stopped");
    }
}