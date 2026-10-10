package org.congcong.controlmanager.service.mail.inspection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class InspectionFormats {
    private InspectionFormats() { }

    public static String text(Object value) {
        return value == null || value.toString().isBlank() ? "未知" : value.toString();
    }

    public static BigInteger integer(Object value) {
        return value == null ? BigInteger.ZERO : new BigInteger(value.toString());
    }

    public static String bytes(BigInteger value) {
        if (value == null) return "未提供";
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        BigDecimal scaled = new BigDecimal(value);
        int unit = 0;
        while (scaled.compareTo(BigDecimal.valueOf(1024)) >= 0 && unit < units.length - 1) {
            scaled = scaled.divide(BigDecimal.valueOf(1024));
            unit++;
        }
        return scaled.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + units[unit]
                + "（" + value + " B）";
    }

    public static String rate(BigInteger failures, BigInteger attempts) {
        return attempts.signum() == 0 ? "不适用（无有效尝试）"
                : new BigDecimal(failures).multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(attempts), 2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String milliseconds(Object micros, Object samples) {
        if (integer(samples).signum() == 0 || micros == null) return "未测量";
        return new BigDecimal(micros.toString()).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)
                .toPlainString() + " ms（有效样本 " + samples + "）";
    }

    public static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant time) return time;
        if (value instanceof Timestamp time) return time.toInstant();
        if (value instanceof LocalDateTime time) return time.toInstant(java.time.ZoneOffset.UTC);
        String raw = value.toString();
        if (raw.contains("T")) return Instant.parse(raw);
        return LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
    }

    public static String time(Instant value, ZoneId zone) {
        return value == null ? "未知" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(zone));
    }
}
