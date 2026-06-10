package org.congcong.controlmanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.common.dto.WorkerPollResponse;
import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;
import org.congcong.controlmanager.dto.WorkerStatusDTO;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.entity.WorkerTask;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerControlService {

    public static final String DEFAULT_WORKER_ID = "default";
    public static final String TASK_TYPE_WOL_WAKE = "WOL_WAKE";
    private static final int MAX_TASKS_PER_POLL = 10;
    private static final long DEFAULT_NEXT_POLL_INTERVAL_MILLIS = 2000L;
    private static final long ONLINE_THRESHOLD_SECONDS = 10L;
    private static final TypeReference<Map<String, Object>> PAYLOAD_MAP_TYPE = new TypeReference<>() {
    };

    private final WorkerTaskRepository workerTaskRepository;
    private final WorkerStatusRepository workerStatusRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkerPollResponse poll(WorkerPollRequest request) {
        LocalDateTime now = LocalDateTime.now();
        WorkerPollRequest safeRequest = request == null ? new WorkerPollRequest() : request;

        updateWorkerStatus(safeRequest, now);
        storeTaskResults(safeRequest.getTaskResults(), now);

        List<WorkerTask> pendingTasks = workerTaskRepository.findByConsumedAtIsNullOrderByCreatedAtAsc(
                PageRequest.of(0, MAX_TASKS_PER_POLL));
        List<WorkerTaskDTO> taskDtos = pendingTasks.stream()
                .map(task -> toDto(task, now))
                .toList();
        if (!pendingTasks.isEmpty()) {
            workerTaskRepository.saveAll(pendingTasks);
        }

        return new WorkerPollResponse(now, DEFAULT_NEXT_POLL_INTERVAL_MILLIS, taskDtos);
    }

    @Transactional
    public WorkerTask createTask(String taskType, Object payload) {
        WorkerTask task = new WorkerTask();
        task.setTaskType(taskType);
        try {
            task.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("任务负载无法序列化: " + taskType, e);
        }
        return workerTaskRepository.save(task);
    }

    public WorkerStatusDTO getLatestStatus() {
        WorkerStatus status = workerStatusRepository.findById(DEFAULT_WORKER_ID).orElse(null);
        if (status == null) {
            return null;
        }
        return toStatusDto(status, LocalDateTime.now());
    }

    private void updateWorkerStatus(WorkerPollRequest request, LocalDateTime now) {
        String workerId = normalizeWorkerId(request.getWorkerId());
        WorkerStatus status = workerStatusRepository.findById(workerId).orElseGet(WorkerStatus::new);
        status.setWorkerId(workerId);
        status.setHostname(request.getHostname());
        status.setStartedAt(request.getStartedAt());
        status.setLastSeenAt(now);
        status.setLastConfigHash(request.getLastConfigHash());

        WorkerMetricsDTO metrics = request.getMetrics();
        if (metrics != null) {
            status.setUptimeSeconds(metrics.getUptimeSeconds());
            status.setHeapUsedBytes(metrics.getHeapUsedBytes());
            status.setHeapMaxBytes(metrics.getHeapMaxBytes());
            status.setRunningInboundCount(metrics.getRunningInboundCount());
            status.setActiveConnectionCount(metrics.getActiveConnectionCount());
        }

        workerStatusRepository.save(status);
    }

    private void storeTaskResults(List<WorkerTaskResultDTO> taskResults, LocalDateTime now) {
        List<WorkerTaskResultDTO> safeResults = taskResults == null ? Collections.emptyList() : taskResults;
        for (WorkerTaskResultDTO result : safeResults) {
            if (result == null || result.getTaskId() == null) {
                continue;
            }
            workerTaskRepository.findById(result.getTaskId()).ifPresent(task -> {
                task.setResultSuccess(Boolean.TRUE.equals(result.getSuccess()));
                task.setResultMessage(result.getMessage());
                task.setResultReportedAt(result.getFinishedAt() == null ? now : result.getFinishedAt());
                workerTaskRepository.save(task);
            });
        }
    }

    private WorkerTaskDTO toDto(WorkerTask task, LocalDateTime consumedAt) {
        task.setConsumedAt(consumedAt);
        return new WorkerTaskDTO(task.getId(), task.getTaskType(), parsePayload(task));
    }

    private Map<String, Object> parsePayload(WorkerTask task) {
        try {
            return objectMapper.readValue(task.getPayloadJson(), PAYLOAD_MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("解析worker任务负载失败, taskId={}", task.getId(), e);
            return Map.of();
        }
    }

    private WorkerStatusDTO toStatusDto(WorkerStatus status, LocalDateTime now) {
        boolean online = status.getLastSeenAt() != null
                && status.getLastSeenAt().isAfter(now.minusSeconds(ONLINE_THRESHOLD_SECONDS));
        return new WorkerStatusDTO(
                status.getWorkerId(),
                status.getHostname(),
                status.getStartedAt(),
                status.getLastSeenAt(),
                status.getLastConfigHash(),
                status.getUptimeSeconds(),
                status.getHeapUsedBytes(),
                status.getHeapMaxBytes(),
                status.getRunningInboundCount(),
                status.getActiveConnectionCount(),
                online
        );
    }

    private String normalizeWorkerId(String workerId) {
        if (workerId == null || workerId.trim().isEmpty()) {
            return DEFAULT_WORKER_ID;
        }
        return workerId.trim();
    }
}
