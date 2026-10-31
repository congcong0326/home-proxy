package org.congcong.controlmanager.dto.outbound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.controlmanager.enums.OutboundKind;

import java.util.Map;

/**
 * 创建／更新出站请求。内置出站（直连、阻断）不能通过接口创建或修改。
 */
@Data
public class OutboundRequest {

    @NotBlank(message = "出站名称不能为空")
    @Size(max = 64, message = "出站名称长度不能超过64个字符")
    private String name;

    @NotNull(message = "出站类别不能为空")
    private OutboundKind kind;

    @NotNull(message = "出站协议不能为空")
    private ProtocolType proxyType;

    @Size(max = 255, message = "主机地址长度不能超过255个字符")
    private String host;

    private Integer port;

    @Size(max = 64, message = "用户名长度不能超过64个字符")
    private String username;

    @Size(max = 255, message = "密码长度不能超过255个字符")
    private String password;

    private ProxyEncAlgo encAlgo;

    private Map<String, Object> config;

    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String notes;
}
