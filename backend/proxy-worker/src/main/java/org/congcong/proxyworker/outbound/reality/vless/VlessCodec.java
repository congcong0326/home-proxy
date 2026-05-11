package org.congcong.proxyworker.outbound.reality.vless;

import io.netty.util.NetUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class VlessCodec {

    public byte[] encode(VlessRequest request) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x00);
            out.write(uuidBytes(request));
            out.write(addonBytes(request.flow()));
            out.write(0x01);
            out.write((request.port() >>> 8) & 0xff);
            out.write(request.port() & 0xff);
            writeAddress(out, request.host());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode VLESS request", e);
        }
    }

    private void writeAddress(ByteArrayOutputStream out, String host) {
        byte[] ipAddress = NetUtil.createByteArrayFromIpAddressString(host);
        if (ipAddress != null && ipAddress.length == 4) {
            out.write(0x01);
            out.write(ipAddress, 0, ipAddress.length);
            return;
        }
        if (ipAddress != null && ipAddress.length == 16) {
            out.write(0x03);
            out.write(ipAddress, 0, ipAddress.length);
            return;
        }

        byte[] domain = host.getBytes(StandardCharsets.US_ASCII);
        if (domain.length > 255) {
            throw new IllegalArgumentException("VLESS domain is too long: " + host);
        }
        out.write(0x02);
        out.write(domain.length);
        out.write(domain, 0, domain.length);
    }

    private byte[] addonBytes(VlessFlow flow) {
        if (flow == VlessFlow.NONE) {
            return new byte[] {0};
        }
        byte[] flowName = flow.wireName().getBytes(StandardCharsets.US_ASCII);
        if (flowName.length > 127) {
            throw new IllegalArgumentException("VLESS flow name is too long: " + flow.wireName());
        }

        ByteArrayOutputStream proto = new ByteArrayOutputStream();
        proto.write(0x0a);
        proto.write(flowName.length);
        proto.write(flowName, 0, flowName.length);

        byte[] protoBytes = proto.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(protoBytes.length);
        out.write(protoBytes, 0, protoBytes.length);
        return out.toByteArray();
    }

    private byte[] uuidBytes(VlessRequest request) {
        byte[] bytes = new byte[16];
        long most = request.uuid().getMostSignificantBits();
        long least = request.uuid().getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - i * 8));
            bytes[8 + i] = (byte) (least >>> (56 - i * 8));
        }
        return bytes;
    }
}
