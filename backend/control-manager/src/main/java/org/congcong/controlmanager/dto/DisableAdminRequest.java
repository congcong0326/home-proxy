package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DisableAdminRequest {
    @NotNull
    private Long userId;
}