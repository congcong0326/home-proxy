package org.congcong.controlmanager.telemetry;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** ClickHouse computes the exact sweep; Java only maps the bounded bucket results. */
public final class ConcurrencyTimeline {
    private static final String QUERY = readQuery();

    private ConcurrencyTimeline() { }
    public record Point(String ts, String average, String peak) { }
    public record Result(boolean available, List<Point> series, String average, String peak) {
        public static Result unavailable() {
            return new Result(false, List.of(), null, null);
        }
    }

    public static String query(String scope) {
        return QUERY.formatted(scope);
    }

    public static Result fromBuckets(Instant from, Instant to, int bucketSeconds, List<Map<String, Object>> rows) {
        long durationUs = micros(to) - micros(from);
        long stepUs = bucketSeconds * 1_000_000L;
        long expected = (durationUs + stepUs - 1) / stepUs;
        if (rows.size() != expected) throw new IllegalStateException("INCOMPLETE_CONCURRENCY_BUCKETS");
        var points = new ArrayList<Point>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (((Number) row.get("bucket_index")).longValue() != index) {
                throw new IllegalStateException("INCOMPLETE_CONCURRENCY_BUCKETS");
            }
            points.add(new Point(from.plusSeconds((long) index * bucketSeconds).toString(),
                    decimal(row.get("average")), row.get("peak").toString()));
        }
        var totals = rows.get(0);
        return new Result(true, points, decimal(totals.get("overall_average")), totals.get("overall_peak").toString());
    }

    private static String readQuery() {
        try {
            return new ClassPathResource("clickhouse/overview-concurrency.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long micros(Instant time) {
        return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000;
    }

    private static String decimal(Object value) {
        return String.format(Locale.ROOT, "%.2f", ((Number) value).doubleValue());
    }
}
