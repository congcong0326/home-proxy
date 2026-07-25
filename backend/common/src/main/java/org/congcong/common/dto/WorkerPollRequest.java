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
    /** Deprecated compatibility field. The control plane uses the authenticated worker id. */
    private String lastConfigHash;
    private String fetchedConfigHash;
    private String appliedConfigHash;
    private Boolean applySuccess;
    private String applyError;
    private LocalDateTime appliedAt;
    private WorkerMetricsDTO metrics;
    private List<WorkerTaskResultDTO> taskResults;
}
