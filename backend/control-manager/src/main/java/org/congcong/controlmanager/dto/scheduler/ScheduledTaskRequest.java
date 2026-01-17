package org.congcong.controlmanager.dto.scheduler;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ScheduledTaskRequest {
    @NotBlank
    private String taskKey;

    @NotBlank
    private String taskType;

    private String bizKey;

    @NotBlank
    private String cronExpression;

    private String description;

    /**
     * 任务配置，可根据 taskType 解析。
     */
    private Map<String, Object> config;

    private Boolean enabled = Boolean.TRUE;
}
