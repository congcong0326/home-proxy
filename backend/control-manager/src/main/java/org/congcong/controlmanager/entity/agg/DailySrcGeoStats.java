package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agg_day_src_geo_stats")
public class DailySrcGeoStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "src_geo_country", length = 64)
    private String srcGeoCountry;

    @Column(name = "src_geo_city", length = 64)
    private String srcGeoCity;

    @Column(name = "requests_count", nullable = false)
    private Long requestsCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}