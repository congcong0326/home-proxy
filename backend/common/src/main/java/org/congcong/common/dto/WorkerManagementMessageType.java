package org.congcong.common.dto;

/** Message types carried over the controller-initiated worker management WebSocket. */
public enum WorkerManagementMessageType {
    /** The worker asks the connected controller for its current aggregate configuration. */
    CONFIG_POLL,
    /** The controller returns a newer aggregate configuration. */
    CONFIG,
    /** The controller confirms that the worker's configuration hash is current. */
    CONFIG_UNCHANGED,
    /** Direct WOL wake command delivered over the management WebSocket. */
    WOL_WAKE
}
