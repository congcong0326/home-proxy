package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    // 首次登录时 oldPassword 可为空
    @Size(min = 8, max = 64)
    private String oldPassword;

    @NotBlank
    @Size(min = 8, max = 64)
    private String newPassword;
}