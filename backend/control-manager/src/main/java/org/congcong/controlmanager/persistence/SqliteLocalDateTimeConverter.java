package org.congcong.controlmanager.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;

/**
 * Stores {@link LocalDateTime} as {@code yyyy-MM-dd HH:mm:ss.SSSSSS} text so lexicographic order equals time order.
 */
@Converter(autoApply = true)
public class SqliteLocalDateTimeConverter implements AttributeConverter<LocalDateTime, String> {
    @Override
    public String convertToDatabaseColumn(LocalDateTime attribute) {
        return attribute == null ? null : SqliteTimeFormat.format(attribute);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SqliteTimeFormat.parseLocal(dbData);
    }
}
