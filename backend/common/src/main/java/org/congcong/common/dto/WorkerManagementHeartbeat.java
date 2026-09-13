package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON-friendly heartbeat payload for the controller-initiated management socket. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerManagementHeartbeat {
    private String hostname;
    private String startedAt;
    private String fetchedConfigHash;
    private String appliedConfigHash;
    private Boolean applySuccess;
    private String applyError;
    private String appliedAt;
    private WorkerMetricsDTO metrics;
}
