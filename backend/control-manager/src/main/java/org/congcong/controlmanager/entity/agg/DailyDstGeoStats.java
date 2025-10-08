package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agg_day_dst_geo_stats")
public class DailyDstGeoStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "dst_geo_country", length = 64)
    private String dstGeoCountry;

    @Column(name = "dst_geo_city", length = 64)
    private String dstGeoCity;

    @Column(name = "requests_count", nullable = false)
    private Long requestsCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}