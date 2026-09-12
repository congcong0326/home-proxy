package org.congcong.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProxyWorkerDTO {
    private String workerId;
    private String displayName;
    private Boolean enabled;
    private Boolean logReportingEnabled;
    private String description;
    private String managementUrl;
    private String managementToken;
    private Boolean managementTokenPresent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private WorkerStatusSnapshot status;
}
