package org.congcong.controlmanager.logstore;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;

final class AccessLogTimeZone {
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId STORAGE_ZONE = ZoneOffset.UTC;

    private AccessLogTimeZone() {
    }

    static LocalDateTime toDisplayDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return LocalDateTime.ofInstant(timestamp.toInstant(), DISPLAY_ZONE);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return LocalDateTime.ofInstant(offsetDateTime.toInstant(), DISPLAY_ZONE);
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, DISPLAY_ZONE);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return LocalDateTime.ofInstant(localDateTime.atZone(STORAGE_ZONE).toInstant(), DISPLAY_ZONE);
        }
        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), DISPLAY_ZONE);
        }
        return null;
    }

    static Timestamp toStorageTimestamp(LocalDateTime displayDateTime) {
        if (displayDateTime == null) {
            return Timestamp.from(Instant.now());
        }
        return Timestamp.from(displayDateTime.atZone(DISPLAY_ZONE).toInstant());
    }
}
