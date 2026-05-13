package org.congcong.controlmanager.logstore;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogTimeZoneTest {

    @Test
    void convertsUtcTimestampFromStoreToShanghaiLocalTime() {
        Timestamp storedTs = Timestamp.from(Instant.parse("2026-05-13T06:19:52.650Z"));

        LocalDateTime displayTime = AccessLogTimeZone.toDisplayDateTime(storedTs);

        assertThat(displayTime).isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 19, 52, 650_000_000));
    }

    @Test
    void treatsLocalDateTimeFromUtcStoreAsUtcWallTime() {
        LocalDateTime storedUtcWallTime = LocalDateTime.of(2026, 5, 13, 6, 19, 52, 650_000_000);

        LocalDateTime displayTime = AccessLogTimeZone.toDisplayDateTime(storedUtcWallTime);

        assertThat(displayTime).isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 19, 52, 650_000_000));
    }

    @Test
    void convertsShanghaiLocalQueryTimeToUtcStorageInstant() {
        LocalDateTime displayTime = LocalDateTime.of(2026, 5, 13, 14, 19, 52, 650_000_000);

        Timestamp storageTs = AccessLogTimeZone.toStorageTimestamp(displayTime);

        assertThat(storageTs.toInstant()).isEqualTo(Instant.parse("2026-05-13T06:19:52.650Z"));
    }

    @Test
    void convertsEpochMillisToShanghaiLocalTimeWithoutChangingInstant() {
        Instant instant = Instant.parse("2026-05-13T06:19:52.650Z");

        LocalDateTime displayTime = AccessLogTimeZone.toDisplayDateTime(instant);
        Timestamp storageTs = AccessLogTimeZone.toStorageTimestamp(displayTime);

        assertThat(displayTime).isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 19, 52, 650_000_000));
        assertThat(storageTs.toInstant()).isEqualTo(instant);
    }
}
