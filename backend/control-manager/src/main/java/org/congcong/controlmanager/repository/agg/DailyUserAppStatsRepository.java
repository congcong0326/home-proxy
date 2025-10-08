package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.DailyUserAppStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyUserAppStatsRepository extends JpaRepository<DailyUserAppStats, Long> {
    Optional<DailyUserAppStats> findByDayDateAndUserIdAndTargetHost(LocalDate dayDate, Long userId, String targetHost);

    @Query("SELECT d FROM DailyUserAppStats d WHERE (:dayDate IS NULL OR d.dayDate = :dayDate) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyUserAppStats> findTopByDay(@Param("dayDate") LocalDate dayDate, @Param("orderBy") String orderBy, Pageable pageable);

    @Query("SELECT d FROM DailyUserAppStats d WHERE (:from IS NULL OR d.dayDate >= :from) AND (:to IS NULL OR d.dayDate <= :to) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyUserAppStats> findTopByDayRange(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("orderBy") String orderBy, Pageable pageable);

    List<DailyUserAppStats> findByDayDateBetween(LocalDate from, LocalDate to);
}