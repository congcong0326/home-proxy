package org.congcong.proxyworker.service;

import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.common.dto.WorkerPollResponse;
import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerControlServiceTest {

    @Test
    void pollExecutesReturnedTaskAndReportsResultOnNextPoll() {
        RecordingTransport transport = new RecordingTransport();
        CapturingExecutor executor = new CapturingExecutor();
        WorkerMetricsCollector metricsCollector = new WorkerMetricsCollector(
                LocalDateTime.of(2026, 5, 16, 10, 0, 0),
                () -> 3,
                () -> "abc123",
                "proxy-host"
        );
        WorkerControlService service = new WorkerControlService(
                "default",
                2000L,
                transport,
                metricsCollector,
                executor
        );

        service.pollOnce();
        service.pollOnce();

        assertEquals(2, transport.requests.size());
        assertEquals("default", transport.requests.get(0).getWorkerId());
        assertEquals(3, transport.requests.get(0).getMetrics().getRunningInboundCount());
        assertEquals(1, executor.tasks.size());
        assertEquals(1002L, executor.tasks.get(0).getTaskId());
        assertEquals(1, transport.requests.get(1).getTaskResults().size());
        assertTrue(transport.requests.get(1).getTaskResults().get(0).getSuccess());
        assertEquals(1002L, transport.requests.get(1).getTaskResults().get(0).getTaskId());
    }

    private static class RecordingTransport implements WorkerControlService.PollTransport {
        private final List<WorkerPollRequest> requests = new ArrayList<>();

        @Override
        public WorkerPollResponse poll(WorkerPollRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return new WorkerPollResponse(
                        LocalDateTime.of(2026, 5, 16, 10, 1, 0),
                        2000L,
                        List.of(new WorkerTaskDTO(1002L, "WOL_WAKE", Map.of(
                                "deviceName", "NAS",
                                "macAddress", "AA:BB:CC:DD:EE:FF",
                                "broadcastIp", "192.168.1.255",
                                "port", 9
                        )))
                );
            }
            return new WorkerPollResponse(LocalDateTime.now(), 2000L, List.of());
        }
    }

    private static class CapturingExecutor implements WorkerTaskExecutor {
        private final List<WorkerTaskDTO> tasks = new ArrayList<>();

        @Override
        public WorkerTaskResultDTO execute(WorkerTaskDTO task) {
            tasks.add(task);
            WorkerTaskResultDTO result = new WorkerTaskResultDTO();
            result.setTaskId(task.getTaskId());
            result.setSuccess(true);
            result.setMessage("executed");
            result.setFinishedAt(LocalDateTime.of(2026, 5, 16, 10, 1, 1));
            return result;
        }
    }
}
