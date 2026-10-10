package org.congcong.common.telemetry;

import lombok.Data;

/** ACK releases the worker batch after manager memory admission, before CK persistence. */
@Data
public class TelemetryAckV1 {
    private int protocolVersion = 1;
    private String batchId;
    // Legacy wire name retained for existing workers; true now means accepted into manager memory.
    private boolean persisted;
    private String reasonCode;
}
