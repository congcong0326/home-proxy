package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.service.UserTrafficStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every rewritten telemetry query runs against a real SQLite file with hand-computed expectations. */
class TelemetrySqliteQueryTest {
    private static final String WORKER = "home-01";
    private static final Instant T0 = Instant.parse("2026-10-09T01:00:00Z");

    private TelemetrySqliteFixture db;
    private SessionLogV1 alice, bob, aliceFailed;

    @BeforeEach
    void setUp() throws Exception {
        db = new TelemetrySqliteFixture();
        alice = session(T0, T0.plusSeconds(120), 100, 1000);
        alice.setUserId(1L);
        alice.setUsername("alice");
        alice.setClientIp("10.0.0.2");
        alice.setInboundId(7L);
        alice.setLogicalTargetHost("example.com");
        alice.setRouteId(3L);
        alice.setRouteName("direct");
        alice.setChannelSetupDurationUs(2000L);
        bob = session(T0.plusSeconds(60), T0.plusSeconds(180), 50, 500);
        bob.setUserId(2L);
        bob.setUsername("bob");
        bob.setClientIp("10.0.0.3");
        bob.setLogicalTargetHost("example.org");
        bob.setChannelSetupDurationUs(4000L);
        aliceFailed = session(T0.plusSeconds(30), T0.plusSeconds(30), 0, 0);
        aliceFailed.setConnectedAt(null);
        aliceFailed.setConnectOutcome(ConnectOutcome.FAILED);
        aliceFailed.setSessionOutcome(SessionOutcome.ERROR_END);
        aliceFailed.setUserId(1L);
        aliceFailed.setUsername("alice");
        aliceFailed.setClientIp("10.0.0.2");
        aliceFailed.setErrorCode("CONNECT_FAILED");

        var answered = dns(T0.plusSeconds(5), "ANSWERED", true);
        answered.setQtype("A");
        answered.setParentConnectionId(alice.getConnectionId());
        var error = dns(T0.plusSeconds(6), "ERROR", false);
        error.setQtype("AAAA");
        var blocked = dns(T0.plusSeconds(7), "BLOCKED", false);
        var attempt1 = failedAuth(T0.plusSeconds(61), bob.getConnectionId(), "mallory");
        attempt1.setClientIp("203.0.113.5");
        var attempt2 = failedAuth(T0.plusSeconds(62), bob.getConnectionId(), "mallory");
        attempt2.setClientIp("203.0.113.5");
        db.ingest(WORKER, alice, bob, aliceFailed, answered, error, blocked, attempt1, attempt2);
        // Another worker's facts must never leak into home-01 aggregates.
        db.ingest("edge-02", session(T0, T0.plusSeconds(90), 7, 7));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void resentFactIsStoredOnceAndLargeCountersSurvive() throws Exception {
        assertEquals(4, db.count(TelemetrySchema.SESSIONS));
        db.ingest(WORKER, alice);
        assertEquals(4, db.count(TelemetrySchema.SESSIONS));

        var big = session(T0.plusSeconds(300), T0.plusSeconds(301), 9007199254740993L, 0);
        db.ingest(WORKER, big);
        var rows = db.store.query(TelemetrySchema.SESSIONS, WORKER, T0.plusSeconds(300), T0.plusSeconds(302), 10, 0, null);
        assertEquals(1, rows.size());
        assertEquals("9007199254740993", rows.get(0).get("uploadBytes"));
        assertTrue(rows.get(0).get("receivedAt").toString().endsWith("Z"));
    }

    @Test
    void auditPageFiltersCaseInsensitivelyAndAttachesAuthAttempts() {
        var request = audit();
        request.setUsername("ALI");
        var page = db.store.page(TelemetrySchema.SESSIONS, request);
        assertEquals("2", page.total());
        assertEquals(List.of(alice.getRecordId(), aliceFailed.getRecordId()),
                page.items().stream().map(item -> item.get("recordId")).toList());

        request.setUsername("mallory");
        page = db.store.page(TelemetrySchema.SESSIONS, request);
        assertEquals("1", page.total());
        var attempts = (List<?>) page.items().get(0).get("credentialAuthAttempts");
        assertEquals(2, attempts.size());
        assertEquals("mallory", ((Map<?, ?>) attempts.get(0)).get("attemptedUsername"));
        assertEquals(T0.plusSeconds(62).toString(), ((Map<?, ?>) attempts.get(0)).get("occurredAt"));

        var detail = db.store.detail(TelemetrySchema.SESSIONS, alice.getRecordId(), audit());
        assertEquals("100", detail.get("uploadBytes"));

        var dnsRequest = audit();
        dnsRequest.setConnectionId(alice.getConnectionId());
        dnsRequest.setQtype("A");
        assertEquals("1", db.store.page(TelemetrySchema.DNS, dnsRequest).total());

        var routeRequest = audit();
        routeRequest.setRoute("3");
        assertEquals("1", db.store.page(TelemetrySchema.SESSIONS, routeRequest).total());
    }

    @Test
    void overviewMatchesHandComputedTrafficCountsAndConcurrency() {
        var workers = mock(WorkerStatusRepository.class);
        when(workers.findById(any())).thenReturn(Optional.empty());
        var service = new OverviewService(db.client, db.store, workers);
        var request = new OverviewQuery();
        request.setWorkerId(WORKER);
        request.setFrom(T0);
        request.setTo(T0.plusSeconds(3600));
        request.setTimeZone("UTC");

        var result = service.overview(request);
        assertEquals(60, result.get("bucketSeconds"));
        assertEquals("2026-10-09 01:00:30.000000", result.get("firstSessionAt"));

        var traffic = (Map<?, ?>) result.get("traffic");
        assertEquals("150", traffic.get("upload"));
        assertEquals("1500", traffic.get("download"));
        var series = (List<?>) traffic.get("series");
        assertEquals("100", ((Map<?, ?>) series.get(2)).get("upload"));
        assertEquals("500", ((Map<?, ?>) series.get(3)).get("download"));
        var sources = (List<?>) traffic.get("sources");
        assertEquals("alice", ((Map<?, ?>) sources.get(0)).get("username"));
        assertEquals("2", ((Map<?, ?>) sources.get(0)).get("connections"));
        assertEquals(List.of(Map.of("category", "EXTERNAL", "bytes", "1650")), traffic.get("categories"));

        var connections = (Map<?, ?>) result.get("connections");
        assertEquals(Map.of("SUCCESS", "2", "FAILURE", "1"), connections.get("totals"));

        var concurrency = (ConcurrencyTimeline.Result) result.get("concurrency");
        assertTrue(concurrency.available());
        assertEquals(60, concurrency.series().size());
        assertEquals(List.of("1.00", "2.00", "1.00", "0.00"),
                concurrency.series().subList(0, 4).stream().map(ConcurrencyTimeline.Point::average).toList());
        assertEquals("2", concurrency.peak());
        assertEquals("0.07", concurrency.average());

        var dnsCounts = (Map<?, ?>) result.get("dns");
        assertEquals(Map.of("SUCCESS", "1", "FAILURE", "1", "BLOCKED", "1"), dnsCounts.get("totals"));
        assertEquals("2", ((Map<?, ?>) result.get("auth")).get("total"));

        request.setConnectionResult("FAILURE");
        assertEquals("1", ((Map<?, ?>) service.overview(request).get("connections")).get("total"));
    }

    @Test
    void concurrencyClipsLongSessionsToTheWindowAndSplitsPartialLastBucket() {
        var workers = mock(WorkerStatusRepository.class);
        when(workers.findById(any())).thenReturn(Optional.empty());
        var service = new OverviewService(db.client, db.store, workers);
        var request = new OverviewQuery();
        request.setWorkerId("edge-02");
        // 90 s window, 60 s buckets: the second bucket is only 30 s long.
        request.setFrom(T0.plusSeconds(30));
        request.setTo(T0.plusSeconds(120));
        request.setTimeZone("UTC");
        var concurrency = (ConcurrencyTimeline.Result) service.overview(request).get("concurrency");
        // edge-02 session spans [T0, T0+90): clipped to [T0+30, T0+90) inside the window.
        assertEquals(List.of("1.00", "0.00"), concurrency.series().stream().map(ConcurrencyTimeline.Point::average).toList());
        assertEquals("0.67", concurrency.average());
        assertEquals("1", concurrency.peak());
    }

    @Test
    void distributionRanksGroupsWithFullDenominators() {
        var service = new DistributionService(db.client, db.store);
        var request = new DistributionQuery();
        request.setWorkerId(WORKER);
        request.setFrom(T0);
        request.setTo(T0.plusSeconds(3600));
        request.setMetric("bytes");
        request.setLimit(1);
        var result = service.aggregate(request);
        assertEquals(1, result.items().size());
        assertEquals(List.of("1", "alice"), result.items().get(0).keys());
        assertEquals("1100", result.items().get(0).value());
        assertEquals("2", result.items().get(0).counters().connections());
        assertEquals("1650", result.totalValue());
        assertEquals("550", result.otherValue());
        assertEquals("2", result.groups());
        assertEquals("3", result.totals().connections());
        assertEquals("0", result.missingConnections());

        request.setDimension("targets");
        request.setMetric("connections");
        request.setLimit(10);
        result = service.aggregate(request);
        // Equal counts tie-break by key ascending, so the unknown target ("") ranks first.
        assertEquals(List.of(List.of(""), List.of("example.com"), List.of("example.org")),
                result.items().stream().map(DistributionService.Item::keys).toList());
        assertEquals("1", result.missingConnections());
    }

    @Test
    void successfulAndFailedAuthenticationsAreCountedSeparately() throws Exception {
        var challenge = failedAuth(T0.plusSeconds(64), null, "alice");
        challenge.setAuthOutcome(AuthOutcome.CHALLENGE);
        challenge.setClientIp("203.0.113.5");
        db.ingest(WORKER, successAuth(T0.plusSeconds(63), "203.0.113.5", 1L, "alice"), challenge);
        var workers = mock(WorkerStatusRepository.class);
        when(workers.findById(any())).thenReturn(Optional.empty());
        var overview = new OverviewService(db.client, db.store, workers);
        var request = new OverviewQuery();
        request.setWorkerId(WORKER);
        request.setFrom(T0);
        request.setTo(T0.plusSeconds(3600));
        request.setTimeZone("UTC");
        var auth = (Map<?, ?>) overview.overview(request).get("auth");
        assertEquals("4", auth.get("total"));
        assertEquals(Map.of("SUCCESS", "1", "FAILURE", "2", "CHALLENGE", "1"), auth.get("totals"));
        request.setAuthResult("SUCCESS");
        assertEquals("1", ((Map<?, ?>) overview.overview(request).get("auth")).get("total"));

        var credential = new CredentialDistributionService(db.client, db.store, db.geo);
        var query = new DistributionQuery();
        query.setWorkerId(WORKER);
        query.setFrom(T0);
        query.setTo(T0.plusSeconds(3600));
        query.setDimension("auth_geo");
        query.setMetric("authentications");
        var all = credential.aggregate(query).authTotals();
        assertEquals(new CredentialDistributionService.Counts("4", "1", "2", "1"), all);
        query.setAuthOutcome("SUCCESS");
        assertEquals("1", credential.aggregate(query).totalValue());
        query.setAuthOutcome("FAILURE");
        assertEquals("2", credential.aggregate(query).totalValue());
    }

    @Test
    void inboundMonthlyAndCredentialAndUserTrafficStats() {
        var inbound = new InboundTrafficService(db.client, db.store).monthly(7, WORKER, "2026-10", "UTC");
        assertEquals("100", inbound.traffic().upload());
        assertEquals("1000", inbound.traffic().download());
        assertEquals("1100", inbound.categories().get(TrafficCategory.EXTERNAL).total());
        assertEquals("2026-10-09 01:02:00.000000", inbound.firstSessionAt());

        var credential = new CredentialDistributionService(db.client, db.store, db.geo);
        var query = new DistributionQuery();
        query.setWorkerId(WORKER);
        query.setFrom(T0);
        query.setTo(T0.plusSeconds(3600));
        query.setDimension("auth_geo");
        query.setMetric("authentications");
        var auth = credential.aggregate(query);
        assertEquals("2", auth.totalValue());
        assertEquals("203.0.113.5", auth.items().get(0).sources().get(0).clientIp());

        var stats = new UserTrafficStatsService(db.client, db.store)
                .getMonthlyUserTrafficStats(WORKER, YearMonth.from(T0.atZone(ZoneId.systemDefault())));
        assertEquals("alice", stats.get(0).getUsername());
        assertEquals(100L, stats.get(0).getByteIn());
        assertEquals(1000L, stats.get(0).getByteOut());
        assertEquals(2, stats.size());
    }

    @Test
    void retentionDeletesOnlyExpiredFactsOfConfiguredTables() throws Exception {
        db.properties.setDnsRetentionDays(1);
        db.ingest(WORKER, dns(Instant.now(), "ANSWERED", true));
        assertEquals(4, db.count(TelemetrySchema.DNS));
        db.store.purgeExpired();
        assertEquals(1, db.count(TelemetrySchema.DNS));
        assertEquals(4, db.count(TelemetrySchema.SESSIONS));
        assertEquals("2", db.client.query("PRAGMA auto_vacuum").get(0).get("auto_vacuum").toString());
    }

    private static TelemetryQueryRequest audit() {
        var request = new TelemetryQueryRequest();
        request.setWorkerId(WORKER);
        request.setFrom(T0);
        request.setTo(T0.plusSeconds(3600));
        return request;
    }
}
