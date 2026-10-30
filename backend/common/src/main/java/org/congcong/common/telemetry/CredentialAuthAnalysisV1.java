package org.congcong.common.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CredentialAuthAnalysisV1 {
    private String authAttemptId;
    private String sourceCountryCode;
    private String sourceCountry;
    private String sourceRegion;
    private String sourceCity;
    private String geoLookupStatus;
    private String geoDatabaseVersion;
    private NetworkScope sourceNetworkScope;
}
