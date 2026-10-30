package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.controlmanager.dto.WorkerStatusDTO;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.entity.WorkerTask;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    void managementHeartbeatUpdatesLatestWorkerMetrics() {
        when(statusRepository.findById("default")).thenReturn(Optional.empty());
        when(statusRepository.save(any(WorkerStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordManagementPoll("default", pollRequest());

        org.mockito.ArgumentCaptor<WorkerStatus> captor = org.mockito.ArgumentCaptor.forClass(WorkerStatus.class);
        verify(statusRepository).save(captor.capture());
        WorkerStatus saved = captor.getValue();
        assertThat(saved.getWorkerId()).isEqualTo("default");
        assertThat(saved.getHostname()).isEqualTo("proxy-host");
        assertThat(saved.getCapabilitiesJson()).isEqualTo("[]");
        assertThat(saved.getHeapUsedBytes()).isEqualTo(134217728L);
        assertThat(saved.getRunningInboundCount()).isEqualTo(3);
    }

    @Test
    void createTaskPersistsSerializedPayload() {
        when(taskRepository.save(any(WorkerTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerTask task = service.createTask("edge-a", "WOL_WAKE", java.util.Map.of("port", 9));

        assertThat(task.getWorkerId()).isEqualTo("edge-a");
        assertThat(task.getTaskType()).isEqualTo("WOL_WAKE");
        assertThat(task.getPayloadJson()).isEqualTo("{\"port\":9}");
    }

    @Test
    void managementPollCreatesStatusWithEmptyCapabilities() {
        when(statusRepository.findById("happy-bounty-1")).thenReturn(Optional.empty());
        when(statusRepository.save(any(WorkerStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordManagementPoll("happy-bounty-1", "hash-123");

        org.mockito.ArgumentCaptor<WorkerStatus> captor = org.mockito.ArgumentCaptor.forClass(WorkerStatus.class);
        verify(statusRepository).save(captor.capture());
        WorkerStatus saved = captor.getValue();
        assertThat(saved.getWorkerId()).isEqualTo("happy-bounty-1");
        assertThat(saved.getFetchedConfigHash()).isEqualTo("hash-123");
        assertThat(saved.getAppliedConfigHash()).isEqualTo("hash-123");
        assertThat(saved.getCapabilitiesJson()).isEqualTo("[]");
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

    private WorkerPollRequest pollRequest() {
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
        return request;
    }
}
