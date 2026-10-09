package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.enums.LogLevel;

@Data
public class WorkerCreateRequest {
    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,127}$", message = "workerId 格式不正确")
    private String workerId;

    @NotBlank
    @Size(max = 128)
    private String displayName;

    @Size(max = 255)
    private String description;

    @NotBlank(message = "Worker WSS 地址不能为空")
    @Size(max = 512)
    private String managementUrl;

    @NotBlank(message = "Worker 管理 Token 不能为空")
    @Size(max = 128)
    private String managementToken;

    private Boolean logReportingEnabled = true;
    private LogLevel logLevel = LogLevel.INFO;
}
