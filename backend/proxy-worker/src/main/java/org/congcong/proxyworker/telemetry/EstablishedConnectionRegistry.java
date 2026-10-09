package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.EstablishedConnectionsV1;
import org.congcong.common.telemetry.SessionLogV1;
import org.congcong.common.telemetry.TrafficCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** One immutable entry per live business connection; entries disappear at inbound close. */
public final class EstablishedConnectionRegistry {
    private static final EstablishedConnectionRegistry INSTANCE = new EstablishedConnectionRegistry();
    private final ConcurrentHashMap<String, Key> connections = new ConcurrentHashMap<>();
    private record Key(TrafficCategory category, String username, String clientIp, Long inboundId) { }
    public static EstablishedConnectionRegistry getInstance() { return INSTANCE; }

    public void connected(SessionLogV1 record) {
        connections.putIfAbsent(record.getConnectionId(), new Key(
                record.getTrafficCategory() == null ? TrafficCategory.UNKNOWN : record.getTrafficCategory(),
                record.getUsername(), record.getClientIp(), record.getInboundId()));
    }
    public void closed(String connectionId) { connections.remove(connectionId); }

    public EstablishedConnectionsV1 snapshot(String instanceId) {
        var totals = new EnumMap<TrafficCategory, Long>(TrafficCategory.class);
        var grouped = new HashMap<Key, Long>();
        connections.forEach((id, key) -> {
            totals.merge(key.category(), 1L, Long::sum);
            grouped.merge(key, 1L, Long::sum);
        });
        var groups = new ArrayList<EstablishedConnectionsV1.Group>();
        grouped.entrySet().stream().sorted(java.util.Comparator
                .<java.util.Map.Entry<Key, Long>>comparingLong(java.util.Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().toString())).limit(200).forEach(entry -> {
                    Key key = entry.getKey();
                    groups.add(new EstablishedConnectionsV1.Group(key.category(), key.username(),
                            key.clientIp(), key.inboundId(), entry.getValue()));
                });
        return new EstablishedConnectionsV1(instanceId, Instant.now(), totals, groups, grouped.size() > 200);
    }
}
