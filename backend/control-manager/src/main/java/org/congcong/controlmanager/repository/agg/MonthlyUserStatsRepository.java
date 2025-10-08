package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.MonthlyUserStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface MonthlyUserStatsRepository extends JpaRepository<MonthlyUserStats, Long> {
    Optional<MonthlyUserStats> findByMonthDateAndUserId(LocalDate monthDate, Long userId);

    @Query("SELECT m FROM MonthlyUserStats m WHERE (:monthDate IS NULL OR m.monthDate = :monthDate) ORDER BY CASE WHEN :orderBy = 'requests' THEN m.requestsCount WHEN :orderBy = 'bytes' THEN (m.bytesIn + m.bytesOut) ELSE m.requestsCount END DESC")
    java.util.List<MonthlyUserStats> findTopByMonth(@Param("monthDate") LocalDate monthDate, @Param("orderBy") String orderBy, org.springframework.data.domain.Pageable pageable);
}