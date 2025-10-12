package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
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

    // 用于JPQL查询的构造函数
    public DailyAppStats(LocalDate dayDate, String targetHost, Long requestsCount, Long bytesIn, Long bytesOut) {
        this.dayDate = dayDate;
        this.targetHost = targetHost;
        this.requestsCount = requestsCount;
        this.bytesIn = bytesIn;
        this.bytesOut = bytesOut;
    }
}