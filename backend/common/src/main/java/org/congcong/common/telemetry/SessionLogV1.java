package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionLogV1 extends TelemetryRecordV1 {
    private Instant startedAt;
    private Instant connectedAt;
    private Instant endedAt;
    private Instant lastActivityAt;
    private ConnectOutcome connectOutcome;
    private SessionOutcome sessionOutcome;
    private String failureStage;
    private String errorCode;
    private String originalDstIp;
    private String logicalTargetHost;
    private String targetSource;
    private String effectiveTargetHost;
    private String selectedTargetIp;
    private String outboundPeerIp;
    private String outboundProtocol;
    private String outboundNodeId;
    private String routeName;
    private String routeAction;
    private String matchedRuleId;
    private String matchedRuleSet;
    private Integer originalDstPort;
    private Integer logicalTargetPort;
    private Integer effectiveTargetPort;
    private Integer outboundPeerPort;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long uploadBytes;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long downloadBytes;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long sessionDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long channelSetupDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long clientHandshakeDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long targetRecoveryDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long dnsDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long outboundTcpDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long outboundTlsDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long proxyHandshakeDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long routeId;
    private Boolean destinationOverrideApplied;
    private TrafficCategory trafficCategory;
}
