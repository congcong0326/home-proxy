package org.congcong.controlmanager.telemetry;

import lombok.Data;
import java.time.Instant;

/** Bounded audit scope. Filters are applied in ClickHouse before pagination. */
@Data
public class TelemetryQueryRequest {
    private String workerId;
    private Instant from;
    private Instant to;
    private int limit = 50;
    private int offset;
    private String connectionId;
    private String username;
    private String clientIp;
    private String target;
    private String protocol;
    private String outcome;
    private String connectOutcome;
    private String route;
    private String trafficCategory;
    private String qtype;
    private String eventType;
    private String authMethod;
}
