package org.congcong.controlmanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.controlmanager.dto.WorkerStatusDTO;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.entity.WorkerTask;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerControlService {

    public static final String DEFAULT_WORKER_ID = "default";
    public static final String TASK_TYPE_WOL_WAKE = "WOL_WAKE";
    private static final long ONLINE_THRESHOLD_SECONDS = 10L;

    private final WorkerTaskRepository workerTaskRepository;
    private final WorkerStatusRepository workerStatusRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkerTask createTask(String workerId, String taskType, Object payload) {
        WorkerTask task = new WorkerTask();
        task.setWorkerId(workerId);
        task.setTaskType(taskType);
        try {
            task.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("任务负载无法序列化: " + taskType, e);
        }
        return workerTaskRepository.save(task);
    }

    public WorkerTask createTask(String taskType, Object payload) {
        return createTask(DEFAULT_WORKER_ID, taskType, payload);
    }

    public WorkerStatusDTO getLatestStatus() {
        WorkerStatus status = workerStatusRepository.findById(DEFAULT_WORKER_ID).orElse(null);
        if (status == null) {
            return null;
        }
        return toStatusDto(status, LocalDateTime.now());
    }

    /** Records liveness for a worker using the controller-initiated WSS channel. */
    @Transactional
    public void recordManagementPoll(String workerId, String appliedConfigHash) {
        LocalDateTime now = LocalDateTime.now();
        WorkerStatus status = workerStatusRepository.findById(workerId).orElseGet(WorkerStatus::new);
        status.setWorkerId(workerId);
        status.setLastSeenAt(now);
        if (appliedConfigHash != null && !appliedConfigHash.isBlank()) {
            status.setFetchedConfigHash(appliedConfigHash);
            status.setAppliedConfigHash(appliedConfigHash);
            status.setApplySuccess(true);
            status.setApplyError(null);
            status.setAppliedAt(now);
        }
        workerStatusRepository.save(status);
    }

    /** Records the complete heartbeat and metrics carried by the management WebSocket. */
    @Transactional
    public void recordManagementPoll(String workerId, WorkerPollRequest request) {
        LocalDateTime now = LocalDateTime.now();
        WorkerPollRequest safeRequest = request == null ? new WorkerPollRequest() : request;
        WorkerStatus status = workerStatusRepository.findById(workerId).orElseGet(WorkerStatus::new);
        status.setWorkerId(workerId);
        status.setHostname(safeRequest.getHostname());
        status.setStartedAt(safeRequest.getStartedAt());
        status.setLastSeenAt(now);
        status.setFetchedConfigHash(firstNonBlank(safeRequest.getFetchedConfigHash(), safeRequest.getLastConfigHash()));
        status.setAppliedConfigHash(safeRequest.getAppliedConfigHash());
        status.setApplySuccess(safeRequest.getApplySuccess());
        status.setApplyError(truncate(safeRequest.getApplyError(), 1024));
        status.setAppliedAt(safeRequest.getAppliedAt());
        WorkerMetricsDTO metrics = safeRequest.getMetrics();
        if (metrics != null) {
            status.setMetricsJson(serializeMetrics(metrics));
            status.setUptimeSeconds(metrics.getUptimeSeconds());
            status.setHeapUsedBytes(metrics.getHeapUsedBytes());
            status.setHeapMaxBytes(metrics.getHeapMaxBytes());
            status.setRunningInboundCount(metrics.getRunningInboundCount());
            status.setActiveConnectionCount(metrics.getActiveConnectionCount());
        }
        workerStatusRepository.save(status);
    }

    private WorkerStatusDTO toStatusDto(WorkerStatus status, LocalDateTime now) {
        boolean online = status.getLastSeenAt() != null
                && status.getLastSeenAt().isAfter(now.minusSeconds(ONLINE_THRESHOLD_SECONDS));
        return new WorkerStatusDTO(
                status.getWorkerId(),
                status.getHostname(),
                status.getStartedAt(),
                status.getLastSeenAt(),
                status.getFetchedConfigHash(),
                status.getAppliedConfigHash(),
                status.getApplySuccess(),
                status.getApplyError(),
                status.getAppliedAt(),
                status.getUptimeSeconds(),
                status.getHeapUsedBytes(),
                status.getHeapMaxBytes(),
                status.getRunningInboundCount(),
                status.getActiveConnectionCount(),
                online
        );
    }

    private String serializeMetrics(WorkerMetricsDTO metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("worker metrics cannot be serialized", failure);
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
