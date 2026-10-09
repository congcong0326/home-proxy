package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@Data
public class TelemetryRecordV1 {
    private String recordId;
    private String workerId;
    private String workerInstanceId;
    private String connectionId;
    private String inboundName;
    private String username;
    private String clientIp;
    private String clientIpSource;
    private String transportPeerIp;
    private String configVersion;
    private String ruleSetVersion;
    private String inboundProtocol;
    private Integer schemaVersion = 1;
    private Integer clientPort;
    private Integer transportPeerPort;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long inboundId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private Instant occurredAt;
    private IdentitySource identitySource;
}
