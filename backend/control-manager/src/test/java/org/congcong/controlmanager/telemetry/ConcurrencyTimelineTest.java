package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTimelineTest {
    private final Instant from = Instant.parse("2026-10-09T00:00:00.123456Z");

    private Map<String, Object> bucket(int index, double average, String peak) {
        return Map.of("bucket_index", index, "average", average, "peak", peak,
                "overall_average", 4.0 / 3, "overall_peak", "2");
    }

    @Test
    void mapsDatabaseAggregatesWithoutReaveragingPartialBuckets() {
        var result = ConcurrencyTimeline.fromBuckets(from, from.plusSeconds(90), 60,
                List.of(bucket(0, 2, "2"), bucket(1, 0, "0")));
        assertTrue(result.available());
        assertEquals("2", result.peak());
        assertEquals("1.33", result.average());
        assertEquals("2.00", result.series().get(0).average());
        assertEquals("0.00", result.series().get(1).average());
        assertEquals(from.plusSeconds(60).toString(), result.series().get(1).ts());
    }

    @Test
    void rejectsTruncatedOrOutOfOrderResultsRatherThanDisplayingPartialStatistics() {
        assertThrows(IllegalStateException.class, () -> ConcurrencyTimeline.fromBuckets(
                from, from.plusSeconds(90), 60, List.of(bucket(0, 2, "2"))));
        assertThrows(IllegalStateException.class, () -> ConcurrencyTimeline.fromBuckets(
                from, from.plusSeconds(90), 60, List.of(bucket(1, 0, "0"), bucket(0, 2, "2"))));
        assertThrows(IllegalStateException.class, () -> ConcurrencyTimeline.fromBuckets(
                from, from.plusSeconds(90), 60, List.of()));
    }

    @Test
    void unavailableStatisticsAreUnknownRatherThanZero() {
        var result = ConcurrencyTimeline.Result.unavailable();
        assertFalse(result.available());
        assertTrue(result.series().isEmpty());
        assertNull(result.average());
        assertNull(result.peak());
    }
}
