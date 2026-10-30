package org.congcong.common.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class TelemetryBatchV1 {
    private int protocolVersion = 1;
    private String batchId;
    private List<SessionLogV1> sessions = List.of();
    private List<DnsQueryLogV1> dnsQueries = List.of();
    /**
     * @deprecated Diagnostic events were removed; older workers still send them. Received and discarded,
     * never persisted and not counted by {@link #recordCount()}.
     */
    @Deprecated
    private List<JsonNode> events = List.of();
    private List<CredentialAuthLogV1> credentialAuth = List.of();

    public int recordCount() {
        return sessions.size() + dnsQueries.size() + credentialAuth.size();
    }
}
