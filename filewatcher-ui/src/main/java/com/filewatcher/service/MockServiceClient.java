package com.filewatcher.service;

import com.filewatcher.model.CredentialConfig;
import com.filewatcher.model.CredentialInfo;
import com.filewatcher.model.Job;
import com.filewatcher.model.JobStatus;
import com.filewatcher.model.WatchJobConfig;
import com.filewatcher.state.AppState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Simulates the backend for local development / demos — fires the same
 * cadence of events as the HTML preview's setInterval loop. Swap this
 * for a real implementation (see WebSocketServiceClientSkeleton) once
 * the Monitoring Service is reachable; nothing else in the app changes.
 */
public class MockServiceClient implements ServiceClient {

    private final List<Consumer<ServiceEvent>> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final AppState state; // read-only: used to pick realistic job names/ids
    private Timeline timeline;
    private boolean connected = false;

    public MockServiceClient(AppState state) {
        this.state = state;
    }

    @Override
    public CompletableFuture<Void> connect() {
        connected = true;
        timeline = new Timeline(new KeyFrame(Duration.seconds(4.2), e -> emitRandomEvent()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        // No real HEALTH message in mock mode — simulate everything nominal
        // (except the not-yet-implemented Socket Service) rather than leaving
        // the dashboard's Health Overview stuck on "Checking…" forever.
        javafx.application.Platform.runLater(() -> {
            state.getStats().databaseHealthProperty().set("HEALTHY");
            state.getStats().schedulerHealthProperty().set("HEALTHY");
            state.getStats().monitoringServiceHealthProperty().set("HEALTHY");
            state.getStats().socketServiceHealthProperty().set("DISABLED");
            try {
                state.localHostProperty().set(java.net.InetAddress.getLocalHost().getHostName());
            } catch (Exception ignored) {
                // "localhost" (AppState's default) is a fine fallback
            }
            if (state.getCredentials().isEmpty()) {
                state.getCredentials().addAll(
                        new CredentialInfo("cred-1", "dcs01", 22, "dcs-svc-account", "demo-password", "SFTP", "2m ago", List.of("job-1")),
                        new CredentialInfo("cred-2", "bags-hub", 22, "bhs-svc-account", "demo-password", "SFTP", "5m ago", List.of("job-2"))
                );
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void disconnect() {
        connected = false;
        if (timeline != null) timeline.stop();
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void addListener(Consumer<ServiceEvent> listener) { listeners.add(listener); }

    @Override
    public void removeListener(Consumer<ServiceEvent> listener) { listeners.remove(listener); }

    @Override
    public CompletableFuture<Void> sendCommand(String jobId, JobCommand command) {
        // Simulate backend latency, then emit the resulting status change.
        return CompletableFuture.runAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            var job = state.findJob(jobId);
            if (job == null) return;
            switch (command) {
                case START -> emit(ServiceEvent.statusChanged(jobId, job.getName(), com.filewatcher.model.JobStatus.RUNNING));
                case STOP -> emit(ServiceEvent.statusChanged(jobId, job.getName(), com.filewatcher.model.JobStatus.STOPPED));
                case RESTART -> emit(ServiceEvent.statusChanged(jobId, job.getName(), com.filewatcher.model.JobStatus.RESTARTING));
                case DELETE -> javafx.application.Platform.runLater(() -> {
                    state.getJobs().remove(job);
                    state.recomputeRunningStoppedCounts();
                });
                case TEST_CONNECTION -> {
                    if (random.nextDouble() < 0.85) {
                        emit(ServiceEvent.transferCompleted(jobId, job.getName(), null));
                    } else {
                        emit(ServiceEvent.transferFailed(jobId, job.getName(), null, "Connection test failed: timed out"));
                    }
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> requestInitialSnapshot() {
        // In the mock, AppState.seedDemoData() already populated everything.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<JobSaveResult> addJob(WatchJobConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            String id = "job-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            Job job = new Job(id, config.name, describeType(config.protocol),
                    config.sourcePath, config.destPath, describeInterval(config.intervalSeconds),
                    describeCredential(config), JobStatus.RUNNING, 0, "—", "Polling source directory");
            job.setRawConfig(config.copy());
            javafx.application.Platform.runLater(() -> {
                state.getJobs().add(job);
                state.recomputeRunningStoppedCounts();
            });
            return JobSaveResult.ok(id);
        });
    }

    @Override
    public CompletableFuture<JobSaveResult> updateJob(String jobId, WatchJobConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            Job job = state.findJob(jobId);
            if (job == null) return JobSaveResult.failed("Job not found: " + jobId);
            javafx.application.Platform.runLater(() -> {
                job.nameProperty().set(config.name);
                job.typeProperty().set(describeType(config.protocol));
                job.sourcePathProperty().set(config.sourcePath);
                job.destPathProperty().set(config.destPath);
                job.pollingIntervalProperty().set(describeInterval(config.intervalSeconds));
                job.credentialProperty().set(describeCredential(config));
                job.setRawConfig(config.copy());
            });
            return JobSaveResult.ok(jobId);
        });
    }

    @Override
    public CompletableFuture<Void> requestCredentials() {
        return CompletableFuture.completedFuture(null); // already populated on connect()
    }

    @Override
    public CompletableFuture<JobSaveResult> addCredential(CredentialConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            String id = "cred-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            CredentialInfo info = new CredentialInfo(id, config.host, config.port, config.username,
                    config.password, config.protocol.name(), "—", List.of());
            javafx.application.Platform.runLater(() -> state.getCredentials().add(info));
            return JobSaveResult.ok(id);
        });
    }

    @Override
    public CompletableFuture<JobSaveResult> updateCredential(String credentialId, CredentialConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            CredentialInfo info = state.findCredential(credentialId);
            if (info == null) return JobSaveResult.failed("Credential not found: " + credentialId);
            javafx.application.Platform.runLater(() -> {
                info.setHost(config.host);
                info.setPort(config.port);
                info.setUsername(config.username);
                if (config.password != null && !config.password.isBlank()) info.setPassword(config.password);
                info.setProtocol(config.protocol.name());
            });
            return JobSaveResult.ok(credentialId);
        });
    }

    @Override
    public void deleteCredential(String credentialId) {
        javafx.application.Platform.runLater(() -> {
            CredentialInfo info = state.findCredential(credentialId);
            if (info != null) state.getCredentials().remove(info);
        });
    }

    @Override
    public CompletableFuture<List<com.filewatcher.model.TransferLogEntry>> requestLogs(LogsQuery query) {
        // No DB in mock mode -- simulate against the in-session live log
        // (state.getLogs()) instead. Good enough for exercising the Logs
        // page's UI without a backend; won't have anything from before this
        // session started, unlike the real WebSocketServiceClient.
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            List<com.filewatcher.model.TransferEvent> source = new ArrayList<>(state.getLogs());
            List<com.filewatcher.model.TransferLogEntry> results = new ArrayList<>();
            for (com.filewatcher.model.TransferEvent e : source) {
                if (query.jobId() != null) {
                    Job j = state.findJob(query.jobId());
                    if (j == null || !j.getName().equals(e.getJobName())) continue;
                }
                if (query.eventType() != null && !matchesEventType(query.eventType(), e)) continue;
                if (query.search() != null && !query.search().isBlank()) {
                    String needle = query.search().toLowerCase();
                    boolean hit = e.getJobName().toLowerCase().contains(needle)
                            || (e.getFilename() != null && e.getFilename().toLowerCase().contains(needle))
                            || (e.getMessage() != null && e.getMessage().toLowerCase().contains(needle));
                    if (!hit) continue;
                }
                Job j = state.findJobByName(e.getJobName()).orElse(null);
                results.add(new com.filewatcher.model.TransferLogEntry(
                        results.size(), j != null ? j.getId() : null, e.getJobName(),
                        "ok".equals(e.getStatus()) ? "TRANSFERRED" : "ERROR",
                        e.getMessage(), e.getFilename(), 0L, e.getTimestamp()));
                if (results.size() >= query.limit()) break;
            }
            return results;
        });
    }

    private static boolean matchesEventType(String filter, com.filewatcher.model.TransferEvent e) {
        boolean isTransferred = "ok".equals(e.getStatus());
        return switch (filter) {
            case "TRANSFERRED" -> isTransferred;
            case "ERROR" -> !isTransferred;
            default -> true;
        };
    }

    @Override
    public CompletableFuture<ConnectionTestResult> testRawConnection(String host, int port, String username, String password, boolean detectOs) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            if (host == null || host.isBlank()) return ConnectionTestResult.failed("Host is required");
            if (username == null || username.isBlank()) return ConnectionTestResult.failed("Username is required");
            // No real network in mock mode -- simulate success for anything plausible-looking,
            // and a plausible-looking detected OS if asked (there's nothing real to probe).
            return ConnectionTestResult.ok(detectOs ? "LINUX" : null);
        });
    }

    @Override
    public CompletableFuture<RemoteBrowseResult> browseRemote(String host, int port, String username, String password, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (host == null || host.isBlank()) {
                return new RemoteBrowseResult(path, List.of(), "Host is required");
            }
            // No real SFTP in mock mode -- a small canned filesystem so the
            // Browse dialog is still exercisable without a backend.
            String resolved = (path == null || path.isBlank()) ? "/home/" + (username == null ? "user" : username) : path;
            List<com.filewatcher.model.RemoteEntryInfo> entries = resolved.endsWith("/inbound")
                    ? List.of(new com.filewatcher.model.RemoteEntryInfo("2026-07", true),
                              new com.filewatcher.model.RemoteEntryInfo("manifest_8841.xml", false))
                    : List.of(new com.filewatcher.model.RemoteEntryInfo("inbound", true),
                              new com.filewatcher.model.RemoteEntryInfo("outbound", true),
                              new com.filewatcher.model.RemoteEntryInfo("archive", true));
            return new RemoteBrowseResult(resolved, entries, null);
        });
    }

    private static String describeType(com.filewatcher.model.Protocol protocol) {
        if (protocol == null) return "Watch";
        return switch (protocol) {
            case SFTP -> "SFTP Watch";
            case FTP -> "FTP Watch";
            case SCP -> "SCP Watch";
            case LOCAL -> "Local Watch";
        };
    }

    private static String describeInterval(int seconds) {
        if (seconds <= 0) return "—";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static String describeCredential(WatchJobConfig config) {
        if (config.sourceUser != null && !config.sourceUser.isBlank()) return config.sourceUser;
        if (config.destUser != null && !config.destUser.isBlank()) return config.destUser;
        return "none";
    }

    private void emitRandomEvent() {
        if (state.getJobs().isEmpty()) return;
        var job = state.getJobs().get(random.nextInt(state.getJobs().size()));
        double roll = random.nextDouble();
        ServiceEvent event;
        if (roll < 0.7) {
            event = ServiceEvent.transferCompleted(job.getId(), job.getName(),
                    "manifest_" + (1000 + random.nextInt(9000)) + ".xml");
        } else {
            event = ServiceEvent.transferFailed(job.getId(), job.getName(),
                    "manifest_" + (1000 + random.nextInt(9000)) + ".xml", "Connection reset by peer");
        }
        emit(event);
    }

    private void emit(ServiceEvent event) {
        for (var l : listeners) l.accept(event);
    }
}
