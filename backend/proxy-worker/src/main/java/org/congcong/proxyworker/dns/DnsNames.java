package org.congcong.proxyworker.dns;

import java.net.IDN;
import java.util.Locale;

/** DNS query/record names have broader syntax than TCP hostnames used for fake leases. */
public final class DnsNames {
    private DnsNames() {}

    /** Canonical ASCII name without the terminal dot; the root is represented by ".". */
    public static String normalize(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("DNS 名称不能为空");
        String ascii = IDN.toASCII(name).toLowerCase(Locale.ROOT);
        if (ascii.equals(".")) return ".";
        if (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
        int wireLength = 1; // root terminator
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalArgumentException("DNS 标签长度必须在 1 到 63 字节之间");
            }
            for (int index = 0; index < label.length(); index++) {
                char value = label.charAt(index);
                if (value <= 32 || value >= 127) {
                    throw new IllegalArgumentException("DNS 名称包含不支持的字符");
                }
            }
            wireLength += 1 + label.length();
        }
        if (wireLength > 255) throw new IllegalArgumentException("DNS 名称超过 255 字节");
        return ascii;
    }

    /** Only ordinary hostnames can be restored later as TCP connection destinations. */
    public static boolean isHostname(String normalizedName) {
        try {
            FakeIpRegistry.normalizeDomain(normalizedName);
            return true;
        } catch (IllegalArgumentException invalidHostname) {
            return false;
        }
    }
}
