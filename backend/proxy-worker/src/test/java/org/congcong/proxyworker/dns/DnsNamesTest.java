package org.congcong.proxyworker.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DnsNamesTest {
    @Test
    void dnsNamesPermitServiceLabelsRootAndNonHostnameCharacters() {
        assertEquals("_sip._tcp.example", DnsNames.normalize("_SIP._TCP.Example."));
        assertEquals(".", DnsNames.normalize("."));
        assertEquals("-probe.example", DnsNames.normalize("-probe.example"));
        assertEquals("*.example", DnsNames.normalize("*.example."));
        assertFalse(DnsNames.isHostname("_probe.example"));
        assertFalse(DnsNames.isHostname("."));
        assertFalse(DnsNames.isHostname("-probe.example"));
        assertTrue(DnsNames.isHostname("ordinary-host.example"));
    }

    @Test
    void normalizesInternationalNamesAndEnforcesDnsWireLimits() {
        assertEquals("xn--bcher-kva.example", DnsNames.normalize("BÜCHER.Example."));
        String maxName = ("a".repeat(63) + ".").repeat(3) + "b".repeat(61);
        assertEquals(maxName, DnsNames.normalize(maxName + ".")); // 255 bytes including root and label lengths
        for (String invalid : new String[]{"", "..", "a..example", "example..", "a".repeat(64) + ".example",
                maxName + "b", "bad name.example", "bad\u0000name.example"}) {
            assertThrows(IllegalArgumentException.class, () -> DnsNames.normalize(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> DnsNames.normalize(null));
    }

    @Test
    void dnsRelaxationDoesNotPermitNonHostNamesToAcquireFakeLeases() {
        FakeIpRegistry registry = new FakeIpRegistry(2);
        assertThrows(IllegalArgumentException.class, () -> registry.getOrAllocate("_probe.example"));
        assertThrows(IllegalArgumentException.class, () -> registry.getOrAllocate("."));
        assertEquals(0, registry.allocations());
    }
}
