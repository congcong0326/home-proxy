package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class WolConfigUpdateRequest {
    
    @NotBlank(message = "设备名称不能为空")
    private String name;
    
    @NotBlank(message = "IP地址不能为空")
    @Pattern(regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$", 
             message = "IP地址格式不正确")
    private String ipAddress;
    
    @NotBlank(message = "子网掩码不能为空")
    @Pattern(regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$", 
             message = "子网掩码格式不正确")
    private String subnetMask;
    
    @NotBlank(message = "MAC地址不能为空")
    @Pattern(regexp = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", 
             message = "MAC地址格式不正确，支持格式：XX:XX:XX:XX:XX:XX 或 XX-XX-XX-XX-XX-XX")
    private String macAddress;
    
    @NotNull(message = "WOL端口不能为空")
    @Positive(message = "WOL端口必须为正数")
    private Integer wolPort;
    
    @NotNull(message = "状态不能为空")
    private Integer status;
    
    private String notes;
}