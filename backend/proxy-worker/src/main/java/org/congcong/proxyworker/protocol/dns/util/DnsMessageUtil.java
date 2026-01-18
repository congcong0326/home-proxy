package org.congcong.proxyworker.protocol.dns.util;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsMessage;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.util.NetUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具：估算 DNS 报文大小并提取 A/AAAA 记录 IP。
 */
public final class DnsMessageUtil {
    private static final int DNS_HEADER_BYTES = 12; // 固定头部长度
    private static final int RESOURCE_FIXED_BYTES = 10; // type + class + ttl + rdlength

    private DnsMessageUtil() {
    }

    public static long estimateMessageSize(DnsMessage message) {
        if (message == null) return 0L;
        int size = DNS_HEADER_BYTES;
        size += estimateQuestions(message);
        size += estimateRecords(message, DnsSection.ANSWER);
        size += estimateRecords(message, DnsSection.AUTHORITY);
        size += estimateRecords(message, DnsSection.ADDITIONAL);
        return size;
    }

    public static List<String> extractAnswerIps(DnsMessage message) {
        if (message == null) return Collections.emptyList();
        Set<String> ips = new LinkedHashSet<>();
        collectIps(message, DnsSection.ANSWER, ips);
        if (ips.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(ips);
    }

    private static int estimateQuestions(DnsMessage message) {
        int count = message.count(DnsSection.QUESTION);
        int total = 0;
        for (int i = 0; i < count; i++) {
            DnsRecord record = message.recordAt(DnsSection.QUESTION, i);
            if (record == null) continue;
            total += estimateNameSize(record.name());
            total += 4; // type + class
        }
        return total;
    }

    private static int estimateRecords(DnsMessage message, DnsSection section) {
        int count = message.count(section);
        int total = 0;
        for (int i = 0; i < count; i++) {
            DnsRecord record = message.recordAt(section, i);
            if (record == null) continue;
            total += estimateNameSize(record.name());
            total += RESOURCE_FIXED_BYTES;
            total += estimateRdataLength(record);
        }
        return total;
    }

    private static int estimateNameSize(String name) {
        if (name == null || name.isEmpty()) return 1; // root
        int total = 1; // 结尾的 \0
        int start = 0;
        int idx;
        while ((idx = name.indexOf('.', start)) >= 0) {
            total += Math.min(idx - start, 63) + 1; // label len + dot
            start = idx + 1;
        }
        total += Math.min(name.length() - start, 63);
        return total;
    }

    private static int estimateRdataLength(DnsRecord record) {
        if (record instanceof DnsRawRecord raw) {
            return raw.content().readableBytes();
        }
        if (record instanceof DnsQuestion) {
            return 0;
        }
        return 0;
    }

    private static void collectIps(DnsMessage message, DnsSection section, Set<String> ips) {
        int count = message.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord record = message.recordAt(section, i);
            if (!(record instanceof DnsRawRecord raw)) {
                continue;
            }
            int readable = raw.content().readableBytes();
            if (record.type() == DnsRecordType.A && readable >= 4) {
                ips.add(readIp(raw.content(), 4));
            } else if (record.type() == DnsRecordType.AAAA && readable >= 16) {
                ips.add(readIp(raw.content(), 16));
            }
        }
    }

    private static String readIp(ByteBuf buf, int len) {
        byte[] data = new byte[len];
        int readerIdx = buf.readerIndex();
        buf.getBytes(readerIdx, data, 0, len);
        return NetUtil.bytesToIpAddress(data);
    }
}
