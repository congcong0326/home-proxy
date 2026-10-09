package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.TrafficCategory;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Monthly payload counters from unique ended sessions, independently of legacy logs. */
@Service
public class InboundTrafficService {
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private final Clock clock = Clock.systemUTC();

    public InboundTrafficService(ClickHouseJdbcClient client, TelemetryStore store) {
        this.client = client;
        this.store = store;
    }

    public record Counters(String upload, String download, String total) { }
    public record MonthlyTraffic(long inboundId, String workerId, String period, String timeZone,
                                 String from, String to, Counters traffic,
                                 Map<TrafficCategory, Counters> categories, String firstSessionAt) { }

    public MonthlyTraffic monthly(long inboundId, String workerId, String month, String timeZone) {
        if (inboundId <= 0 || workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("Valid inboundId and workerId required");
        }
        ZoneId zone = ZoneId.of(timeZone);
        YearMonth period = month == null ? YearMonth.now(clock.withZone(zone)) : YearMonth.parse(month);
        var from = period.atDay(1).atStartOfDay(zone).toInstant();
        var to = period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        store.requireQueryAvailable();
        var rows = client.query("SELECT coalesce(traffic_category, 'UNKNOWN') AS category, "
                + "toString(sum(toUInt128(coalesce(upload_bytes, 0)))) AS upload, "
                + "toString(sum(toUInt128(coalesce(download_bytes, 0)))) AS download "
                + "FROM " + TelemetrySchema.SESSIONS.table + " FINAL "
                + "WHERE worker_id = ? AND inbound_id = ? AND occurred_at >= ? AND occurred_at < ? "
                + "GROUP BY category", workerId, inboundId, Timestamp.from(from), Timestamp.from(to));
        var categories = new EnumMap<TrafficCategory, Counters>(TrafficCategory.class);
        for (TrafficCategory category : TrafficCategory.values()) categories.put(category, counters(BigInteger.ZERO, BigInteger.ZERO));
        BigInteger upload = BigInteger.ZERO, download = BigInteger.ZERO;
        for (var row : rows) {
            TrafficCategory category;
            try { category = TrafficCategory.valueOf(row.get("category").toString()); }
            catch (IllegalArgumentException e) { category = TrafficCategory.UNKNOWN; }
            BigInteger up = new BigInteger(row.get("upload").toString());
            BigInteger down = new BigInteger(row.get("download").toString());
            var previous = categories.get(category);
            categories.put(category, counters(new BigInteger(previous.upload()).add(up), new BigInteger(previous.download()).add(down)));
            upload = upload.add(up);
            download = download.add(down);
        }
        List<Map<String, Object>> first = client.query("SELECT toString(minOrNull(occurred_at)) AS first_at "
                + "FROM " + TelemetrySchema.SESSIONS.table + " FINAL WHERE worker_id = ? AND inbound_id = ?", workerId, inboundId);
        String firstAt = first.isEmpty() || first.get(0).get("first_at") == null ? null : first.get(0).get("first_at").toString();
        return new MonthlyTraffic(inboundId, workerId, period.toString(), zone.getId(), from.toString(), to.toString(),
                counters(upload, download), categories, firstAt);
    }

    private static Counters counters(BigInteger upload, BigInteger download) {
        return new Counters(upload.toString(), download.toString(), upload.add(download).toString());
    }
}
