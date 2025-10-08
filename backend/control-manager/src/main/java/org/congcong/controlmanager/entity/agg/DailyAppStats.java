package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agg_day_app_stats")
public class DailyAppStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "target_host", nullable = false, length = 255)
    private String targetHost;

    @Column(name = "requests_count", nullable = false)
    private Long requestsCount;

    @Column(name = "bytes_in", nullable = false)
    private Long bytesIn;

    @Column(name = "bytes_out", nullable = false)
    private Long bytesOut;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}