package org.congcong.controlmanager.service;

import org.congcong.common.dto.ProxyWorkerDTO;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUp() {
        service = new ProxyWorkerService(proxyWorkerRepository, workerStatusRepository,
                workerTaskRepository, inboundConfigRepository);
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
