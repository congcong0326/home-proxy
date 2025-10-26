package org.congcong.controlmanager.repository.agg;

import org.congcong.controlmanager.dto.UserTrafficStatsDTO;
import org.congcong.controlmanager.entity.agg.MinuteTrafficStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MinuteTrafficStatsRepository extends JpaRepository<MinuteTrafficStats, Long> {

    // 查询指定时间范围内的全局流量趋势
    @Query("SELECT m FROM MinuteTrafficStats m WHERE m.userId IS NULL AND m.minuteTime BETWEEN :from AND :to ORDER BY m.minuteTime")
    List<MinuteTrafficStats> findGlobalTrafficTrend(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    
    // 查询指定用户在指定时间范围内的流量趋势
    @Query("SELECT m FROM MinuteTrafficStats m WHERE m.userId = :userId AND m.minuteTime BETWEEN :from AND :to ORDER BY m.minuteTime")
    List<MinuteTrafficStats> findUserTrafficTrend(@Param("userId") Long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 删除指定时间之前的数据（用于自动清理）
    @Modifying
    @Query("DELETE FROM MinuteTrafficStats m WHERE m.minuteTime < :before")
    int deleteByMinuteTimeBefore(@Param("before") LocalDateTime before);
    
    // 统计指定时间之前的记录数量
    @Query("SELECT COUNT(m) FROM MinuteTrafficStats m WHERE m.minuteTime < :before")
    long countByMinuteTimeBefore(@Param("before") LocalDateTime before);
    
    // 查询用户当天流量统计（关联用户表）
    @Query("SELECT new org.congcong.controlmanager.dto.UserTrafficStatsDTO(" +
           "m.userId, u.username, SUM(m.byteIn), SUM(m.byteOut)) " +
           "FROM MinuteTrafficStats m " +
           "JOIN User u ON m.userId = u.id " +
           "WHERE m.userId IS NOT NULL " +
           "AND m.minuteTime >= :dayStart AND m.minuteTime < :dayEnd " +
           "GROUP BY m.userId, u.username " +
           "ORDER BY SUM(m.byteIn + m.byteOut) DESC limit 10")
    List<UserTrafficStatsDTO> findDailyUserTrafficStats(@Param("dayStart") LocalDateTime dayStart, 
                                                        @Param("dayEnd") LocalDateTime dayEnd);
    
    // 查询用户本月流量统计（关联用户表）
    @Query("SELECT new org.congcong.controlmanager.dto.UserTrafficStatsDTO(" +
           "m.userId, u.username, SUM(m.byteIn), SUM(m.byteOut)) " +
           "FROM MinuteTrafficStats m " +
           "JOIN User u ON m.userId = u.id " +
           "WHERE m.userId IS NOT NULL " +
           "AND m.minuteTime >= :monthStart AND m.minuteTime < :monthEnd " +
           "GROUP BY m.userId, u.username " +
           "ORDER BY SUM(m.byteIn + m.byteOut) DESC")
    List<UserTrafficStatsDTO> findMonthlyUserTrafficStats(@Param("monthStart") LocalDateTime monthStart, 
                                                          @Param("monthEnd") LocalDateTime monthEnd);
}