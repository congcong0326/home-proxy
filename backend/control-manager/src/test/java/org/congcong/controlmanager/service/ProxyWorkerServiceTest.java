package org.congcong.controlmanager.service;

import org.congcong.common.dto.ProxyWorkerDTO;
import org.congcong.common.dto.WorkerTokenResponse;
import org.congcong.common.enums.LogLevel;
import org.congcong.controlmanager.dto.WorkerCreateRequest;
import org.congcong.controlmanager.dto.WorkerUpdateRequest;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyWorkerServiceTest {

    @Mock
    private ProxyWorkerRepository proxyWorkerRepository;
    @Mock
    private WorkerStatusRepository workerStatusRepository;
    @Mock
    private WorkerTaskRepository workerTaskRepository;
    @Mock
    private InboundConfigRepository inboundConfigRepository;

    private ProxyWorkerService service;
    private ConcurrentMapCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager("aggregateConfig");
        service = new ProxyWorkerService(proxyWorkerRepository, workerStatusRepository,
                workerTaskRepository, inboundConfigRepository, cacheManager);
    }

    @Test
    void listLoadsAllWorkerStatusesInOneQuery() {
        ProxyWorker workerB = worker("worker-b", "节点 B");
        ProxyWorker workerA = worker("worker-a", "节点 A");
        WorkerStatus statusA = status("worker-a", 3);
        WorkerStatus statusB = status("worker-b", 5);
        when(proxyWorkerRepository.findAll()).thenReturn(List.of(workerB, workerA));
        when(workerStatusRepository.findAllById(List.of("worker-a", "worker-b")))
                .thenReturn(List.of(statusA, statusB));

        List<ProxyWorkerDTO> result = service.list();

        assertEquals(List.of("worker-a", "worker-b"), result.stream().map(ProxyWorkerDTO::getWorkerId).toList());
        assertEquals(3, result.get(0).getStatus().getActiveConnectionCount());
        assertEquals(5, result.get(1).getStatus().getActiveConnectionCount());
        assertTrue(result.get(0).getStatus().getOnline());
        verify(workerStatusRepository).findAllById(List.of("worker-a", "worker-b"));
        verify(workerStatusRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void workerListReadsEstablishedConnectionSnapshotWithInstant() {
        WorkerStatus snapshot = status("edge-01", 2);
        snapshot.setMetricsJson("{\"establishedConnectionsV1\":{\"workerInstanceId\":\"instance\","
                + "\"sampledAt\":\"2026-10-09T10:00:00Z\",\"byCategory\":{\"EXTERNAL\":2},"
                + "\"groups\":[],\"groupsTruncated\":false}}");
        when(proxyWorkerRepository.findAll()).thenReturn(List.of(worker("edge-01", "Edge")));
        when(workerStatusRepository.findAllById(List.of("edge-01"))).thenReturn(List.of(snapshot));
        var metric = service.list().get(0).getStatus().getMetrics().getEstablishedConnectionsV1();
        assertEquals(java.time.Instant.parse("2026-10-09T10:00:00Z"), metric.sampledAt());
        assertEquals(2L, metric.byCategory().get(org.congcong.common.telemetry.TrafficCategory.EXTERNAL));
    }

    @Test
    void createsControllerInitiatedManagementConnectionWithoutLegacyToken() {
        WorkerCreateRequest request = new WorkerCreateRequest();
        request.setWorkerId("edge-01");
        request.setDisplayName("Edge 01");
        request.setManagementUrl("wss://edge.example.test:18444/management/ws");
        request.setManagementToken("hpw_example-management-token");
        when(proxyWorkerRepository.existsById("edge-01")).thenReturn(false);
        when(proxyWorkerRepository.save(any(ProxyWorker.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workerStatusRepository.findById("edge-01")).thenReturn(java.util.Optional.empty());

        WorkerTokenResponse response = service.create(request);

        assertNull(response.getToken());
        assertEquals("wss://edge.example.test:18444/management/ws", response.getWorker().getManagementUrl());
        assertTrue(response.getWorker().getManagementTokenPresent());
        assertEquals(LogLevel.INFO, response.getWorker().getLogLevel());
    }

    @Test
    void updatesLogLevelAndEvictsOnlyThatWorkersConfigAfterCommit() {
        ProxyWorker worker = worker("edge-01", "Edge 01");
        when(proxyWorkerRepository.findById("edge-01")).thenReturn(java.util.Optional.of(worker));
        when(proxyWorkerRepository.save(any(ProxyWorker.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var cache = cacheManager.getCache("aggregateConfig");
        cache.put("edge-01", "old-config");
        cache.put("other-worker", "other-config");
        WorkerUpdateRequest request = new WorkerUpdateRequest();
        request.setDisplayName("Edge 01");
        request.setLogLevel(LogLevel.WARN);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(LogLevel.WARN, service.update("edge-01", request).getLogLevel());
            assertEquals(LogLevel.WARN, worker.getLogLevel());
            assertEquals("old-config", cache.get("edge-01").get());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertNull(cache.get("edge-01"));
            assertEquals("other-config", cache.get("other-worker").get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateWithoutLogLevelRetainsExistingLevel() {
        ProxyWorker worker = worker("edge-01", "Edge 01");
        worker.setLogLevel(LogLevel.ERROR);
        when(proxyWorkerRepository.findById("edge-01")).thenReturn(java.util.Optional.of(worker));
        when(proxyWorkerRepository.save(any(ProxyWorker.class))).thenAnswer(invocation -> invocation.getArgument(0));
        WorkerUpdateRequest request = new WorkerUpdateRequest();
        request.setDisplayName("Renamed edge");

        assertEquals(LogLevel.ERROR, service.update("edge-01", request).getLogLevel());
    }

    private ProxyWorker worker(String workerId, String displayName) {
        ProxyWorker worker = new ProxyWorker();
        worker.setWorkerId(workerId);
        worker.setDisplayName(displayName);
        worker.setEnabled(true);
        return worker;
    }

    private WorkerStatus status(String workerId, int activeConnections) {
        WorkerStatus status = new WorkerStatus();
        status.setWorkerId(workerId);
        status.setLastSeenAt(LocalDateTime.now());
        status.setActiveConnectionCount(activeConnections);
        return status;
    }
}
