package org.congcong.proxyworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.common.dto.WorkerPollResponse;
import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class WorkerControlService {

    private static final long MAX_FAILURE_POLL_INTERVAL_MS = 10_000L;

    private final String workerId;
    private final long defaultPollIntervalMs;
    private final PollTransport pollTransport;
    private final WorkerMetricsCollector metricsCollector;
    private final WorkerTaskExecutor taskExecutor;
    private final ConcurrentLinkedQueue<WorkerTaskResultDTO> pendingResults = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile long currentPollIntervalMs;

    public WorkerControlService() {
        this(ProxyWorkerConfig.getInstance());
    }

    public WorkerControlService(ProxyWorkerConfig config) {
        this(
                config.getWorkerId(),
                config.getWorkerControlPollIntervalMs(),
                new HttpPollTransport(config.getWorkerPollUrl()),
                new WorkerMetricsCollector(),
                new WolTaskExecutor()
        );
    }

    public static WorkerControlService forAggregateConfigService(AggregateConfigService configService) {
        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();
        WorkerMetricsCollector metricsCollector = new WorkerMetricsCollector(() -> {
            AggregateConfigResponse currentConfig = configService.getCurrentConfig();
            return currentConfig == null ? null : currentConfig.getConfigHash();
        });
        return new WorkerControlService(
                config.getWorkerId(),
                config.getWorkerControlPollIntervalMs(),
                new HttpPollTransport(config.getWorkerPollUrl()),
                metricsCollector,
                new WolTaskExecutor()
        );
    }

    WorkerControlService(String workerId,
                         long defaultPollIntervalMs,
                         PollTransport pollTransport,
                         WorkerMetricsCollector metricsCollector,
                         WorkerTaskExecutor taskExecutor) {
        this.workerId = workerId == null || workerId.isBlank() ? "default" : workerId.trim();
        this.defaultPollIntervalMs = Math.max(500L, defaultPollIntervalMs);
        this.currentPollIntervalMs = this.defaultPollIntervalMs;
        this.pollTransport = pollTransport;
        this.metricsCollector = metricsCollector;
        this.taskExecutor = taskExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "worker-control-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting worker control polling, interval={}ms", defaultPollIntervalMs);
            scheduler.schedule(this::pollLoopOnce, 0, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    WorkerPollResponse pollOnce() {
        List<WorkerTaskResultDTO> resultsToReport = new ArrayList<>(pendingResults);
        WorkerPollRequest request = new WorkerPollRequest();
        request.setWorkerId(workerId);
        request.setHostname(metricsCollector.getHostname());
        request.setStartedAt(metricsCollector.getStartedAt());
        request.setLastConfigHash(metricsCollector.getLastConfigHash());
        request.setMetrics(metricsCollector.collect());
        request.setTaskResults(resultsToReport);

        WorkerPollResponse response = pollTransport.poll(request);
        pendingResults.removeAll(resultsToReport);

        List<WorkerTaskDTO> tasks = response == null || response.getTasks() == null
                ? Collections.emptyList()
                : response.getTasks();
        for (WorkerTaskDTO task : tasks) {
            pendingResults.add(taskExecutor.execute(task));
        }
        if (response != null && response.getNextPollIntervalMillis() != null) {
            currentPollIntervalMs = Math.max(500L, response.getNextPollIntervalMillis());
        } else {
            currentPollIntervalMs = defaultPollIntervalMs;
        }
        return response;
    }

    private void pollLoopOnce() {
        long nextDelay = currentPollIntervalMs;
        try {
            pollOnce();
            nextDelay = currentPollIntervalMs;
        } catch (RuntimeException e) {
            nextDelay = Math.min(MAX_FAILURE_POLL_INTERVAL_MS, Math.max(5000L, currentPollIntervalMs * 2));
            currentPollIntervalMs = nextDelay;
            log.warn("Worker control poll failed, next retry in {}ms: {}", nextDelay, e.toString());
        } finally {
            if (running.get()) {
                scheduler.schedule(this::pollLoopOnce, nextDelay, TimeUnit.MILLISECONDS);
            }
        }
    }

    public interface PollTransport {
        WorkerPollResponse poll(WorkerPollRequest request);
    }

    private static class HttpPollTransport implements PollTransport {
        private final String url;
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;

        private HttpPollTransport(String url) {
            this.url = url;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(5000))
                    .build();
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new JavaTimeModule());
            this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Override
        public WorkerPollResponse poll(WorkerPollRequest request) {
            try {
                String body = objectMapper.writeValueAsString(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(5000))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("worker poll failed, status=" + response.statusCode());
                }
                return objectMapper.readValue(response.body(), WorkerPollResponse.class);
            } catch (IOException e) {
                throw new IllegalStateException("worker poll request failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker poll interrupted", e);
            }
        }
    }
}
