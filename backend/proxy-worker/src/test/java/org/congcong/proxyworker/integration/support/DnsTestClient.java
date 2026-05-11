package org.congcong.proxyworker.integration.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DnsTestClient {
    private DnsTestClient() {
    }

    public static List<String> queryA(int dnsPort, String qName) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
            byte[] query = query(qName);
            socket.send(new DatagramPacket(query, query.length, new InetSocketAddress("127.0.0.1", dnsPort)));
            byte[] buffer = new byte[512];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return parseARecords(response.getData(), response.getLength());
        } catch (IOException e) {
            throw new IllegalStateException("DNS query failed", e);
        }
    }

    private static byte[] query(String qName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        for (String label : qName.replaceAll("\\.$", "").split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        return out.toByteArray();
    }

    private static List<String> parseARecords(byte[] data, int length) throws IOException {
        int answerCount = ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        int offset = skipName(data, 12) + 4;
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < answerCount && offset < length; i++) {
            offset = skipName(data, offset);
            int type = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            offset += 8;
            int rdLength = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            offset += 2;
            if (type == 1 && rdLength == 4) {
                answers.add(InetAddress.getByAddress(java.util.Arrays.copyOfRange(data, offset, offset + 4)).getHostAddress());
            }
            offset += rdLength;
        }
        return answers;
    }

    private static int skipName(byte[] data, int offset) throws IOException {
        while (true) {
            int len = data[offset] & 0xff;
            if (len == 0) {
                return offset + 1;
            }
            if ((len & 0xc0) == 0xc0) {
                return offset + 2;
            }
            offset += len + 1;
            if (offset >= data.length) {
                throw new IOException("Invalid DNS name");
            }
        }
    }
}
