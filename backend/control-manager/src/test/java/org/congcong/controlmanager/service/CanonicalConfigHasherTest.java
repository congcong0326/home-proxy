package org.congcong.controlmanager.service;

import org.congcong.common.dto.InboundConfigDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CanonicalConfigHasherTest {

    @Test
    void hashesInboundWithLocalDateTimeFieldsDeterministically() {
        InboundConfigDTO inbound = new InboundConfigDTO();
        inbound.setId(1L);
        inbound.setWorkerId("default");
        inbound.setName("local-socks");
        inbound.setCreatedAt(LocalDateTime.of(2026, 7, 25, 20, 25));
        inbound.setUpdatedAt(LocalDateTime.of(2026, 7, 25, 20, 26));

        CanonicalConfigHasher hasher = new CanonicalConfigHasher();
        String first = hasher.hash(List.of(inbound), List.of(), List.of(), List.of(), List.of());
        String second = hasher.hash(List.of(inbound), List.of(), List.of(), List.of(), List.of());

        assertFalse(first.isBlank());
        assertEquals(first, second);
    }
}
