package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.MonthlyDstGeoStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface MonthlyDstGeoStatsRepository extends JpaRepository<MonthlyDstGeoStats, Long> {
    Optional<MonthlyDstGeoStats> findByMonthDateAndDstGeoCountryAndDstGeoCity(LocalDate monthDate, String dstGeoCountry, String dstGeoCity);

    @Query("SELECT m FROM MonthlyDstGeoStats m WHERE (:monthDate IS NULL OR m.monthDate = :monthDate) ORDER BY m.requestsCount DESC")
    java.util.List<MonthlyDstGeoStats> findTopByMonth(@Param("monthDate") LocalDate monthDate, org.springframework.data.domain.Pageable pageable);
}