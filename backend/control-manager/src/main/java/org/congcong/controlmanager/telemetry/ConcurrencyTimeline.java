package org.congcong.controlmanager.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sweep half-open intervals: simultaneous starts/ends are combined before counting. */
public final class ConcurrencyTimeline {
    private ConcurrencyTimeline() { }
    public record Point(String ts, String average, String peak) { }
    public record Result(List<Point> series, String average, String peak) { }

    public static Result calculate(Instant from, Instant to, int bucketSeconds, List<Map<String, Object>> deltas) {
        long start = micros(from), end = micros(to), step = bucketSeconds * 1_000_000L;
        int index = 0;
        long active = 0, globalPeak = 0;
        double globalArea = 0;
        var points = new ArrayList<Point>();
        for (long bucket = start; bucket < end; bucket += step) {
            long right = Math.min(end, bucket + step), cursor = bucket, peak = active;
            double area = 0;
            while (index < deltas.size()) {
                Map<String, Object> delta = deltas.get(index);
                long time = ((Number) delta.get("event_us")).longValue();
                if (time >= right) break;
                area += active * (double) (time - cursor);
                active += ((Number) delta.get("delta")).longValue();
                if (active < 0) throw new IllegalStateException("INVALID_CONCURRENCY_INTERVALS");
                cursor = time;
                // At the left boundary, connections ending there do not belong to this bucket.
                if (time == bucket) peak = active;
                else peak = Math.max(peak, active);
                index++;
            }
            area += active * (double) (right - cursor);
            points.add(new Point(Instant.ofEpochSecond(bucket / 1_000_000, bucket % 1_000_000 * 1000).toString(),
                    decimal(area / (right - bucket)), Long.toString(peak)));
            globalArea += area;
            globalPeak = Math.max(globalPeak, peak);
        }
        return new Result(points, decimal(globalArea / (end - start)), Long.toString(globalPeak));
    }
    private static long micros(Instant time) { return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000; }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
