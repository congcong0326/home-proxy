package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DistributionBucket {
    private String key;
    private Long count;
}