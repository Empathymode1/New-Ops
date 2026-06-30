package com.filewatchercommon.model;

import java.time.LocalDateTime;

/**
 * Wire-format DTO for a single transfer_logs row, sent Service → UI in
 * response to GET_LOGS. Mirrors TransferRepository.LogEntry on the service
 * side, but lives in filewatcher-common (like CredentialMessage does for
 * CredentialStore.Credential) so the UI module doesn't need a dependency
 * on the service module's database package just to deserialize a log row.
 */
public class LogEntryMessage {

    private long id;
    private String jobId;
    private String jobName;
    private String eventType;
    private String message;
    private String filename;
    private long sizeBytes;
    private LocalDateTime occurredAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}