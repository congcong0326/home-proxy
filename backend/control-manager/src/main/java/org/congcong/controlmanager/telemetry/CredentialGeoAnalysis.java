package org.congcong.controlmanager.telemetry;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import org.congcong.common.telemetry.*;
import org.congcong.common.util.geo.GeoIpDatabaseLocator;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
@Slf4j
public class CredentialGeoAnalysis {
    private final Cache<String, CredentialAuthAnalysisV1> cache = CacheBuilder.newBuilder()
            .maximumSize(2000).expireAfterWrite(1, TimeUnit.HOURS).build();
    private DatabaseReader reader;
    private String databaseVersion;
    private final Supplier<DatabaseReader> loader;
    private final LongSupplier nanoTime;
    private long nextLoadAttempt;

    public CredentialGeoAnalysis() {
        this(CredentialGeoAnalysis::loadDatabase, System::nanoTime);
    }

    CredentialGeoAnalysis(Supplier<DatabaseReader> loader, LongSupplier nanoTime) {
        this.loader = loader;
        this.nanoTime = nanoTime;
        reload();
    }

    private static DatabaseReader loadDatabase() {
        try {
            var path = GeoIpDatabaseLocator.resolveDefault();
            if (path.isPresent()) {
                DatabaseReader loaded = new DatabaseReader.Builder(path.get().toFile()).build();
                log.info("Authentication GeoIP database loaded from {}", path.get());
                return loaded;
            }
            log.warn("Authentication GeoIP database missing; configure GEOIP_MMDB_PATH or data/geoip/GeoLite2-City.mmdb");
        } catch (Exception error) { log.warn("Authentication GeoIP database load failed", error); }
        return null;
    }

    private void reload() {
        reader = loader.get();
        nextLoadAttempt = nanoTime.getAsLong() + TimeUnit.MINUTES.toNanos(1);
        if (reader != null) {
            databaseVersion = reader.getMetadata().getBuildDate().toInstant().toString();
            cache.invalidateAll();
        }
    }

    public synchronized CredentialAuthAnalysisV1 analyze(CredentialAuthLogV1 record) {
        if (reader == null && nanoTime.getAsLong() - nextLoadAttempt >= 0) reload();
        String ip = record.getClientIp();
        CredentialAuthAnalysisV1 result = ip == null ? null : cache.getIfPresent(ip);
        if (result == null) {
            result = lookup(ip);
            if (ip != null && !List.of("DB_UNAVAILABLE", "ERROR").contains(result.getGeoLookupStatus())) cache.put(ip, result);
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
            result.setSourceCountry(response.getCountry().getNames().getOrDefault("zh-CN", response.getCountry().getName()));
            var subdivision = response.getMostSpecificSubdivision();
            var city = response.getCity();
            result.setSourceRegion(subdivision == null ? null : subdivision.getNames().getOrDefault("zh-CN", subdivision.getName()));
            result.setSourceCity(city == null ? null : city.getNames().getOrDefault("zh-CN", city.getName()));
            result.setGeoLookupStatus("RESOLVED");
        } catch (AddressNotFoundException e) { result.setGeoLookupStatus("NOT_FOUND"); }
        catch (Exception e) { result.setGeoLookupStatus("ERROR"); }
        return result;
    }

    @PreDestroy
    synchronized void close() throws Exception { if (reader != null) reader.close(); }
}
