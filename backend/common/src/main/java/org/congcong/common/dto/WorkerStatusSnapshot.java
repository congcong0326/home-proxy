package org.congcong.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkerStatusSnapshot {
    private String workerId;
    private String hostname;
    private LocalDateTime startedAt;
    private LocalDateTime lastSeenAt;
    private String fetchedConfigHash;
    private String appliedConfigHash;
    private Boolean applySuccess;
    private String applyError;
    private LocalDateTime appliedAt;
    private Long uptimeSeconds;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Integer runningInboundCount;
    private Integer activeConnectionCount;
    private Boolean online;
}
