package org.congcong.controlmanager.persistence;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

final class SqliteTimeFormat {
    private static final DateTimeFormatter WRITER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    /** Lenient reader: seconds and fraction are optional so hand-written or imported values still load. */
    private static final DateTimeFormatter READER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':').appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalEnd()
            .toFormatter();

    private SqliteTimeFormat() {
    }

    static String format(LocalDateTime value) {
        return WRITER.format(value);
    }

    static LocalDateTime parseLocal(String text) {
        return LocalDateTime.parse(text.trim(), READER);
    }
}
