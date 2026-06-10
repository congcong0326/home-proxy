package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.common.dto.WorkerPollResponse;
import org.congcong.common.dto.WorkerTaskResultDTO;
import org.congcong.controlmanager.dto.WorkerStatusDTO;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.entity.WorkerTask;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerControlServiceTest {

    private final WorkerTaskRepository taskRepository = mock(WorkerTaskRepository.class);
    private final WorkerStatusRepository statusRepository = mock(WorkerStatusRepository.class);
    private final WorkerControlService service = new WorkerControlService(
            taskRepository,
            statusRepository,
            new ObjectMapper().registerModule(new JavaTimeModule())
    );

    @Test
    void pollConsumesPendingTasksSoTheyAreNotReturnedAgain() {
        WorkerTask task = new WorkerTask();
        task.setId(1002L);
        task.setTaskType("WOL_WAKE");
        task.setPayloadJson("{\"deviceName\":\"NAS\",\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"broadcastIp\":\"192.168.1.255\",\"port\":9}");

        when(statusRepository.findById("default")).thenReturn(Optional.empty());
        when(taskRepository.findByConsumedAtIsNullOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(task))
                .thenReturn(List.of());
        when(statusRepository.save(any(WorkerStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerPollResponse first = service.poll(pollRequest(List.of()));
        WorkerPollResponse second = service.poll(pollRequest(List.of()));

        assertThat(first.getTasks()).hasSize(1);
        assertThat(first.getTasks().get(0).getTaskId()).isEqualTo(1002L);
        assertThat(task.getConsumedAt()).isNotNull();
        assertThat(second.getTasks()).isEmpty();
    }

    @Test
    void pollStoresReportedTaskResults() {
        WorkerTask task = new WorkerTask();
        task.setId(1001L);
        when(statusRepository.findById("default")).thenReturn(Optional.empty());
        when(taskRepository.findById(1001L)).thenReturn(Optional.of(task));
        when(taskRepository.findByConsumedAtIsNullOrderByCreatedAtAsc(any(Pageable.class))).thenReturn(List.of());
        when(statusRepository.save(any(WorkerStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerTaskResultDTO result = new WorkerTaskResultDTO();
        result.setTaskId(1001L);
        result.setSuccess(true);
        result.setMessage("WOL packet sent");
        result.setFinishedAt(LocalDateTime.of(2026, 5, 16, 10, 1, 5));

        service.poll(pollRequest(List.of(result)));

        assertThat(task.getResultSuccess()).isTrue();
        assertThat(task.getResultMessage()).isEqualTo("WOL packet sent");
        assertThat(task.getResultReportedAt()).isEqualTo(LocalDateTime.of(2026, 5, 16, 10, 1, 5));
        verify(taskRepository).save(task);
    }

    @Test
    void pollUpdatesLatestWorkerMetrics() {
        when(statusRepository.findById("default")).thenReturn(Optional.empty());
        when(taskRepository.findByConsumedAtIsNullOrderByCreatedAtAsc(any(Pageable.class))).thenReturn(List.of());
        when(statusRepository.save(any(WorkerStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.poll(pollRequest(List.of()));

        org.mockito.ArgumentCaptor<WorkerStatus> captor = org.mockito.ArgumentCaptor.forClass(WorkerStatus.class);
        verify(statusRepository).save(captor.capture());
        WorkerStatus saved = captor.getValue();
        assertThat(saved.getWorkerId()).isEqualTo("default");
        assertThat(saved.getHostname()).isEqualTo("proxy-host");
        assertThat(saved.getHeapUsedBytes()).isEqualTo(134217728L);
        assertThat(saved.getRunningInboundCount()).isEqualTo(3);
    }

    @Test
    void getLatestStatusReturnsNullWhenNoWorkerHasReported() {
        when(statusRepository.findById("default")).thenReturn(Optional.empty());

        assertThat(service.getLatestStatus()).isNull();
    }

    @Test
    void getLatestStatusReturnsOnlineSnapshotForRecentHeartbeat() {
        WorkerStatus status = new WorkerStatus();
        status.setWorkerId("default");
        status.setHostname("proxy-host");
        status.setLastSeenAt(LocalDateTime.now());
        status.setHeapUsedBytes(100L);
        status.setHeapMaxBytes(200L);
        status.setRunningInboundCount(3);
        status.setActiveConnectionCount(12);
        when(statusRepository.findById("default")).thenReturn(Optional.of(status));

        WorkerStatusDTO dto = service.getLatestStatus();

        assertThat(dto).isNotNull();
        assertThat(dto.getHostname()).isEqualTo("proxy-host");
        assertThat(dto.getHeapUsedBytes()).isEqualTo(100L);
        assertThat(dto.getOnline()).isTrue();
    }

    private WorkerPollRequest pollRequest(List<WorkerTaskResultDTO> results) {
        WorkerMetricsDTO metrics = new WorkerMetricsDTO();
        metrics.setUptimeSeconds(3600L);
        metrics.setHeapUsedBytes(134217728L);
        metrics.setHeapMaxBytes(536870912L);
        metrics.setRunningInboundCount(3);
        metrics.setActiveConnectionCount(12);

        WorkerPollRequest request = new WorkerPollRequest();
        request.setWorkerId("default");
        request.setHostname("proxy-host");
        request.setStartedAt(LocalDateTime.of(2026, 5, 16, 10, 0, 0));
        request.setLastConfigHash("abc123");
        request.setMetrics(metrics);
        request.setTaskResults(results);
        return request;
    }
}
