package org.congcong.controlmanager.telemetry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "observability")
public class TelemetryProperties {
    private boolean ingestEnabled;
    private boolean queryEnabled;
    private int maxBatchRecords = 100;
    private int maxBatchBytes = 262144;
    /** Null disables TTL. Stage A never changes retention on legacy tables. */
    private Integer sessionRetentionDays;
    private Integer dnsRetentionDays;
    private Integer eventRetentionDays;
    private Integer authRetentionDays;
}
