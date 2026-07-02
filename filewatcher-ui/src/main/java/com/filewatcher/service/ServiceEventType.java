package com.filewatcher.service;

/** Event types dispatched from the Monitoring Service over WebSocket (spec §14). */
public enum ServiceEventType {
    TRANSFER_COMPLETED,
    TRANSFER_FAILED,
    SERVICE_STARTED,
    SERVICE_STOPPED,
    SERVICE_STATUS_CHANGED,
    HEARTBEAT,
    CONNECTION_LOST,
    CONNECTION_RESTORED
}
