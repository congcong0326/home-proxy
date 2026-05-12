package org.congcong.proxyworker.outbound.reality.vless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBufUtil;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VlessCodecTest {

    @Test
    void encodesXtlsRprxVisionAddonBeforeCommand() {
        VlessRequest request = new VlessRequest(
                UUID.fromString("f1ae6f5a-8d34-41bd-9ddc-e578a07b2d4a"),
                "example.com",
                443,
                VlessFlow.XTLS_RPRX_VISION);

        byte[] encoded = new VlessCodec().encode(request);

        assertEquals("120a1078746c732d727072782d766973696f6e", ByteBufUtil.hexDump(encoded, 17, 19));
        assertEquals(0x01, encoded[36] & 0xff);
    }

    @Test
    void encodesIpLiteralsWithIpAddressTypes() {
        byte[] ipv4 = new VlessCodec().encode(new VlessRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "1.2.3.4",
                443,
                VlessFlow.NONE));
        byte[] ipv6 = new VlessCodec().encode(new VlessRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "2001:db8::1",
                443,
                VlessFlow.NONE));

        assertEquals(0x01, ipv4[21] & 0xff);
        assertEquals("01020304", ByteBufUtil.hexDump(ipv4, 22, 4));
        assertEquals(0x03, ipv6[21] & 0xff);
        assertEquals("20010db8000000000000000000000001", ByteBufUtil.hexDump(ipv6, 22, 16));
        assertTrue(ipv6.length > ipv4.length);
    }
}
