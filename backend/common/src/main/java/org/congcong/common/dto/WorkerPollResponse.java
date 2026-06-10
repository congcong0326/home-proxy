package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerPollResponse {
    private LocalDateTime serverTime;
    private Long nextPollIntervalMillis;
    private List<WorkerTaskDTO> tasks;
}
