package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.CredentialAuthLogV1;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.*;

/** Authentication attempts are independent of ended sessions and traffic bytes. */
@Service
@RequiredArgsConstructor
public class CredentialDistributionService {
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private final CredentialGeoAnalysis geo;

    public record Counts(String attempts) { }
    public record Source(String clientIp, Counts counts) { }
    public record Item(List<String> keys, String value, Counts auth, Map<String, String> unknownReasons,
                       List<Source> sources) { }
    public record Distribution(String from, String to, String dimension, String metric, List<Item> items,
                               Counts authTotals, String totalValue, String otherValue, String groups,
                               String historyBasis, String timeBoundary) { }
    private static class Group {
        BigInteger attempts = BigInteger.ZERO;
        final Map<String, BigInteger> reasons = new TreeMap<>();
        final List<Source> sources = new ArrayList<>();
        Counts counts() {
            return new Counts(attempts.toString());
        }
    }

    public Distribution aggregate(DistributionQuery request) {
        // Reuse the existing bounds and source-filter validation without allowing connection metrics here.
        var validation = new DistributionQuery();
        validation.setWorkerId(request.getWorkerId());
        validation.setFrom(request.getFrom());
        validation.setTo(request.getTo());
        validation.setLimit(request.getLimit());
        validation.setUsername(request.getUsername());
        validation.setClientIp(request.getClientIp());
        DistributionService.validate(validation);
        if (!"auth_geo".equals(request.getDimension()) || !"authentications".equals(request.getMetric())) {
            throw new IllegalArgumentException("认证地理位置仅支持认证失败次数");
        }
        store.requireQueryAvailable();
        // Fixed failure filter keeps legacy SUCCESS rows from older workers out of the statistics.
        StringBuilder where = new StringBuilder("worker_id = ? AND occurred_at >= ? AND occurred_at < ? AND "
                + OverviewService.AUTH_FAILURE_FILTER);
        var params = new ArrayList<Object>(List.of(request.getWorkerId(), Timestamp.from(request.getFrom()), Timestamp.from(request.getTo())));
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            where.append(" AND (attempted_username = ? OR username = ?)");
            params.addAll(Collections.nCopies(2, request.getUsername()));
        }
        if (request.getClientIp() != null && !request.getClientIp().isBlank()) {
            where.append(" AND client_ip = ?");
            params.add(request.getClientIp());
        }
        // No TopN or audit-page truncation before geolocation and full-denominator aggregation.
        var rows = client.query("SELECT coalesce(client_ip,'') AS ip, toString(count()) AS attempts "
                + "FROM " + TelemetrySchema.AUTH.table + " FINAL WHERE " + where + " GROUP BY ip ORDER BY ip", params.toArray());
        Map<List<String>, Group> groups = new HashMap<>();
        Group totals = new Group();
        for (var row : rows) {
            String ip = Objects.toString(row.get("ip"), "");
            var fact = new CredentialAuthLogV1();
            fact.setClientIp(ip);
            // Use the same lookup/cache as audit repair, including retry after an unavailable database.
            var analysis = geo.analyze(fact);
            String country = normalized(analysis.getSourceCountry());
            String region = normalized(analysis.getSourceRegion());
            String city = normalized(analysis.getSourceCity());
            boolean privateIp = "PRIVATE".equals(analysis.getGeoLookupStatus());
            boolean unknown = country.isEmpty() && region.isEmpty() && city.isEmpty();
            List<String> keys = privateIp ? List.of("__PRIVATE__", "", "")
                    : unknown ? List.of("__UNKNOWN__", "", "") : List.of(country, region, city);
            Group group = groups.computeIfAbsent(keys, ignored -> new Group());
            BigInteger count = new BigInteger(row.get("attempts").toString());
            for (Group target : List.of(group, totals)) {
                target.attempts = target.attempts.add(count);
            }
            group.sources.add(new Source(ip, new Counts(count.toString())));
            if (unknown && !privateIp) group.reasons.merge(Objects.toString(analysis.getGeoLookupStatus(), "UNKNOWN"), count, BigInteger::add);
        }
        var sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.<Map.Entry<List<String>, Group>, BigInteger>comparing(entry -> entry.getValue().attempts).reversed()
                .thenComparing(entry -> entry.getKey().toString()));
        var items = new ArrayList<Item>();
        BigInteger top = BigInteger.ZERO;
        for (var entry : sorted.subList(0, Math.min(request.getLimit(), sorted.size()))) {
            var group = entry.getValue();
            Map<String, String> reasons = new TreeMap<>();
            group.reasons.forEach((key, value) -> reasons.put(key, value.toString()));
            items.add(new Item(entry.getKey(), group.attempts.toString(), group.counts(), reasons, group.sources));
            top = top.add(group.attempts);
        }
        return new Distribution(request.getFrom().toString(), request.getTo().toString(), "auth_geo", "authentications", items,
                totals.counts(), totals.attempts.toString(), totals.attempts.subtract(top).toString(), Integer.toString(groups.size()),
                "AUTH_ATTEMPTS_ONLY", "[from,to)");
    }

    static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
