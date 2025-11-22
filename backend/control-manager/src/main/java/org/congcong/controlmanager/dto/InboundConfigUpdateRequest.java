package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;

import java.util.List;

/**
 * 更新入站配置请求DTO
 */
@Data
public class InboundConfigUpdateRequest {

    /**
     * 配置名称
     */
    @NotBlank(message = "配置名称不能为空")
    private String name;

    /**
     * 协议类型
     */
    @NotNull(message = "协议类型不能为空")
    private ProtocolType protocol;

    /**
     * 监听IP
     */
    @NotBlank(message = "监听IP不能为空")
    private String listenIp;

    /**
     * 端口
     */
    @NotNull(message = "端口不能为空")
    @Positive(message = "端口必须为正数")
    private Integer port;

    /**
     * 是否启用TLS
     */
    private Boolean tlsEnabled;

    /**
     * 是否启用嗅探
     */
    private Boolean sniffEnabled;

    /**
     * Shadowsocks加密方法
     */
    private ProxyEncAlgo ssMethod;

    /**
     * 允许的用户ID列表
     */
    private List<Long> allowedUserIds;

    /**
     * 路由ID列表
     */
    private List<Long> routeIds;

    @NotNull(message = "绑定数据不能为空")
    private List<InboundRouteBinding> inboundRouteBindings;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 备注
     */
    private String notes;
}