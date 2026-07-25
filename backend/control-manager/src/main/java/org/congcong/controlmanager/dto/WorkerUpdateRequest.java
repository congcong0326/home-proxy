package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkerUpdateRequest {
    @NotBlank
    @Size(max = 128)
    private String displayName;

    @Size(max = 255)
    private String description;
    private Boolean enabled;
}
