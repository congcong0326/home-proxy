package org.congcong.controlmanager.telemetry;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import org.congcong.common.telemetry.*;
import org.congcong.common.util.geo.GeoIpDatabaseLocator;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class CredentialGeoAnalysis {
    private final Cache<String, CredentialAuthAnalysisV1> cache = CacheBuilder.newBuilder()
            .maximumSize(2000).expireAfterWrite(1, TimeUnit.HOURS).build();
    private DatabaseReader reader;
    private String databaseVersion;

    public CredentialGeoAnalysis() {
        try {
            var path = GeoIpDatabaseLocator.resolveDefault();
            if (path.isPresent()) {
                reader = new DatabaseReader.Builder(path.get().toFile()).build();
                databaseVersion = reader.getMetadata().getBuildDate().toInstant().toString();
            }
        } catch (Exception ignored) { reader = null; }
    }

    public CredentialAuthAnalysisV1 analyze(CredentialAuthLogV1 record) {
        String ip = record.getClientIp();
        CredentialAuthAnalysisV1 result = ip == null ? null : cache.getIfPresent(ip);
        if (result == null) {
            result = lookup(ip);
            if (ip != null) cache.put(ip, result);
        }
        // Analysis has its own identity/version and never changes the raw authentication fact.
        CredentialAuthAnalysisV1 copy = new CredentialAuthAnalysisV1();
        copy.setAuthAttemptId(record.getAuthAttemptId());
        copy.setSourceNetworkScope(result.getSourceNetworkScope());
        copy.setSourceCountryCode(result.getSourceCountryCode());
        copy.setSourceCountry(result.getSourceCountry());
        copy.setSourceRegion(result.getSourceRegion());
        copy.setSourceCity(result.getSourceCity());
        copy.setGeoLookupStatus(result.getGeoLookupStatus());
        copy.setGeoDatabaseVersion(databaseVersion);
        copy.setRiskRuleVersion("contract-v1");
        copy.setBaselineStatus("BASELINE_INSUFFICIENT");
        copy.setRiskTags("RESOLVED".equals(result.getGeoLookupStatus()) || "PRIVATE".equals(result.getGeoLookupStatus())
                ? List.of() : List.of("LOCATION_UNKNOWN"));
        return copy;
    }

    private CredentialAuthAnalysisV1 lookup(String ip) {
        CredentialAuthAnalysisV1 result = new CredentialAuthAnalysisV1();
        result.setSourceNetworkScope(TrafficClassifierV1.scope(ip));
        result.setGeoLookupStatus("UNKNOWN");
        if (ip == null || !InetAddresses.isInetAddress(ip)) return result;
        var address = InetAddresses.forString(ip);
        if (result.getSourceNetworkScope() == NetworkScope.INTERNAL || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            result.setGeoLookupStatus("PRIVATE");
            return result;
        }
        if (result.getSourceNetworkScope() != NetworkScope.EXTERNAL) return result;
        if (reader == null) { result.setGeoLookupStatus("DB_UNAVAILABLE"); return result; }
        try {
            var response = reader.city(address);
            result.setSourceCountryCode(response.getCountry().getIsoCode());
            result.setSourceCountry(response.getCountry().getName());
            result.setSourceRegion(response.getMostSpecificSubdivision().getName());
            result.setSourceCity(response.getCity().getName());
            result.setGeoLookupStatus("RESOLVED");
        } catch (AddressNotFoundException e) { result.setGeoLookupStatus("NOT_FOUND"); }
        catch (Exception e) { result.setGeoLookupStatus("ERROR"); }
        return result;
    }

    @PreDestroy
    void close() throws Exception { if (reader != null) reader.close(); }
}
