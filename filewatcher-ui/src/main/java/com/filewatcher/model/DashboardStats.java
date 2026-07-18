package com.filewatcher.model;

import javafx.beans.property.*;

/** Backing model for the 7 summary cards + Health Overview panel on the Dashboard (spec §7). */
public class DashboardStats {
    private final IntegerProperty runningJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty stoppedJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty transfersToday = new SimpleIntegerProperty(0);
    private final IntegerProperty failedTransfers = new SimpleIntegerProperty(0);
    private final IntegerProperty activeConnections = new SimpleIntegerProperty(0);
    private final StringProperty schedulerStatus = new SimpleStringProperty("Running");
    private final StringProperty webSocketStatus = new SimpleStringProperty("Connected");

    // Health Overview panel — populated from the backend's periodic HEALTH
    // message (contract §1.4), NOT hardcoded. Each is one of "HEALTHY",
    // "UNHEALTHY", or "DISABLED" (a subsystem that's intentionally not
    // running, e.g. the not-yet-implemented Socket Service — distinct from
    // "unhealthy", which means something that should be up isn't).
    // "UNKNOWN" until the first HEALTH message arrives (a few seconds after
    // connect), rather than defaulting to "healthy" and potentially lying
    // about a subsystem we haven't heard from yet. The WebSocket row doesn't
    // need its own property here — it's derived from webSocketStatusProperty
    // (already a live signal).
    private final StringProperty databaseHealth = new SimpleStringProperty("UNKNOWN");
    private final StringProperty schedulerHealth = new SimpleStringProperty("UNKNOWN");
    private final StringProperty monitoringServiceHealth = new SimpleStringProperty("UNKNOWN");
    private final StringProperty socketServiceHealth = new SimpleStringProperty("UNKNOWN");

    public IntegerProperty runningJobsProperty() { return runningJobs; }
    public IntegerProperty stoppedJobsProperty() { return stoppedJobs; }
    public IntegerProperty transfersTodayProperty() { return transfersToday; }
    public IntegerProperty failedTransfersProperty() { return failedTransfers; }
    public IntegerProperty activeConnectionsProperty() { return activeConnections; }
    public StringProperty schedulerStatusProperty() { return schedulerStatus; }
    public StringProperty webSocketStatusProperty() { return webSocketStatus; }
    public StringProperty databaseHealthProperty() { return databaseHealth; }
    public StringProperty schedulerHealthProperty() { return schedulerHealth; }
    public StringProperty monitoringServiceHealthProperty() { return monitoringServiceHealth; }
    public StringProperty socketServiceHealthProperty() { return socketServiceHealth; }

    public void incrementTransfersToday() { transfersToday.set(transfersToday.get() + 1); }
    public void incrementFailedTransfers() { failedTransfers.set(failedTransfers.get() + 1); }
}
