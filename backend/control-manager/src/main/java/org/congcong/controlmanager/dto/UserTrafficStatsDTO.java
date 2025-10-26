package org.congcong.controlmanager.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 用户流量统计DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserTrafficStatsDTO {
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 上传字节数
     */
    private Long byteIn;
    
    /**
     * 下载字节数
     */
    private Long byteOut;
    
    /**
     * 总流量（上传+下载）
     */
    private Long totalBytes;
    
    /**
     * 统计时间段描述（如"2024-01-15"或"2024-01"）
     */
    private String period;
    
    /**
     * 构造函数，自动计算总流量
     */
    public UserTrafficStatsDTO(Long userId, String username, Long byteIn, Long byteOut, String period) {
        this.userId = userId;
        this.username = username;
        this.byteIn = byteIn != null ? byteIn : 0L;
        this.byteOut = byteOut != null ? byteOut : 0L;
        this.totalBytes = this.byteIn + this.byteOut;
        this.period = period;
    }
    
    /**
     * 用于JPQL查询结果的构造函数
     */
    public UserTrafficStatsDTO(Long userId, String username, Long byteIn, Long byteOut) {
        this(userId, username, byteIn, byteOut, null);
    }
}