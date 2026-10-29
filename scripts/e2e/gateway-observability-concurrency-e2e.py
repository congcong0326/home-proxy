#!/usr/bin/env python3
"""Read-only regression of the production concurrency SQL on ClickHouse 24.8.

Uses SELECT-only synthetic fixtures; no database tables or retained facts are changed.
"""
import json
import math
import os
import random
import subprocess
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SQL = (ROOT / 'backend/control-manager/src/main/resources/clickhouse/overview-concurrency.sql').read_text()
START = 1_728_432_000_123_456
SECOND = 1_000_000


def ck(sql):
    result = subprocess.run([
        'docker', 'exec', os.environ.get('E2E_CLICKHOUSE_CONTAINER', 'home-proxy-clickhouse'),
        'sh', '-c',
        'exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "$1"',
        'sh', sql + ' FORMAT JSONEachRow',
    ], capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return [json.loads(line) for line in result.stdout.splitlines() if line]


def source_for(intervals):
    if not intervals:
        return "SELECT fromUnixTimestamp64Micro(toInt64(0)) AS connected_at, connected_at AS ended_at FROM numbers(0)"
    pairs = ','.join(f'({START + left},{START + right})' for left, right in intervals)
    return ("SELECT fromUnixTimestamp64Micro(pair.1) AS connected_at, "
            f"fromUnixTimestamp64Micro(pair.2) AS ended_at FROM (SELECT arrayJoin([{pairs}]) AS pair)")


def oracle(intervals, left, right):
    """Independent average from interval intersections, peak from grouped local events."""
    area, events = 0, defaultdict(int)
    for start, end in intervals:
        start, end = max(left, start), min(right, end)
        if start >= end:
            continue
        area += end - start
        events[start] += 1
        events[end] -= 1
    active, peak = 0, 0
    for timestamp, delta in sorted(events.items()):
        active += delta
        if timestamp < right:
            peak = max(peak, active)
    return area / (right - left), str(peak)


def verify(name, intervals, duration=600 * SECOND, step=60 * SECOND, source=None):
    params = iter([START, START + duration, step])
    sql = SQL
    for value in params:
        sql = sql.replace('?', str(value), 1)
    predicate = (f"connected_at < fromUnixTimestamp64Micro(toInt64({START + duration})) "
                 f"AND ended_at > fromUnixTimestamp64Micro(toInt64({START})) AND ended_at > connected_at")
    sql = sql.replace('%s', predicate).replace('telemetry.proxy_session_log_v1 FINAL',
                                               '(' + (source or source_for(intervals)) + ')')
    before = time.monotonic()
    rows = ck(sql)
    elapsed = time.monotonic() - before
    assert len(rows) == math.ceil(duration / step), (name, len(rows))
    overall_average, overall_peak = oracle(intervals, 0, duration)
    for index, row in enumerate(rows):
        average, peak = oracle(intervals, index * step, min(duration, (index + 1) * step))
        assert int(row['bucket_index']) == index, (name, row)
        assert math.isclose(row['average'], average, abs_tol=1e-10), (name, row, average)
        assert row['peak'] == peak, (name, row, peak)
        assert math.isclose(row['overall_average'], overall_average, abs_tol=1e-10), (name, row)
        assert row['overall_peak'] == overall_peak, (name, row)
    return dict(case=name, intervals=len(intervals), returnedBuckets=len(rows),
                peak=overall_peak, elapsedSeconds=round(elapsed, 3))


def main():
    reports = [verify('empty', [])]
    reports.append(verify('carry-in and weighted average', [
        (-60 * SECOND, 300 * SECOND), (120 * SECOND, 360 * SECOND), (240 * SECOND, 480 * SECOND),
    ]))
    reports.append(verify('simultaneous ends and starts', [
        (0, 60 * SECOND), (60 * SECOND, 120 * SECOND),
    ], duration=120 * SECOND))
    reports.append(verify('bucket boundary and partial bucket', [
        (0, 60 * SECOND), (0, 60 * SECOND),
    ], duration=90 * SECOND))
    reports.append(verify('clipping and microseconds', [
        (-SECOND, 150 * SECOND), (-SECOND, 0), (90 * SECOND + 3, 120 * SECOND),
        (0, 0), (SECOND, SECOND - 1), (SECOND + 1, SECOND + 2),
        (60 * SECOND - 1, 60 * SECOND + 1),
    ], duration=90 * SECOND + 3))
    rng = random.Random(173)
    intervals = [(rng.randrange(-120 * SECOND, 700 * SECOND), rng.randrange(-120 * SECOND, 700 * SECOND))
                 for _ in range(4000)]
    intervals = [(start, end) for start, end in intervals if start < end]
    reports.append(verify('randomized interval intersections', intervals, duration=600 * SECOND + 7))
    # 120,000 distinct endpoints exceed the former 100,000-row limit.
    intervals = [(index * 10 + 1, 600001 + index * 10) for index in range(60000)]
    source = (f"SELECT fromUnixTimestamp64Micro(toInt64({START}) + toInt64(number) * 10 + 1) AS connected_at, "
              f"fromUnixTimestamp64Micro(toInt64({START}) + 600001 + toInt64(number) * 10) AS ended_at FROM numbers(60000)")
    reports.append(verify('over former event limit', intervals, duration=90 * SECOND, source=source))
    print(json.dumps(dict(readOnly=True, results=reports), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
