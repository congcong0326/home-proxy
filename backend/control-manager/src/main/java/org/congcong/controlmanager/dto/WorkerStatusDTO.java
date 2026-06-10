package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerStatusDTO {
    private String workerId;
    private String hostname;
    private LocalDateTime startedAt;
    private LocalDateTime lastSeenAt;
    private String lastConfigHash;
    private Long uptimeSeconds;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Integer runningInboundCount;
    private Integer activeConnectionCount;
    private Boolean online;
}
