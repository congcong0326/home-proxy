package org.congcong.controlmanager.telemetry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "observability")
public class TelemetryProperties {
    private boolean ingestEnabled = true;
    private boolean queryEnabled = true;
    private int maxBatchRecords = 100;
    private int maxBatchBytes = 262144;
    private int flushBatchSize = 1000;
    private long flushMaxDelayMs = 120000;
    private int bufferCapacity = 20000;
    private long bufferMaxBytes = 67108864;
    /** Null disables TTL. Stage A never changes retention on legacy tables. */
    private Integer sessionRetentionDays;
    private Integer dnsRetentionDays;
    private Integer eventRetentionDays;
    private Integer authRetentionDays;
}
