package com.filewatcher.state;

import com.filewatcher.model.*;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Optional;

/**
 * Single source of truth shared across all views (Dashboard, Services, Logs).
 * Views only ever read/bind to these ObservableLists/Properties — they never
 * mutate Job/log state directly except through ServiceClient commands, so
 * the UI always reflects what the backend confirms happened.
 */
public class AppState {

    private final ObservableList<Job> jobs = FXCollections.observableArrayList(
            j -> new javafx.beans.Observable[]{j.statusProperty(), j.filesTodayProperty(), j.lastTransferProperty()}
    );
    private final ObservableList<TransferEvent> logs = FXCollections.observableArrayList();
    private final ObservableList<ActivityEvent> activityFeed = FXCollections.observableArrayList();
    private final DashboardStats stats = new DashboardStats();
    private final ObjectProperty<Job> selectedJob = new SimpleObjectProperty<>(null);

    private static final int MAX_ACTIVITY_ITEMS = 8;

    public ObservableList<Job> getJobs() { return jobs; }
    public ObservableList<TransferEvent> getLogs() { return logs; }
    public ObservableList<ActivityEvent> getActivityFeed() { return activityFeed; }
    public DashboardStats getStats() { return stats; }
    public ObjectProperty<Job> selectedJobProperty() { return selectedJob; }

    public Job findJob(String jobId) {
        if (jobId == null) return null;
        return jobs.stream().filter(j -> jobId.equals(j.getId())).findFirst().orElse(null);
    }

    public Optional<Job> findJobByName(String name) {
        return jobs.stream().filter(j -> j.getName().equals(name)).findFirst();
    }

    public void pushActivity(ActivityEvent event) {
        activityFeed.add(0, event);
        while (activityFeed.size() > MAX_ACTIVITY_ITEMS) {
            activityFeed.remove(activityFeed.size() - 1);
        }
    }

    public void pushLog(TransferEvent event) {
        logs.add(0, event);
    }

    public void recomputeRunningStoppedCounts() {
        long running = jobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
        long stopped = jobs.stream().filter(j -> j.getStatus() == JobStatus.STOPPED).count();
        stats.runningJobsProperty().set((int) running);
        stats.stoppedJobsProperty().set((int) stopped);
        stats.activeConnectionsProperty().set((int) running);
    }

    /** Seeds the app with the same 7 demo jobs used in the HTML preview, so behavior matches exactly. */
    public static AppState seedDemoData() {
        AppState state = new AppState();
        state.getJobs().addAll(
            new Job("job-1", "PAX-Manifest-Sync", "SFTP Watch", "/export/pax/manifests", "sftp://dcs01/inbound",
                    "15s", "dcs-svc-account", JobStatus.RUNNING, 342, "12s ago", "Polling source directory"),
            new Job("job-2", "Baggage-EDI-Feed", "SFTP Watch", "/export/bhs/edi", "sftp://bags-hub/in",
                    "30s", "bhs-svc-account", JobStatus.RUNNING, 118, "44s ago", "Polling source directory"),
            new Job("job-3", "Cargo-Docs-Relay", "FTP Watch", "/cargo/outbound", "ftp://cargo-edge/docs",
                    "60s", "cargo-ftp-user", JobStatus.RESTARTING, 76, "3m ago", "Reconnecting to host…"),
            new Job("job-4", "Crew-Roster-Push", "SFTP Watch", "/ops/crew/rosters", "sftp://crewnet/rosters",
                    "5m", "crewnet-svc", JobStatus.RUNNING, 29, "6m ago", "Polling source directory"),
            new Job("job-5", "Weather-METAR-Pull", "HTTP Poll", "https://wx.feed/metar", "/data/wx/metar",
                    "2m", "none", JobStatus.STARTING, 0, "—", "Establishing connection…"),
            new Job("job-6", "Fuel-Ticket-Archive", "Local Watch", "/fuel/tickets/new", "/fuel/tickets/archive",
                    "10s", "none", JobStatus.STOPPED, 0, "2h ago", "Idle"),
            new Job("job-7", "Legacy-NOTAM-Sync", "SFTP Watch", "/notam/legacy", "sftp://notam-old/in",
                    "5m", "notam-legacy", JobStatus.DISABLED, 0, "—", "Disabled by operator")
        );

        state.getLogs().addAll(
            new TransferEvent("14:32:08", "PAX-Manifest-Sync", "manifest_8841.xml", "Transferred", "ok", "0.8s", "214 KB", "Completed successfully"),
            new TransferEvent("14:31:52", "Baggage-EDI-Feed", "bag_edi_0092.txt", "Transferred", "ok", "0.3s", "12 KB", "Completed successfully"),
            new TransferEvent("14:29:14", "Cargo-Docs-Relay", "awb_774123.pdf", "Failed", "failed", "12.4s", "—", "Connection reset by peer"),
            new TransferEvent("14:27:40", "Crew-Roster-Push", "roster_2026-07-01.csv", "Transferred", "ok", "1.1s", "88 KB", "Completed successfully"),
            new TransferEvent("14:20:03", "PAX-Manifest-Sync", "manifest_8840.xml", "Transferred", "ok", "0.7s", "201 KB", "Completed successfully")
        );

        state.getStats().runningJobsProperty().set(5);
        state.getStats().stoppedJobsProperty().set(1);
        state.getStats().transfersTodayProperty().set(1247);
        state.getStats().failedTransfersProperty().set(6);
        state.getStats().activeConnectionsProperty().set(5);

        return state;
    }
}
