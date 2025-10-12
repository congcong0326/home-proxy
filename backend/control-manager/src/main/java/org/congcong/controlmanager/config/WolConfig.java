package org.congcong.controlmanager.config;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * WOL配置实体类
 */
@Data
@Entity
@Table(name = "wol_configs")
public class WolConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 64)
    private String name;
    
    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;
    
    @Column(name = "subnet_mask", nullable = false, length = 64)
    private String subnetMask = "255.255.255.255";
    
    @Column(name = "mac_address", nullable = false, length = 64)
    private String macAddress;
    
    @Column(name = "wol_port", nullable = false)
    private Integer wolPort = 9;
    
    @Column(nullable = false)
    private Integer status = 1; // 1-启用，0-禁用
    
    @Column(length = 255)
    private String notes;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * 检查设备是否启用
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
    
    /**
     * 启用设备
     */
    public void enable() {
        this.status = 1;
    }
    
    /**
     * 禁用设备
     */
    public void disable() {
        this.status = 0;
    }
}
