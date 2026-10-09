package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProxyEventLogV1 extends TelemetryRecordV1 {
    private String eventId;
    private String queryId;
    private String eventType;
    private String stage;
    private String outcome;
    private String errorCode;
    private String errorMessage;
    private String recoverySource;
    private String mappingOutcome;
    private String fallbackOutcome;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long eventSequence;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long durationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long repeatCount;
    private Instant firstOccurredAt;
    private Instant lastOccurredAt;
}
