package org.congcong.controlmanager.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Stores {@link Instant} as UTC {@code yyyy-MM-dd HH:mm:ss.SSSSSS} text.
 */
@Converter(autoApply = true)
public class SqliteInstantConverter implements AttributeConverter<Instant, String> {
    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : SqliteTimeFormat.format(attribute.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SqliteTimeFormat.parseLocal(dbData).toInstant(ZoneOffset.UTC);
    }
}
