package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WolWakeTaskPayload {
    private String deviceName;
    private String macAddress;
    private String broadcastIp;
    private Integer port;
}
