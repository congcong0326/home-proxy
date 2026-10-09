package org.congcong.common.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrafficClassifierV1Test {
    @Test
    void onlyAppliedOverrideToRealPrivateDestinationIsNasTraffic() {
        for (String ip : new String[]{"10.0.0.1", "172.16.0.1", "192.168.10.85", "fd00::85", "::ffff:192.168.10.85"}) {
            SessionLogV1 record = classify(ip, true, "DESTINATION_OVERRIDE");
            assertEquals(TrafficCategory.INTERNAL_REWRITE, record.getTrafficCategory(), ip);
            assertEquals(NetworkScope.INTERNAL, record.getTargetNetworkScope());
        }
        assertEquals(TrafficCategory.OTHER_INTERNAL, classify("192.168.10.85", false, "DIRECT").getTrafficCategory());
        assertEquals(TrafficCategory.EXTERNAL, classify("8.8.8.8", true, "DESTINATION_OVERRIDE").getTrafficCategory());
    }

    @Test
    void reservedFakeAndUnresolvedTargetsAreNotPrivateNas() {
        for (String ip : new String[]{"198.18.0.1", "198.19.255.254", "100.64.1.2", "127.0.0.1", "169.254.1.2",
                "192.0.2.85", "198.51.100.85", "203.0.113.85", "0.0.0.0", "224.0.0.1", "::1", "2001:db8::85",
                "fe80::1", "ff02::1", "nas.example.test", "", null}) {
            assertEquals(TrafficCategory.UNKNOWN, classify(ip, true, "DESTINATION_OVERRIDE").getTrafficCategory(), ip);
        }
    }

    @Test
    void remoteResolutionKeepsTargetUnknownButBusinessTrafficExternal() {
        SessionLogV1 record = classify(null, false, "OUTBOUND_PROXY");
        record.setOutboundPeerIp("192.168.0.10");
        TrafficClassifierV1.classify(record);
        assertEquals(TrafficCategory.EXTERNAL, record.getTrafficCategory());
        assertEquals("OUTBOUND_PROXY_ROUTE", record.getTrafficCategoryBasis());
        assertNull(record.getSelectedTargetIp());
        assertEquals(NetworkScope.UNKNOWN, record.getTargetNetworkScope());
        assertEquals(NetworkScope.INTERNAL, record.getOutboundPeerNetworkScope());
    }

    private SessionLogV1 classify(String ip, boolean override, String route) {
        SessionLogV1 record = new SessionLogV1();
        record.setSelectedTargetIp(ip); record.setDestinationOverrideApplied(override); record.setRouteAction(route);
        TrafficClassifierV1.classify(record);
        return record;
    }
}
