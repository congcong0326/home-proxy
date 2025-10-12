package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.entity.agg.DailyAppStats;
import org.congcong.controlmanager.entity.agg.DailyUserAppStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyAppStatsRepository extends JpaRepository<DailyAppStats, Long> {
    Optional<DailyAppStats> findByDayDateAndTargetHost(LocalDate dayDate, String targetHost);

    @Query("SELECT d FROM DailyAppStats d WHERE (:dayDate IS NULL OR d.dayDate = :dayDate) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyAppStats> findTopByDay(@Param("dayDate") LocalDate dayDate, @Param("orderBy") String orderBy, Pageable pageable);

    @Query("SELECT d FROM DailyAppStats d WHERE (:from IS NULL OR d.dayDate >= :from) AND (:to IS NULL OR d.dayDate <= :to) ORDER BY CASE WHEN :orderBy = 'requests' THEN d.requestsCount WHEN :orderBy = 'bytes' THEN (d.bytesIn + d.bytesOut) ELSE d.requestsCount END DESC")
    List<DailyAppStats> findTopByDayRange(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("orderBy") String orderBy, Pageable pageable);

    List<DailyAppStats> findByDayDateBetween(LocalDate from, LocalDate to);

    @Query("SELECT new org.congcong.controlmanager.entity.agg.DailyAppStats(d.dayDate, d.targetHost, SUM(d.requestsCount), SUM(d.bytesIn), SUM(d.bytesOut)) " +
           "FROM DailyUserAppStats d WHERE d.userId = :userId AND d.dayDate BETWEEN :from AND :to " +
           "GROUP BY d.dayDate, d.targetHost")
    List<DailyAppStats> findByUserIdAndDayDateBetween(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}