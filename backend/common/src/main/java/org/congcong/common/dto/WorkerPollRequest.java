package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerPollRequest {
    private String workerId;
    private String hostname;
    private LocalDateTime startedAt;
    private String lastConfigHash;
    private WorkerMetricsDTO metrics;
    private List<WorkerTaskResultDTO> taskResults;
}
