package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Small envelope for the controller-to-worker WebSocket management channel.
 * The worker remains responsible for polling and applying a configuration;
 * the WebSocket only removes the need for it to reach a public controller URL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerManagementMessage {
    private WorkerManagementMessageType type;
    private String workerId;
    private String configHash;
    private AggregateConfigResponse config;
}
