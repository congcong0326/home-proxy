package org.congcong.common.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Heartbeat snapshot of established business tunnels; independent of accepted socket counts. */
public record EstablishedConnectionsV1(String workerInstanceId, Instant sampledAt,
        Map<TrafficCategory, Long> byCategory, List<Group> groups, boolean groupsTruncated) {
    public record Group(TrafficCategory trafficCategory, String username, String clientIp,
                        Long inboundId, long count) { }
}
