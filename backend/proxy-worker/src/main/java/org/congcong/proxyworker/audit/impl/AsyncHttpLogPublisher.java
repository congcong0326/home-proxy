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
        this.scheduler.execute(() -> processQueue(authQueue, config.getAuthLogUrl(), "auth"));
        this.scheduler.execute(() -> processQueue(accessQueue, config.getAccessLogUrl(), "access"));
        log.info("AsyncHttpLogPublisher started with batch dispatch: batchSize={}, flushIntervalMs={}", batchSize, this.flushIntervalMs);
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

    // 单线程消费者：达到批量阈值或等待窗口到期即发送，避免固定调度导致堆积
    private <T> void processQueue(BlockingQueue<T> queue, String url, String queueName) {
        List<T> buffer = new ArrayList<>(batchSize);
        long lastFlushTime = System.nanoTime();
        final long maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // 等待下一条日志或超时，避免空转
                T item = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (item != null) {
                    buffer.add(item);
                    queue.drainTo(buffer, batchSize - buffer.size());
                }

                boolean hitBatchSize = buffer.size() >= batchSize;
                boolean hitMaxWait = !buffer.isEmpty() && (System.nanoTime() - lastFlushTime >= maxWaitNanos);
                if (hitBatchSize || hitMaxWait) {
                    sendBatch(new ArrayList<>(buffer), url);
                    buffer.clear();
                    lastFlushTime = System.nanoTime();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Log worker for {} interrupted", queueName);
        } catch (Exception e) {
            log.warn("Unexpected error in log worker {}: {}", queueName, e.toString());
        } finally {
            if (!buffer.isEmpty()) {
                sendBatch(buffer, url);
            }
        }
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Error sending logs (interrupted): {}", e.toString());
        } catch (IOException e) {
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
