package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@Data
@EqualsAndHashCode(callSuper = true)
public class DnsQueryLogV1 extends TelemetryRecordV1 {
    private String queryId;
    private String parentConnectionId;
    private String queryOrigin;
    private String resolvePurpose;
    private String qname;
    private String qtype;
    private String qclass;
    private String transport;
    private String outcome;
    private String answerMode;
    private String emptyAnswerReason;
    private String cacheStatus;
    private String sharedLookupId;
    private String resolverEndpoint;
    private String errorCode;
    private String errorMessage;
    private String mappingId;
    private String mappingRegistryId;
    private Integer rcode;
    private Integer ttlSeconds;
    private Integer upstreamAttemptCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long dnsRouteId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long durationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long upstreamDurationUs;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long requestBytes;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long responseBytes;
    private Boolean responseSent;
    private List<String> answerIps;
}
