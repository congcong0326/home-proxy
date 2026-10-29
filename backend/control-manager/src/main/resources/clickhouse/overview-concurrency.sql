WITH bounds AS (
    SELECT toInt64(?) AS start_us, toInt64(?) AS end_us, toInt64(?) AS step_us
), intervals AS (
    SELECT connected_at, ended_at
    FROM telemetry.proxy_session_log_v1 FINAL WHERE %s
), events AS (
    SELECT event.1 AS event_us, event.2 AS delta
    FROM intervals CROSS JOIN bounds
    ARRAY JOIN [
        (greatest(toUnixTimestamp64Micro(connected_at), start_us), toInt64(1)),
        (least(toUnixTimestamp64Micro(ended_at), end_us), toInt64(-1))
    ] AS event
    UNION ALL
    -- Zero-delta bucket boundaries split idle and long-lived spans without rounding events.
    SELECT least(start_us + toInt64(arrayJoin(range(toUInt64(
        intDiv(end_us - start_us + step_us - 1, step_us) + 1)))) * step_us, end_us), toInt64(0)
    FROM bounds
), merged AS (
    SELECT event_us, sum(delta) AS delta FROM events GROUP BY event_us
), spans AS (
    SELECT event_us,
        sum(delta) OVER (ORDER BY event_us ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS active,
        leadInFrame(event_us, 1, event_us) OVER (
            ORDER BY event_us ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS next_us
    FROM merged
), buckets AS (
    SELECT intDiv(event_us - start_us, step_us) AS bucket_index,
        sum(toInt128(active) * (next_us - event_us)) AS area_us,
        max(active) AS peak
    FROM spans CROSS JOIN bounds
    WHERE event_us < end_us
    GROUP BY bucket_index
)
SELECT bucket_index,
    toFloat64(area_us) / least(step_us, end_us - start_us - bucket_index * step_us) AS average,
    toString(peak) AS peak,
    toFloat64(sum(area_us) OVER ()) / (end_us - start_us) AS overall_average,
    toString(max(peak) OVER ()) AS overall_peak
FROM buckets CROSS JOIN bounds
ORDER BY bucket_index
SETTINGS max_execution_time = 15, timeout_overflow_mode = 'throw',
    max_memory_usage = 268435456, max_threads = 2
