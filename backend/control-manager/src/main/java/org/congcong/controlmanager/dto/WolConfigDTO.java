package org.congcong.controlmanager.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WolConfigDTO {
    
    private Long id;
    
    private String name;
    
    private String ipAddress;
    
    private String subnetMask;
    
    private String macAddress;
    
    private Integer wolPort;
    
    private Integer status;
    
    private Boolean enabled;
    
    private String notes;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    // 运行时状态信息
    private Boolean online;
}