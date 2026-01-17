package org.congcong.controlmanager.dto.scheduler;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScheduledTaskToggleRequest {
    @NotNull
    private Boolean enabled;
}
