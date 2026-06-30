package com.filewatchercommon.model;

import java.time.LocalDateTime;

public class TransferEvent {

    public enum EventType { DETECTED, TRANSFERRED, SKIPPED, ERROR, CONNECTED, DISCONNECTED, STARTED, STOPPED }

    private final LocalDateTime timestamp;
    private final EventType type;
    private final String jobId;
    private final String jobName;
    private final String message;
    private final String fileName;
    private final long fileSize;

    public TransferEvent(String jobId, String jobName, EventType type, String message) {
        this(jobId, jobName, type, message, null, 0);
    }

    public TransferEvent(String jobId, String jobName, EventType type, String message, String fileName, long fileSize) {
        this.timestamp = LocalDateTime.now();
        this.jobId = jobId;
        this.jobName = jobName;
        this.type = type;
        this.message = message;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public EventType getType() { return type; }
    public String getJobId() { return jobId; }
    public String getJobName() { return jobName; }
    public String getMessage() { return message; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
}
