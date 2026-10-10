package org.congcong.controlmanager.telemetry;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import org.congcong.common.telemetry.CredentialAuthLogV1;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CredentialGeoAnalysisTest {
    @Test
    void missingDatabaseRecoversAfterFileBecomesAvailableWithoutRestartOrStaleCache() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicReference<DatabaseReader> available = new AtomicReference<>();
        var geo = new CredentialGeoAnalysis(available::get, now::get);
        var record = record("8.8.8.8");
        assertEquals("DB_UNAVAILABLE", geo.analyze(record).getGeoLookupStatus());

        var reader = mock(DatabaseReader.class);
        var metadata = new com.maxmind.db.Metadata(2, 0,
                java.math.BigInteger.valueOf(Instant.parse("2026-10-07T00:00:00Z").getEpochSecond()),
                "GeoLite2-City", java.util.List.of("en", "zh-CN"), Map.of(), 6, 1, 28);
        when(reader.getMetadata()).thenReturn(metadata);
        var country = new com.maxmind.geoip2.record.Country(null, null, null, false, "US", Map.of("zh-CN", "美国", "en", "United States"));
        var subdivision = new com.maxmind.geoip2.record.Subdivision(null, null, null, "CA", Map.of("zh-CN", "加利福尼亚州"));
        var city = new com.maxmind.geoip2.record.City(null, null, null, Map.of("zh-CN", "山景城"));
        var response = new CityResponse(city, null, country, null, null, null, null, null, new java.util.ArrayList<>(java.util.List.of(subdivision)), null);
        when(reader.city(any(InetAddress.class))).thenReturn(response);
        available.set(reader);
        now.set(60_000_000_000L);
        var analysis = geo.analyze(record);
        assertEquals("RESOLVED", analysis.getGeoLookupStatus());
        assertEquals("美国", analysis.getSourceCountry());
        assertEquals("加利福尼亚州", analysis.getSourceRegion());
        assertEquals("山景城", analysis.getSourceCity());
        assertEquals("2026-10-07T00:00:00Z", analysis.getGeoDatabaseVersion());
        assertTrue(analysis.getRiskTags().isEmpty());
        record.setAuthAttemptId("another-attempt");
        assertEquals("another-attempt", geo.analyze(record).getAuthAttemptId());
        verify(reader, times(1)).city(any(InetAddress.class));
        geo.close();
        verify(reader).close();
    }

    @Test
    void privateAndInvalidAddressesDoNotBecomeFakePublicLocations() {
        var geo = new CredentialGeoAnalysis(() -> null, () -> 0);
        assertEquals("PRIVATE", geo.analyze(record("192.168.1.2")).getGeoLookupStatus());
        assertTrue(geo.analyze(record("192.168.1.2")).getRiskTags().isEmpty());
        assertNull(geo.analyze(record("192.168.1.2")).getSourceCountry());
        assertEquals("UNKNOWN", geo.analyze(record("example.com")).getGeoLookupStatus());
    }

    private CredentialAuthLogV1 record(String ip) {
        var record = new CredentialAuthLogV1();
        record.setAuthAttemptId("attempt");
        record.setClientIp(ip);
        return record;
    }
}
