package org.congcong.proxyworker.udp;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Internal diagnostics only. Gauges and cumulative counters are safe to inspect off-loop. */
public final class UdpRelayCounters {
    private final ConcurrentHashMap<String, AtomicLong> values = new ConcurrentHashMap<>();
    private final AtomicLong activity = new AtomicLong();

    public UdpRelayCounters() {
        for (String name : new String[]{"sessions_connecting", "sessions_ready", "sessions_rejected", "reply_sockets"}) {
            values.put(name, new AtomicLong());
        }
    }

    public void add(String name, long delta) {
        values.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(delta);
        activity.incrementAndGet();
    }

    public void increment(String name) { add(name, 1); }
    public void drop(String reason) { increment("drop_" + reason); }
    public long get(String name) { return values.getOrDefault(name, new AtomicLong()).get(); }
    public long activity() { return activity.get(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
