package com.filewatcher.model;

import javafx.beans.property.*;

/** Backing model for the 7 summary cards on the Dashboard (spec §7). */
public class DashboardStats {
    private final IntegerProperty runningJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty stoppedJobs = new SimpleIntegerProperty(0);
    private final IntegerProperty transfersToday = new SimpleIntegerProperty(0);
    private final IntegerProperty failedTransfers = new SimpleIntegerProperty(0);
    private final IntegerProperty activeConnections = new SimpleIntegerProperty(0);
    private final StringProperty schedulerStatus = new SimpleStringProperty("Running");
    private final StringProperty webSocketStatus = new SimpleStringProperty("Connected");

    public IntegerProperty runningJobsProperty() { return runningJobs; }
    public IntegerProperty stoppedJobsProperty() { return stoppedJobs; }
    public IntegerProperty transfersTodayProperty() { return transfersToday; }
    public IntegerProperty failedTransfersProperty() { return failedTransfers; }
    public IntegerProperty activeConnectionsProperty() { return activeConnections; }
    public StringProperty schedulerStatusProperty() { return schedulerStatus; }
    public StringProperty webSocketStatusProperty() { return webSocketStatus; }

    public void incrementTransfersToday() { transfersToday.set(transfersToday.get() + 1); }
    public void incrementFailedTransfers() { failedTransfers.set(failedTransfers.get() + 1); }
}
