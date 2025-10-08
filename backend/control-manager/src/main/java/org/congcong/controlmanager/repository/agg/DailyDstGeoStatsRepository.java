package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.DailyDstGeoStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyDstGeoStatsRepository extends JpaRepository<DailyDstGeoStats, Long> {
    Optional<DailyDstGeoStats> findByDayDateAndDstGeoCountryAndDstGeoCity(LocalDate dayDate, String dstGeoCountry, String dstGeoCity);

    @Query("SELECT d FROM DailyDstGeoStats d WHERE (:dayDate IS NULL OR d.dayDate = :dayDate) ORDER BY d.requestsCount DESC")
    List<DailyDstGeoStats> findTopByDay(@Param("dayDate") LocalDate dayDate, Pageable pageable);

    @Query("SELECT d FROM DailyDstGeoStats d WHERE (:from IS NULL OR d.dayDate >= :from) AND (:to IS NULL OR d.dayDate <= :to) ORDER BY d.requestsCount DESC")
    List<DailyDstGeoStats> findTopByDayRange(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    List<DailyDstGeoStats> findByDayDateBetween(LocalDate from, LocalDate to);
}