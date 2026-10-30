WITH RECURSIVE bounds AS (
    SELECT CAST(? AS INTEGER) AS start_us, CAST(? AS INTEGER) AS end_us, CAST(? AS INTEGER) AS step_us
), intervals AS (
    SELECT connected_at, ended_at
    FROM proxy_session_log_v1 WHERE %s
), ticks(tick_us) AS (
    -- Zero-delta bucket boundaries split idle and long-lived spans without rounding events.
    SELECT start_us FROM bounds
    UNION ALL
    SELECT tick_us + step_us FROM ticks, bounds WHERE tick_us + step_us < end_us
), events AS (
    SELECT max(connected_at, start_us) AS event_us, 1 AS delta FROM intervals, bounds
    UNION ALL
    SELECT min(ended_at, end_us), -1 FROM intervals, bounds
    UNION ALL
    SELECT tick_us, 0 FROM ticks
    UNION ALL
    SELECT end_us, 0 FROM bounds
), merged AS (
    SELECT event_us, SUM(delta) AS delta FROM events GROUP BY event_us
), spans AS (
    SELECT event_us,
        SUM(delta) OVER (ORDER BY event_us ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS active,
        LEAD(event_us, 1, event_us) OVER (ORDER BY event_us) AS next_us
    FROM merged
), buckets AS (
    -- active * span stays far below 2^63 (e.g. 10^6 concurrent sessions over a 31-day window is ~2.7 * 10^18).
    SELECT (event_us - start_us) / step_us AS bucket_index,
        SUM(active * (next_us - event_us)) AS area_us,
        MAX(active) AS peak
    FROM spans, bounds
    WHERE event_us < end_us
    GROUP BY bucket_index
)
SELECT bucket_index,
    CAST(area_us AS REAL) / min(step_us, end_us - start_us - bucket_index * step_us) AS average,
    CAST(peak AS TEXT) AS peak,
    CAST(SUM(area_us) OVER () AS REAL) / (end_us - start_us) AS overall_average,
    CAST(MAX(peak) OVER () AS TEXT) AS overall_peak
FROM buckets, bounds
ORDER BY bucket_index
