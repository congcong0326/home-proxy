package org.congcong.proxyworker.service;

import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.proxyworker.server.ProxyContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

public class WorkerMetricsCollector {

    private final LocalDateTime startedAt;
    private final Supplier<Integer> runningInboundCountSupplier;
    private final Supplier<String> lastConfigHashSupplier;
    private final String hostname;

    public WorkerMetricsCollector() {
        this(() -> null);
    }

    public WorkerMetricsCollector(Supplier<String> lastConfigHashSupplier) {
        this(
                LocalDateTime.now(),
                () -> ProxyContext.getInstance().getRunningServerCount(),
                lastConfigHashSupplier,
                resolveHostname()
        );
    }

    WorkerMetricsCollector(LocalDateTime startedAt,
                           Supplier<Integer> runningInboundCountSupplier,
                           Supplier<String> lastConfigHashSupplier,
                           String hostname) {
        this.startedAt = startedAt;
        this.runningInboundCountSupplier = runningInboundCountSupplier;
        this.lastConfigHashSupplier = lastConfigHashSupplier;
        this.hostname = hostname;
    }

    public WorkerMetricsDTO collect() {
        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return new WorkerMetricsDTO(
                Duration.between(startedAt, LocalDateTime.now()).toSeconds(),
                heapUsed,
                heapMax,
                runningInboundCountSupplier.get(),
                ProxyContext.getInstance().getActiveConnectionCount()
        );
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public String getHostname() {
        return hostname;
    }

    public String getLastConfigHash() {
        return lastConfigHashSupplier.get();
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
