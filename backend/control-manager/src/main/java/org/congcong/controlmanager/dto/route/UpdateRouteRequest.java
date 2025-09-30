package org.congcong.controlmanager.dto.route;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.RouteRule;

import java.util.List;

/**
 * 更新路由请求
 */
@Data
public class UpdateRouteRequest {
    
    @Size(max = 64, message = "路由名称长度不能超过64个字符")
    private String name;
    
    private List<RouteRule> rules;
    
    private RoutePolicy policy;
    
    @Size(max = 64, message = "出站标签长度不能超过64个字符")
    private String outboundTag;
    
    private ProtocolType outboundProxyType;
    
    @Size(max = 255, message = "代理主机地址长度不能超过255个字符")
    private String outboundProxyHost;
    
    private Integer outboundProxyPort;
    
    @Size(max = 64, message = "代理用户名长度不能超过64个字符")
    private String outboundProxyUsername;
    
    @Size(max = 255, message = "代理密码长度不能超过255个字符")
    private String outboundProxyPassword;
    
    private Integer status;
    
    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String notes;
}