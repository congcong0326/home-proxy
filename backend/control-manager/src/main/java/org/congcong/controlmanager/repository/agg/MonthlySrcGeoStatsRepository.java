package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.MonthlySrcGeoStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface MonthlySrcGeoStatsRepository extends JpaRepository<MonthlySrcGeoStats, Long> {
    Optional<MonthlySrcGeoStats> findByMonthDateAndSrcGeoCountryAndSrcGeoCity(LocalDate monthDate, String srcGeoCountry, String srcGeoCity);

    @Query("SELECT m FROM MonthlySrcGeoStats m WHERE (:monthDate IS NULL OR m.monthDate = :monthDate) ORDER BY m.requestsCount DESC")
    java.util.List<MonthlySrcGeoStats> findTopByMonth(@Param("monthDate") LocalDate monthDate, org.springframework.data.domain.Pageable pageable);
}