package org.congcong.common.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@EqualsAndHashCode(callSuper = true)
public class CredentialAuthLogV1 extends TelemetryRecordV1 {
    private String authAttemptId;
    private String authMethod;
    private String authEventKind;
    private String attemptedUsername;
    private String reasonCode;
    private AuthOutcome authOutcome;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long authDurationUs;
}
