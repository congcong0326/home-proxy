package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agg_month_user_stats")
public class MonthlyUserStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month_date", nullable = false)
    private LocalDate monthDate;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "requests_count", nullable = false)
    private Long requestsCount;

    @Column(name = "bytes_in", nullable = false)
    private Long bytesIn;

    @Column(name = "bytes_out", nullable = false)
    private Long bytesOut;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}