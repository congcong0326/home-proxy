package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TimeSeriesPoint {
    private LocalDateTime ts;
    private Long byteIn;
    private Long byteOut;
}