package org.congcong.proxyworker.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakeIpRegistryTest {
    @Test
    void normalizesDomainsAndReusesLeasesWithoutExpiringThem() {
        FakeIpRegistry registry = new FakeIpRegistry(2);
        String address = registry.getOrAllocate("BÜCHER.Example.");
        assertEquals(address, registry.getOrAllocate("xn--bcher-kva.example"));
        assertEquals("xn--bcher-kva.example", registry.reverseLookup(address).orElseThrow());
        assertTrue(FakeIpRegistry.isInPool(address));
        assertEquals(1, registry.allocations());
        assertTrue(FakeIpRegistry.isInPool("198.18.0.0"));
        assertTrue(FakeIpRegistry.isInPool("198.19.255.255"));
    }

    @Test
    void reverseLookupRefreshesLruAndEvictionKeepsIndexesConsistent() {
        FakeIpRegistry registry = new FakeIpRegistry(2);
        String first = registry.getOrAllocate("first.example");
        String second = registry.getOrAllocate("second.example");
        registry.reverseLookup(first);
        String third = registry.getOrAllocate("third.example");
        assertNotEquals(second, third); // unused pool addresses precede recycled addresses
        assertEquals("first.example", registry.reverseLookup(first).orElseThrow());
        assertTrue(registry.reverseLookup(second).isEmpty());
        assertEquals(1, registry.evictions());
        assertEquals(1, registry.reverseMisses());
    }
}
