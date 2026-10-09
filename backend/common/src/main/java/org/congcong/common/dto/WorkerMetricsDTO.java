package org.congcong.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@NoArgsConstructor
public class WorkerMetricsDTO {
    private Long uptimeSeconds;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Integer runningInboundCount;
    private Integer activeConnectionCount;
    private org.congcong.common.telemetry.EstablishedConnectionsV1 establishedConnectionsV1;
    private Long fakeIpAllocations;
    private Long fakeIpEvictions;
    private Long fakeIpReverseMisses;
    private Map<Long, Long> realDnsQueriesByRoute;
    private Map<Long, Long> realDnsTimeoutsByRoute;
    private Map<Long, Long> realDnsTruncationsByRoute;
    private Map<Long, Long> realDnsCacheHitsByRoute;
    private Long rejectedPoolAddresses;
    private Long rejectedDestinationAddresses;

    public WorkerMetricsDTO(Long uptimeSeconds, Long heapUsedBytes, Long heapMaxBytes,
                            Integer runningInboundCount, Integer activeConnectionCount) {
        this.uptimeSeconds = uptimeSeconds;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.runningInboundCount = runningInboundCount;
        this.activeConnectionCount = activeConnectionCount;
    }
}
