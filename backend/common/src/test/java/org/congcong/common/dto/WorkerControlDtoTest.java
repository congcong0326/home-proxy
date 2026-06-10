package org.congcong.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerControlDtoTest {

    @Test
    void workerPollRequestRoundTripsMetricsAndTaskResults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        WorkerMetricsDTO metrics = new WorkerMetricsDTO();
        metrics.setUptimeSeconds(3600L);
        metrics.setHeapUsedBytes(134217728L);
        metrics.setHeapMaxBytes(536870912L);
        metrics.setRunningInboundCount(3);
        metrics.setActiveConnectionCount(12);

        WorkerTaskResultDTO result = new WorkerTaskResultDTO();
        result.setTaskId(1001L);
        result.setSuccess(true);
        result.setMessage("WOL packet sent");

        WorkerPollRequest request = new WorkerPollRequest();
        request.setWorkerId("default");
        request.setHostname("proxy-host");
        request.setLastConfigHash("abc123");
        request.setMetrics(metrics);
        request.setTaskResults(List.of(result));

        String json = mapper.writeValueAsString(request);
        WorkerPollRequest decoded = mapper.readValue(json, WorkerPollRequest.class);

        assertEquals("default", decoded.getWorkerId());
        assertEquals(134217728L, decoded.getMetrics().getHeapUsedBytes());
        assertEquals(1001L, decoded.getTaskResults().get(0).getTaskId());
    }
}
