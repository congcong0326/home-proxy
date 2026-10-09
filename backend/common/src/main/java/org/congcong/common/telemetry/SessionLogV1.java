package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties({"rateLimitPolicyId", "rateLimitWaitUs"})
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionLogV1 extends TelemetryRecordV1 {
    private Instant startedAt;
    private Instant connectedAt;
    private Instant endedAt;
    private Instant firstUploadAt;
    private Instant firstDownloadAt;
    private Instant lastActivityAt;
    private ConnectOutcome connectOutcome;
    private SessionOutcome sessionOutcome;
    private String failureStage;
    private String errorCode;
    private String errorMessage;
    private String closeInitiator;
    private String closeReason;
    private String originalDstIp;
    private String logicalTargetHost;
    private String targetSource;
    private String effectiveTargetHost;
    private String selectedTargetIp;
    private String outboundPeerIp;
    private String outboundMode;
    private String outboundProtocol;
    private String outboundNodeId;
    private String routeName;
    private String routeAction;
    private String routeSelection;
    private String matchedRuleId;
    private String matchedRuleSet;
    private String targetAddressClass;
    private String trafficCategoryBasis;
    private String channelReadyBasis;
    private String targetConfirmationLevel;
    private String proxyHandshakeOutcome;
    private Integer originalDstPort;
    private Integer logicalTargetPort;
    private Integer effectiveTargetPort;
    private Integer outboundPeerPort;
    private Integer trafficClassificationVersion;
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
    private Long routeSelectionDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long dnsDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long outboundTcpDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long outboundTlsDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long proxyHandshakeDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long uploadBackpressureDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long downloadBackpressureDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long routeId;
    private Boolean destinationOverrideApplied;
    private NetworkScope targetNetworkScope;
    private NetworkScope clientNetworkScope;
    private NetworkScope outboundPeerNetworkScope;
    private TrafficCategory trafficCategory;
}
