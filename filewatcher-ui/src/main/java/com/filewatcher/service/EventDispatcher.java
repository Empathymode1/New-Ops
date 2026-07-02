package com.filewatcher.service;

import com.filewatcher.model.*;
import com.filewatcher.state.AppState;
import javafx.application.Platform;

import java.time.LocalTime;
import java.util.function.BiConsumer;

/**
 * Bridges ServiceClient -> AppState -> JavaFX Controls.
 *
 * Every inbound ServiceEvent is guaranteed to be applied on the JavaFX
 * Application Thread via Platform.runLater, per spec §14
 * (Monitoring Service -> WebSocket -> ServiceClient -> Event Dispatcher ->
 * Observable Models -> JavaFX Controls). Views never talk to ServiceClient
 * directly; they only ever observe AppState's ObservableLists/Properties.
 */
public class EventDispatcher {

    private final AppState state;
    private BiConsumer<String, Boolean> toastHandler = (msg, err) -> {};

    public EventDispatcher(AppState state, ServiceClient client) {
        this.state = state;
        client.addListener(this::onEvent);
    }

    /** UI hooks this to show toast notifications (spec §12) without EventDispatcher depending on any Node. */
    public void setToastHandler(BiConsumer<String, Boolean> handler) {
        this.toastHandler = handler;
    }

    private void onEvent(ServiceEvent event) {
        Platform.runLater(() -> apply(event));
    }

    private void apply(ServiceEvent event) {
        Job job = state.findJob(event.jobId());

        switch (event.type()) {
            case HEARTBEAT -> {
                if (job != null) {
                    job.setLastTransfer("just now");
                }
            }
            case TRANSFER_COMPLETED -> {
                if (job != null) {
                    job.incrementFilesToday();
                    job.setLastTransfer("just now");
                    job.setCurrentActivity("Polling source directory");
                }
                state.getStats().incrementTransfersToday();
                state.pushActivity(new ActivityEvent(LocalTime.now(), event.jobName(),
                        "Transferred", event.jobName() + " transferred " + event.filename(), "green"));
                state.pushLog(new TransferEvent(nowLabel(), event.jobName(), event.filename(),
                        "Transferred", "ok", "0.8s", "—", "Completed successfully"));
                toastHandler.accept("Transfer Completed — " + event.jobName(), false);
            }
            case TRANSFER_FAILED -> {
                state.getStats().incrementFailedTransfers();
                if (job != null) job.setLastError(event.message());
                state.pushActivity(new ActivityEvent(LocalTime.now(), event.jobName(),
                        "Failed", event.jobName() + " transfer failed — " + event.message(), "red"));
                state.pushLog(new TransferEvent(nowLabel(), event.jobName(), event.filename(),
                        "Failed", "failed", "—", "—", event.message()));
                toastHandler.accept("Transfer Failed — " + event.jobName(), true);
            }
            case SERVICE_STARTED -> {
                if (job != null) job.setStatus(com.filewatcher.model.JobStatus.RUNNING);
                state.pushActivity(new ActivityEvent(LocalTime.now(), event.jobName(),
                        "Started", event.jobName() + " service started", "blue"));
            }
            case SERVICE_STOPPED -> {
                if (job != null) job.setStatus(com.filewatcher.model.JobStatus.STOPPED);
                state.pushActivity(new ActivityEvent(LocalTime.now(), event.jobName(),
                        "Stopped", event.jobName() + " service stopped", "gray"));
            }
            case SERVICE_STATUS_CHANGED -> {
                if (job != null && event.newStatus() != null) job.setStatus(event.newStatus());
                state.recomputeRunningStoppedCounts();
            }
            case CONNECTION_LOST -> state.getStats().webSocketStatusProperty().set("Reconnecting…");
            case CONNECTION_RESTORED -> state.getStats().webSocketStatusProperty().set("Connected");
        }
    }

    private String nowLabel() {
        return LocalTime.now().withNano(0).toString();
    }
}
