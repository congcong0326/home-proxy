package org.congcong.controlmanager.telemetry;

import lombok.Data;
import java.time.Instant;

@Data
public class DistributionQuery {
    private String workerId;
    private Instant from;
    private Instant to;
    private String dimension = "users";
    private String metric = "connections";
    private int limit = 10;
    private String trafficCategory = "EXTERNAL";
    private String connectOutcome = "ALL";
    private String username;
    private String clientIp;
}
