package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerTaskResultDTO {
    private Long taskId;
    private Boolean success;
    private String message;
    private LocalDateTime finishedAt;
}
