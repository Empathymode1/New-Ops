package com.filewatcher.model;

import javafx.beans.property.*;

/**
 * A Watch Job row. Backed by JavaFX properties so TableView columns,
 * the Dashboard live table, and the Details Panel all stay in sync
 * automatically when a ServiceEvent updates a job.
 */
public class Job {
    private final StringProperty id = new SimpleStringProperty(this, "id");
    private final StringProperty name = new SimpleStringProperty(this, "name");
    private final StringProperty type = new SimpleStringProperty(this, "type");
    private final StringProperty sourcePath = new SimpleStringProperty(this, "sourcePath");
    private final StringProperty destPath = new SimpleStringProperty(this, "destPath");
    private final StringProperty pollingInterval = new SimpleStringProperty(this, "pollingInterval");
    private final StringProperty credential = new SimpleStringProperty(this, "credential");
    private final ObjectProperty<JobStatus> status = new SimpleObjectProperty<>(this, "status");
    private final IntegerProperty filesToday = new SimpleIntegerProperty(this, "filesToday");
    private final StringProperty lastTransfer = new SimpleStringProperty(this, "lastTransfer");
    private final StringProperty currentActivity = new SimpleStringProperty(this, "currentActivity");
    private final StringProperty lastError = new SimpleStringProperty(this, "lastError", "");

    public Job(String id, String name, String type, String sourcePath, String destPath,
               String pollingInterval, String credential, JobStatus status,
               int filesToday, String lastTransfer, String currentActivity) {
        this.id.set(id);
        this.name.set(name);
        this.type.set(type);
        this.sourcePath.set(sourcePath);
        this.destPath.set(destPath);
        this.pollingInterval.set(pollingInterval);
        this.credential.set(credential);
        this.status.set(status);
        this.filesToday.set(filesToday);
        this.lastTransfer.set(lastTransfer);
        this.currentActivity.set(currentActivity);
    }

    public StringProperty idProperty() { return id; }
    public StringProperty nameProperty() { return name; }
    public StringProperty typeProperty() { return type; }
    public StringProperty sourcePathProperty() { return sourcePath; }
    public StringProperty destPathProperty() { return destPath; }
    public StringProperty pollingIntervalProperty() { return pollingInterval; }
    public StringProperty credentialProperty() { return credential; }
    public ObjectProperty<JobStatus> statusProperty() { return status; }
    public IntegerProperty filesTodayProperty() { return filesToday; }
    public StringProperty lastTransferProperty() { return lastTransfer; }
    public StringProperty currentActivityProperty() { return currentActivity; }
    public StringProperty lastErrorProperty() { return lastError; }

    public String getId() { return id.get(); }
    public String getName() { return name.get(); }
    public JobStatus getStatus() { return status.get(); }
    public void setStatus(JobStatus s) { status.set(s); }
    public void setLastError(String e) { lastError.set(e); }
    public void setCurrentActivity(String a) { currentActivity.set(a); }
    public void incrementFilesToday() { filesToday.set(filesToday.get() + 1); }
    public void setLastTransfer(String s) { lastTransfer.set(s); }
}
