package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerMetricsDTO {
    private Long uptimeSeconds;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Integer runningInboundCount;
    private Integer activeConnectionCount;
}
