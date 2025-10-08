package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.DailySrcGeoStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailySrcGeoStatsRepository extends JpaRepository<DailySrcGeoStats, Long> {
    Optional<DailySrcGeoStats> findByDayDateAndSrcGeoCountryAndSrcGeoCity(LocalDate dayDate, String srcGeoCountry, String srcGeoCity);

    @Query("SELECT d FROM DailySrcGeoStats d WHERE (:dayDate IS NULL OR d.dayDate = :dayDate) ORDER BY d.requestsCount DESC")
    List<DailySrcGeoStats> findTopByDay(@Param("dayDate") LocalDate dayDate, Pageable pageable);

    @Query("SELECT d FROM DailySrcGeoStats d WHERE (:from IS NULL OR d.dayDate >= :from) AND (:to IS NULL OR d.dayDate <= :to) ORDER BY d.requestsCount DESC")
    List<DailySrcGeoStats> findTopByDayRange(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    List<DailySrcGeoStats> findByDayDateBetween(LocalDate from, LocalDate to);
}