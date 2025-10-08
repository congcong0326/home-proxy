package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.MonthlyAppStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface MonthlyAppStatsRepository extends JpaRepository<MonthlyAppStats, Long> {
    Optional<MonthlyAppStats> findByMonthDateAndTargetHost(LocalDate monthDate, String targetHost);

    @Query("SELECT m FROM MonthlyAppStats m WHERE (:monthDate IS NULL OR m.monthDate = :monthDate) ORDER BY CASE WHEN :orderBy = 'requests' THEN m.requestsCount WHEN :orderBy = 'bytes' THEN (m.bytesIn + m.bytesOut) ELSE m.requestsCount END DESC")
    java.util.List<MonthlyAppStats> findTopByMonth(@Param("monthDate") LocalDate monthDate, @Param("orderBy") String orderBy, org.springframework.data.domain.Pageable pageable);
}