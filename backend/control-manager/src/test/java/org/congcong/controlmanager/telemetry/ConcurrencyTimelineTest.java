package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTimelineTest {
    private final Instant from = Instant.parse("2026-10-09T00:00:00Z");
    private Map<String, Object> delta(long seconds, long count) {
        return Map.of("event_us", (from.getEpochSecond() + seconds) * 1_000_000, "delta", count);
    }
    @Test
    void includesCarryInAndCalculatesDurationWeightedAverageAndPeak() {
        var result = ConcurrencyTimeline.calculate(from, from.plusSeconds(600), 60,
                List.of(delta(0, 1), delta(120, 1), delta(240, 1), delta(300, -1), delta(360, -1), delta(480, -1)));
        assertEquals("3", result.peak());
        assertEquals("1.30", result.average());
        assertEquals(List.of("1", "1", "2", "2", "3", "2", "1", "1", "0", "0"),
                result.series().stream().map(ConcurrencyTimeline.Point::peak).toList());
        assertEquals("0.00", result.series().get(9).average());
    }
    @Test
    void simultaneousEndsAndStartsDoNotCreateAnArtificialPeak() {
        var result = ConcurrencyTimeline.calculate(from, from.plusSeconds(120), 60,
                List.of(delta(0, 1), delta(60, 0), delta(120, -1)));
        assertEquals("1", result.peak());
        assertEquals("1.00", result.average());
    }
    @Test
    void endingAtBucketBoundaryDoesNotCountInNextBucketAndPartialBucketHasCorrectDenominator() {
        var result = ConcurrencyTimeline.calculate(from, from.plusSeconds(90), 60,
                List.of(delta(0, 2), delta(60, -2), delta(90, 0)));
        assertEquals("0", result.series().get(1).peak());
        assertEquals("1.33", result.average());
    }
}
