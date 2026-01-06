package org.congcong.controlmanager.dto.mail;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ToggleStatusRequest {
    @NotNull
    private Boolean enabled;
}
