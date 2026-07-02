package com.filewatcher.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** A single row in the Logs page transfer table (spec §9). */
public class TransferEvent {
    private final StringProperty timestamp = new SimpleStringProperty();
    private final StringProperty jobName = new SimpleStringProperty();
    private final StringProperty filename = new SimpleStringProperty();
    private final StringProperty eventType = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty(); // "ok" | "failed"
    private final StringProperty duration = new SimpleStringProperty();
    private final StringProperty size = new SimpleStringProperty();
    private final StringProperty message = new SimpleStringProperty();

    public TransferEvent(String timestamp, String jobName, String filename, String eventType,
                          String status, String duration, String size, String message) {
        this.timestamp.set(timestamp);
        this.jobName.set(jobName);
        this.filename.set(filename);
        this.eventType.set(eventType);
        this.status.set(status);
        this.duration.set(duration);
        this.size.set(size);
        this.message.set(message);
    }

    public StringProperty timestampProperty() { return timestamp; }
    public StringProperty jobNameProperty() { return jobName; }
    public StringProperty filenameProperty() { return filename; }
    public StringProperty eventTypeProperty() { return eventType; }
    public StringProperty statusProperty() { return status; }
    public StringProperty durationProperty() { return duration; }
    public StringProperty sizeProperty() { return size; }
    public StringProperty messageProperty() { return message; }

    public String getJobName() { return jobName.get(); }
    public String getStatus() { return status.get(); }
    public String getMessage() { return message.get(); }
    public String getTimestamp() { return timestamp.get(); }
    public String getFilename() { return filename.get(); }
    public String getDuration() { return duration.get(); }
    public String getSize() { return size.get(); }
    public String getEventType() { return eventType.get(); }
}
