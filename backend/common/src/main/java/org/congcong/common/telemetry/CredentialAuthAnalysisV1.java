package org.congcong.common.telemetry;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@Data
public class CredentialAuthAnalysisV1 {
    private String authAttemptId;
    private String sourceCountryCode;
    private String sourceCountry;
    private String sourceRegion;
    private String sourceCity;
    private String geoLookupStatus;
    private String geoDatabaseVersion;
    private String riskRuleVersion;
    private String baselineStatus;
    private String comparisonBasis;
    private NetworkScope sourceNetworkScope;
    private List<String> riskTags;
}
