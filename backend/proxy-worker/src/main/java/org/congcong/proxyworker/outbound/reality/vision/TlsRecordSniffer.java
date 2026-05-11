package org.congcong.proxyworker.outbound.reality.vision;

public final class TlsRecordSniffer {
    private static final byte[] TLS13_SUPPORTED_VERSIONS = new byte[] {0x00, 0x2b, 0x00, 0x02, 0x03, 0x04};

    public boolean looksLikeClientHello(byte[] bytes) {
        return bytes.length >= 6
                && (bytes[0] & 0xff) == 0x16
                && (bytes[1] & 0xff) == 0x03
                && (bytes[5] & 0xff) == 0x01;
    }

    public Result inspect(byte[] bytes) {
        boolean tls = false;
        boolean tls13 = false;
        boolean applicationData = false;
        boolean completeRecord = false;
        if (bytes.length >= 6) {
            int contentType = bytes[0] & 0xff;
            int majorVersion = bytes[1] & 0xff;
            int length = ((bytes[3] & 0xff) << 8) | (bytes[4] & 0xff);
            tls = (contentType == 0x16 || contentType == 0x17) && majorVersion == 0x03;
            applicationData = contentType == 0x17 && majorVersion == 0x03;
            completeRecord = applicationData ? completeApplicationDataRecords(bytes) : bytes.length >= 5 + length;
            tls13 = contentType == 0x16
                    && majorVersion == 0x03
                    && (bytes[5] & 0xff) == 0x02
                    && contains(bytes, TLS13_SUPPORTED_VERSIONS);
        }
        return new Result(tls, tls13, applicationData, completeRecord);
    }

    private boolean completeApplicationDataRecords(byte[] bytes) {
        int index = 0;
        while (index < bytes.length) {
            if (bytes.length - index < 5) {
                return false;
            }
            if ((bytes[index] & 0xff) != 0x17
                    || (bytes[index + 1] & 0xff) != 0x03
                    || (bytes[index + 2] & 0xff) != 0x03) {
                return false;
            }
            int length = ((bytes[index + 3] & 0xff) << 8) | (bytes[index + 4] & 0xff);
            if (bytes.length - index < 5 + length) {
                return false;
            }
            index += 5 + length;
        }
        return true;
    }

    private boolean contains(byte[] bytes, byte[] needle) {
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    public static final class Result {
        private final boolean tls;
        private final boolean tls13;
        private final boolean applicationData;
        private final boolean completeRecord;

        public Result(boolean tls, boolean tls13, boolean applicationData, boolean completeRecord) {
            this.tls = tls;
            this.tls13 = tls13;
            this.applicationData = applicationData;
            this.completeRecord = completeRecord;
        }

        public boolean tls() {
            return tls;
        }

        public boolean tls13() {
            return tls13;
        }

        public boolean applicationData() {
            return applicationData;
        }

        public boolean completeRecord() {
            return completeRecord;
        }
    }
}
