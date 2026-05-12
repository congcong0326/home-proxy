package org.congcong.proxyworker.outbound.reality.vless;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VlessResponseHeaderDecoderTest {

    @Test
    void supportsSplitResponseHeader() {
        VlessResponseHeaderDecoder decoder = new VlessResponseHeaderDecoder();

        VlessResponseHeader first = decoder.decode(new byte[] {0x00});
        VlessResponseHeader second = decoder.decode(new byte[] {0x00, 0x7f});

        assertFalse(first.complete());
        assertArrayEquals(new byte[0], first.payload());
        assertTrue(second.complete());
        assertArrayEquals(new byte[] {0x7f}, second.payload());
    }

    @Test
    void skipsNonZeroAddonsBeforePayload() {
        VlessResponseHeader header = new VlessResponseHeaderDecoder()
                .decode(new byte[] {0x00, 0x02, 0x11, 0x12, 0x01, 0x02});

        assertTrue(header.complete());
        assertArrayEquals(new byte[] {0x01, 0x02}, header.payload());
    }

    @Test
    void rejectsUnexpectedResponseVersion() {
        VlessResponseHeaderDecoder decoder = new VlessResponseHeaderDecoder();

        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[] {'H', 'T', 'T', 'P'}));
    }
}
