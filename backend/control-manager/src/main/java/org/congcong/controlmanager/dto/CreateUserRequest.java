package org.congcong.controlmanager.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class CreateUserRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过64个字符")
    private String username;

    @Size(max = 255, message = "凭据长度不能超过255个字符")
    private String credential;

    private Integer status = 1; // 默认启用

    @Size(max = 255, message = "备注长度不能超过255个字符")
    private String remark;
}