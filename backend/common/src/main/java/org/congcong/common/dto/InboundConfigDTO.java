package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;

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
    private ProxyEncAlgo ssMethod;
    private Integer status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // todo 将 allowedUserIds 与 routeIds 一对一绑定迁移为 inboundRouteBindingDTOs 的绑定关系，使得一个入站配置可以支持不同用户适配不同路由策略的效果
    @Deprecated
    private List<Long> allowedUserIds;
    @Deprecated
    private List<Long> routeIds;

    private List<InboundRouteBinding> inboundRouteBindings;

    // 扩展字段，用于列表展示
    private Integer userCount;
    private List<String> routeNames;
}