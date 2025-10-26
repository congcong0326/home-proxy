package org.congcong.controlmanager.entity.agg;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "agg_minute_traffic_stats", indexes = {
    @Index(name = "idx_minute_traffic_time", columnList = "minute_time"),
    @Index(name = "idx_minute_traffic_user_time", columnList = "user_id, minute_time")
})
public class MinuteTrafficStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "minute_time", nullable = false)
    private LocalDateTime minuteTime;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "byte_in", nullable = false)
    private Long byteIn;

    @Column(name = "byte_out", nullable = false)
    private Long byteOut;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 用于JPQL查询的构造函数
    public MinuteTrafficStats(LocalDateTime minuteTime, Long userId, Long byteIn, Long byteOut) {
        this.minuteTime = minuteTime;
        this.userId = userId;
        this.byteIn = byteIn;
        this.byteOut = byteOut;
        this.createdAt = LocalDateTime.now();
    }

    // 用于全局统计的构造函数（不区分用户）
    public MinuteTrafficStats(LocalDateTime minuteTime, Long byteIn, Long byteOut) {
        this.minuteTime = minuteTime;
        this.userId = null;
        this.byteIn = byteIn;
        this.byteOut = byteOut;
        this.createdAt = LocalDateTime.now();
    }
}