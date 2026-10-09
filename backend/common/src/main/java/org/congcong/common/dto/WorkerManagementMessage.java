package org.congcong.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Envelope for the controller-to-worker WebSocket management channel. The
 * worker sends heartbeats and buffered logs through the same channel while the
 * controller returns configuration responses.
 */
@Data
@NoArgsConstructor
public class WorkerManagementMessage {
    private WorkerManagementMessageType type;
    private String workerId;
    private String configHash;
    private AggregateConfigResponse config;
    /** Optional status/control payload carried by CONFIG_POLL frames. */
    private WorkerManagementHeartbeat heartbeat;
    /** Access logs buffered by the worker and delivered over the management socket. */
    private List<AccessLog> accessLogs;
    /** Authentication logs buffered by the worker and delivered over the management socket. */
    private List<AuthLog> authLogs;
    private java.util.Map<String, Object> taskPayload;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer telemetryVersion;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private org.congcong.common.telemetry.TelemetryBatchV1 telemetryBatch;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private org.congcong.common.telemetry.TelemetryAckV1 telemetryAck;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String, Object> telemetryStatus;


    public WorkerManagementMessage(WorkerManagementMessageType type,
                                   String workerId,
                                   String configHash,
                                   AggregateConfigResponse config) {
        this(type, workerId, configHash, config, null, null, null);
    }

    public WorkerManagementMessage(WorkerManagementMessageType type,
                                   String workerId,
                                   String configHash,
                                   AggregateConfigResponse config,
                                   WorkerManagementHeartbeat heartbeat,
                                   List<AccessLog> accessLogs,
                                   List<AuthLog> authLogs) {
        this.type = type;
        this.workerId = workerId;
        this.configHash = configHash;
        this.config = config;
        this.heartbeat = heartbeat;
        this.accessLogs = accessLogs;
        this.authLogs = authLogs;
    }
}
