package org.congcong.proxyworker.audit.impl;

import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.proxyworker.audit.LogPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Buffers worker logs until the controller-facing management WebSocket sends
 * the next CONFIG_POLL frame. No outbound HTTP request is made by this
 * publisher.
 */
public final class WebSocketLogPublisher implements LogPublisher {
    private static final Logger log = LoggerFactory.getLogger(WebSocketLogPublisher.class);

    private final BlockingQueue<AuthLog> authQueue;
    private final BlockingQueue<AccessLog> accessQueue;

    public WebSocketLogPublisher(int queueCapacity) {
        int capacity = Math.max(1, queueCapacity);
        this.authQueue = new ArrayBlockingQueue<>(capacity);
        this.accessQueue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void publishAuth(AuthLog logItem) {
        if (logItem == null) return;
        if (!authQueue.offer(logItem)) {
            log.warn("Auth log queue full, dropping log");
        }
    }

    @Override
    public void publishAccess(AccessLog logItem) {
        if (logItem == null) return;
        if (!accessQueue.offer(logItem)) {
            log.warn("Access log queue full, dropping log");
        }
    }

    public List<AccessLog> drainAccess(int maxItems) {
        return drain(accessQueue, maxItems);
    }

    public List<AuthLog> drainAuth(int maxItems) {
        return drain(authQueue, maxItems);
    }

    public void requeueAccess(List<AccessLog> logs) {
        if (logs == null) return;
        for (AccessLog item : logs) {
            if (item != null && !accessQueue.offer(item)) {
                log.warn("Access log queue full while requeueing failed batch, dropping log");
            }
        }
    }

    public void requeueAuth(List<AuthLog> logs) {
        if (logs == null) return;
        for (AuthLog item : logs) {
            if (item != null && !authQueue.offer(item)) {
                log.warn("Auth log queue full while requeueing failed batch, dropping log");
            }
        }
    }

    @Override
    public void shutdown() {
        accessQueue.clear();
        authQueue.clear();
    }

    private static <T> List<T> drain(BlockingQueue<T> queue, int maxItems) {
        int limit = Math.max(1, maxItems);
        List<T> result = new ArrayList<>(limit);
        queue.drainTo(result, limit);
        return result;
    }
}
