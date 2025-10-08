package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agg_month_dst_geo_stats")
public class MonthlyDstGeoStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month_date", nullable = false)
    private LocalDate monthDate;

    @Column(name = "dst_geo_country", length = 64)
    private String dstGeoCountry;

    @Column(name = "dst_geo_city", length = 64)
    private String dstGeoCity;

    @Column(name = "requests_count", nullable = false)
    private Long requestsCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}