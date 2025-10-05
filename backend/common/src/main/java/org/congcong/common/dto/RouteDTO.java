package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 路由DTO
 */
@Data
public class RouteDTO {
    
    private Long id;
    
    private String name;
    
    private List<RouteRule> rules;
    
    private RoutePolicy policy;
    
    private String outboundTag;
    
    private ProtocolType outboundProxyType;
    
    private String outboundProxyHost;
    
    private Integer outboundProxyPort;
    
    private String outboundProxyUsername;
    
    private String outboundProxyPassword;
    
    private Integer status;
    
    private String notes;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}