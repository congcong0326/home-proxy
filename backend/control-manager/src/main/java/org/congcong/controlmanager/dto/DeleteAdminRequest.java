package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除管理员请求DTO
 */
@Data
public class DeleteAdminRequest {
    
    @NotNull(message = "用户ID不能为空")
    private Long userId;

}