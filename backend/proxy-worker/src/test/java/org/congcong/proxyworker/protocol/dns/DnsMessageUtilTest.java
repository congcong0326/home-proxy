package org.congcong.proxyworker.protocol.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import java.util.List;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.protocol.dns.util.DnsMessageUtil;
import org.junit.jupiter.api.Test;

class DnsMessageUtilTest {
    @Test
    void extractsUniqueAnswerIpsWithoutMovingRecordReaderIndex() {
        DatagramDnsResponse response = new DatagramDnsResponse(
                ProxyWorkerTestFixtures.socket("1.1.1.1", 53),
                ProxyWorkerTestFixtures.socket("192.168.1.30", 5353),
                1);
        DefaultDnsRawRecord firstA = raw("example.com.", DnsRecordType.A, 93, 184, 216, 34);
        DefaultDnsRawRecord duplicateA = raw("example.com.", DnsRecordType.A, 93, 184, 216, 34);
        DefaultDnsRawRecord aaaa = raw("example.com.", DnsRecordType.AAAA,
                0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        int readerIndex = firstA.content().readerIndex();

        response.addRecord(DnsSection.ANSWER, firstA);
        response.addRecord(DnsSection.ANSWER, duplicateA);
        response.addRecord(DnsSection.ANSWER, aaaa);

        try {
            assertEquals(List.of("93.184.216.34", "2001:db8::1"),
                    DnsMessageUtil.extractAnswerIps(response));
            assertEquals(readerIndex, firstA.content().readerIndex());
        } finally {
            response.release();
        }
    }

    private DefaultDnsRawRecord raw(String name, DnsRecordType type, int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return new DefaultDnsRawRecord(name, type, 60, Unpooled.wrappedBuffer(data));
    }
}
