package com.filewatchercommon.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight notification model shared between service and UI.
 * Replaces NotificationService.Notification as the wire type.
 */
public class NotificationMessage {

    private String        id;
    private String        jobId;
    private String        jobName;
    private String        message;
    private LocalDateTime timestamp;
    private boolean       read;

    public NotificationMessage() {}

    public NotificationMessage(String jobId, String jobName, String message) {
        this.id        = UUID.randomUUID().toString();
        this.jobId     = jobId;
        this.jobName   = jobName;
        this.message   = message;
        this.timestamp = LocalDateTime.now();
        this.read      = false;
    }

    public String        getId()        { return id; }
    public String        getJobId()     { return jobId; }
    public String        getJobName()   { return jobName; }
    public String        getMessage()   { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public boolean       isRead()       { return read; }
    public void          markRead()     { this.read = true; }
    public void          setId(String id)               { this.id = id; }
    public void          setJobId(String jobId)         { this.jobId = jobId; }
    public void          setJobName(String jobName)     { this.jobName = jobName; }
    public void          setMessage(String message)     { this.message = message; }
    public void          setTimestamp(LocalDateTime t)  { this.timestamp = t; }
    public void          setRead(boolean read)          { this.read = read; }
}
