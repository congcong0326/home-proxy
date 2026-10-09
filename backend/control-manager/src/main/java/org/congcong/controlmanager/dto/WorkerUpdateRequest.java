package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.enums.LogLevel;

@Data
public class WorkerUpdateRequest {
    @NotBlank
    @Size(max = 128)
    private String displayName;

    @Size(max = 255)
    private String description;
    private Boolean enabled;

    @Size(max = 512)
    private String managementUrl;

    /** Omit this field to retain the currently configured token. */
    @Size(max = 128)
    private String managementToken;
    private Boolean logReportingEnabled;
    /** Omit this field to retain the current runtime logging level. */
    private LogLevel logLevel;
}
