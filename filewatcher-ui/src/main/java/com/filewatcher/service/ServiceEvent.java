package com.filewatcher.service;

import com.filewatcher.model.JobStatus;
import java.time.LocalTime;

/**
 * A single event coming off the wire from the Monitoring Service.
 * This is the contract your real backend needs to satisfy: whatever
 * transport you use (WebSocket, SSE, etc.), deserialize inbound
 * messages into instances of this class before handing them to the
 * EventDispatcher.
 */
public record ServiceEvent(
        ServiceEventType type,
        String jobId,
        String jobName,
        String filename,
        String message,
        JobStatus newStatus,
        LocalTime timestamp
) {
    public static ServiceEvent heartbeat(String jobId, String jobName) {
        return new ServiceEvent(ServiceEventType.HEARTBEAT, jobId, jobName, null, null, null, LocalTime.now());
    }

    public static ServiceEvent transferCompleted(String jobId, String jobName, String filename) {
        return new ServiceEvent(ServiceEventType.TRANSFER_COMPLETED, jobId, jobName, filename,
                "Completed successfully", null, LocalTime.now());
    }

    public static ServiceEvent transferFailed(String jobId, String jobName, String filename, String message) {
        return new ServiceEvent(ServiceEventType.TRANSFER_FAILED, jobId, jobName, filename,
                message, null, LocalTime.now());
    }

    public static ServiceEvent statusChanged(String jobId, String jobName, JobStatus status) {
        return new ServiceEvent(ServiceEventType.SERVICE_STATUS_CHANGED, jobId, jobName, null,
                null, status, LocalTime.now());
    }
}
