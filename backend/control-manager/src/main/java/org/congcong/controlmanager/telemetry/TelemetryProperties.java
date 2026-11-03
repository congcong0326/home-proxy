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
    /** Global limits across all fact tables. Calendar days are UTC and include today. */
    private int retentionDays = 90;
    private long maxRecords = 100_000_000L;
    /** Optional shorter per-table retention; null inherits the global limit. */
    private Integer sessionRetentionDays;
    private Integer dnsRetentionDays;
    private Integer authRetentionDays;
}
