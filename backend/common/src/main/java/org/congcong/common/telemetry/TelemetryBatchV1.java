package org.congcong.common.telemetry;

import lombok.Data;
import java.util.List;

@Data
public class TelemetryBatchV1 {
    private int protocolVersion = 1;
    private String batchId;
    private List<SessionLogV1> sessions = List.of();
    private List<DnsQueryLogV1> dnsQueries = List.of();
    private List<ProxyEventLogV1> events = List.of();
    private List<CredentialAuthLogV1> credentialAuth = List.of();

    public int recordCount() {
        return sessions.size() + dnsQueries.size() + events.size() + credentialAuth.size();
    }
}
