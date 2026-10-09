package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EstablishedConnectionRegistryTest {
    @Test
    void duplicateConnectAndCloseCallbacksAreIdempotentAndCategoriesRemainSeparate() {
        var registry = new EstablishedConnectionRegistry();
        var external = session(TrafficCategory.EXTERNAL, "alice", "192.168.1.1");
        var internal = session(TrafficCategory.INTERNAL_REWRITE, "alice", "192.168.1.1");
        registry.connected(external); registry.connected(external); registry.connected(internal);
        var snapshot = registry.snapshot("instance");
        assertEquals(1L, snapshot.byCategory().get(TrafficCategory.EXTERNAL));
        assertEquals(1L, snapshot.byCategory().get(TrafficCategory.INTERNAL_REWRITE));
        registry.closed(external.getConnectionId()); registry.closed(external.getConnectionId());
        assertNull(registry.snapshot("instance").byCategory().get(TrafficCategory.EXTERNAL));
        registry.closed(internal.getConnectionId()); assertTrue(registry.snapshot("instance").groups().isEmpty());
    }
    @Test
    void boundsHeartbeatGroupsWithoutLosingCategoryTotals() {
        var registry = new EstablishedConnectionRegistry();
        for (int i = 0; i < 250; i++) registry.connected(session(TrafficCategory.EXTERNAL, "user-" + i, "192.168.1.1"));
        var snapshot = registry.snapshot("instance");
        assertTrue(snapshot.groupsTruncated()); assertEquals(200, snapshot.groups().size());
        assertEquals(250L, snapshot.byCategory().get(TrafficCategory.EXTERNAL));
    }
    private SessionLogV1 session(TrafficCategory category, String username, String ip) {
        var record = new SessionLogV1(); record.setConnectionId(UUID.randomUUID().toString());
        record.setTrafficCategory(category); record.setUsername(username); record.setClientIp(ip); return record;
    }
}
