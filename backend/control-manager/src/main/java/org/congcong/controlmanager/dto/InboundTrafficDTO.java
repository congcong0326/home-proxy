package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入站流量统计（当前按月）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InboundTrafficDTO {
    private Long inboundId;
    private Long bytesIn;
    private Long bytesOut;
    private Long totalBytes;
    /**
     * 统计周期，例如 "2024-04"
     */
    private String period;

    public static InboundTrafficDTO of(Long inboundId, Long bytesIn, Long bytesOut, String period) {
        long safeIn = bytesIn == null ? 0L : bytesIn;
        long safeOut = bytesOut == null ? 0L : bytesOut;
        return new InboundTrafficDTO(inboundId, safeIn, safeOut, safeIn + safeOut, period);
    }
}
