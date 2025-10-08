package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.DailyUserStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyUserStatsRepository extends JpaRepository<DailyUserStats, Long> {
    Optional<DailyUserStats> findByDayDateAndUserId(LocalDate dayDate, Long userId);

    @Query("SELECT d FROM DailyUserStats d WHERE (:dayDate IS NULL OR d.dayDate = :dayDate) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyUserStats> findTopByDay(@Param("dayDate") LocalDate dayDate, @Param("orderBy") String orderBy, Pageable pageable);

    @Query("SELECT d FROM DailyUserStats d WHERE (:from IS NULL OR d.dayDate >= :from) AND (:to IS NULL OR d.dayDate <= :to) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyUserStats> findTopByDayRange(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("orderBy") String orderBy, Pageable pageable);

    List<DailyUserStats> findByDayDateBetween(LocalDate from, LocalDate to);
}