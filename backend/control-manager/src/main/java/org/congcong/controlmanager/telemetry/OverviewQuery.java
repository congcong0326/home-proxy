package org.congcong.controlmanager.telemetry;

import lombok.Data;
import java.time.Instant;

@Data
public class OverviewQuery {
    private String workerId;
    private Instant from;
    private Instant to;
    private String timeZone = "Asia/Shanghai";
    private String clientIp;
    private String username;
    private String trafficCategory = "EXTERNAL";
    private String trafficResult = "ALL";
    private String connectionResult = "ALL";
    private String dnsResult = "ALL";
    private String authResult = "ALL";
    private String dnsOrigin = "CLIENT";
}
