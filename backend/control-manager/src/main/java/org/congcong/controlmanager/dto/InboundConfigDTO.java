package org.congcong.controlmanager.dto;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入站配置DTO
 */
@Data
public class InboundConfigDTO {
    private Long id;
    private String name;
    private ProtocolType protocol;
    private String listenIp;
    private Integer port;
    private Boolean tlsEnabled;
    private Boolean sniffEnabled;
    private String ssMethod;
    private List<Long> allowedUserIds;
    private List<Long> routeIds;
    private Integer status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // 扩展字段，用于列表展示
    private Integer userCount;
    private List<String> routeNames;
}