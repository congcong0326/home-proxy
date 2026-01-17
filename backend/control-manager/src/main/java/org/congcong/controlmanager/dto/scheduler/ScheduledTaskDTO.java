package org.congcong.controlmanager.dto.scheduler;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
public class ScheduledTaskDTO {
    private Long id;
    private String taskKey;
    private String taskType;
    private String bizKey;
    private String cronExpression;
    private String description;
    private boolean enabled;
    private LocalDateTime lastExecutedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Map<String, Object> config;
}
