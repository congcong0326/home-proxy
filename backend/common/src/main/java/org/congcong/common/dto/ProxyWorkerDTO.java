package org.congcong.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProxyWorkerDTO {
    private String workerId;
    private String displayName;
    private Boolean enabled;
    private String description;
    private Boolean tokenPresent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private WorkerStatusSnapshot status;
}
