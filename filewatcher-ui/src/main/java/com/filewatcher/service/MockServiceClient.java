package com.filewatcher.service;

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
                case DELETE, TEST_CONNECTION -> { /* no-op in mock */ }
            }
        });
    }

    @Override
    public CompletableFuture<Void> requestInitialSnapshot() {
        // In the mock, AppState.seedDemoData() already populated everything.
        return CompletableFuture.completedFuture(null);
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
