package org.congcong.common.telemetry;

import lombok.Data;

/** ACK means all facts in this batch are durably stored, including replayed facts. */
@Data
public class TelemetryAckV1 {
    private int protocolVersion = 1;
    private String batchId;
    private boolean persisted;
    private String reasonCode;
}
