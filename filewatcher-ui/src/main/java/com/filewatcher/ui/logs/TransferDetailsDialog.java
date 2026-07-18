package com.filewatcher.ui.logs;

import com.filewatcher.model.Job;
import com.filewatcher.model.TransferEvent;
import com.filewatcher.model.TransferLogEntry;
import com.filewatcher.service.LogsQuery;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Slide-over panel shown when a Logs row is double-clicked (spec §9).
 * Implemented as an overlay inside the root StackPane rather than a
 * separate Stage, so it animates in/out like the HTML preview's version.
 *
 * Timeline is built from REAL log entries (contract §2.9 LOGS_REQUEST),
 * not fabricated text — the previous version hardcoded "Watcher found new
 * file..." / "Connection opened..." for every single row regardless of
 * what actually happened. "Connected"/"Detected" steps are reconstructed
 * by finding the nearest matching log entry for the same job within a
 * few minutes of the event being viewed; if nothing matching is found
 * (the backend doesn't log every step for every event type — see
 * contract §1.2's table), the step honestly says "Not recorded" instead
 * of inventing a plausible-sounding description.
 */
public class TransferDetailsDialog extends StackPane {

    private static final int LOOKBACK_WINDOW_SECONDS = 300; // 5 minutes either side

    private final VBox panel = new VBox(16);
    private final Region backdrop = new Region();
    private final VBox timelineBox = new VBox(10);

    public TransferDetailsDialog() {
        setPickOnBounds(false);
        setMouseTransparent(true);
        setVisible(false);

        backdrop.getStyleClass().add("slideover-backdrop");
        backdrop.setOpacity(0);
        backdrop.setOnMouseClicked(e -> hide());

        panel.getStyleClass().add("slideover");
        panel.setPadding(new Insets(16));
        panel.setMaxWidth(380);
        panel.setPrefWidth(380);
        StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
        panel.setTranslateX(400);

        getChildren().addAll(backdrop, panel);
    }

    public void show(TransferEvent event, AppState state, ServiceClient client) {
        setMouseTransparent(false);
        setVisible(true);

        HBox header = new HBox();
        Label title = new Label("Transfer Details");
        title.getStyleClass().add("slideover-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("\u2715");
        close.getStyleClass().add("close-x");
        close.setOnAction(e -> hide());
        header.getChildren().addAll(title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        // Final step is already real data (this IS the event being viewed) --
        // shown immediately. "Connected"/"Detected" steps load asynchronously
        // below since they require a LOGS_REQUEST round-trip.
        timelineBox.getChildren().setAll(
                timelineItem("Loading\u2026", "", "Looking up related log entries\u2026"),
                finalTimelineItem(event)
        );

        Optional<Job> job = state.findJobByName(event.getJobName());

        VBox details = detailSection("Details",
                row("Job", event.getJobName()),
                row("Filename", event.getFilename()),
                row("Duration", event.getDuration()),
                row("File Size", event.getSize())
        );

        VBox pathInfo = detailSection("Source \u2192 Destination",
                row("Source", job.map(Job::sourcePathProperty).map(p -> p.get()).orElse("\u2014")),
                row("Destination", job.map(Job::destPathProperty).map(p -> p.get()).orElse("\u2014"))
        );

        panel.getChildren().setAll(header, timelineBox, details, pathInfo);

        backdrop.setMouseTransparent(false);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(200), backdrop);
        fade.setToValue(1);
        fade.play();

        TranslateTransition slide = new TranslateTransition(Duration.millis(240), panel);
        slide.setToX(0);
        slide.play();

        loadTimeline(event, job.map(Job::getId).orElse(event.getJobId()), client);
    }

    /** Fetches this job's recent log history and reconstructs the Connected/Detected steps from real entries. */
    private void loadTimeline(TransferEvent event, String jobId, ServiceClient client) {
        if (jobId == null) {
            timelineBox.getChildren().setAll(
                    timelineItem("Connected", "", "Not recorded"),
                    timelineItem("Detected", "", "Not recorded"),
                    finalTimelineItem(event));
            return;
        }

        LocalDateTime around = parseTimestamp(event.getTimestamp());

        client.requestLogs(new LogsQuery(jobId, null, null, null, 100))
                .thenAccept(entries -> Platform.runLater(() -> {
                    var connected = nearest(entries, "CONNECTED", null, around);
                    var detected = nearest(entries, "DETECTED", event.getFilename(), around);
                    timelineBox.getChildren().setAll(
                            connected.map(this::toTimelineItem).orElse(timelineItem("Connected", "", "Not recorded")),
                            detected.map(this::toTimelineItem).orElse(timelineItem("Detected", "", "Not recorded")),
                            finalTimelineItem(event));
                }));
    }

    /**
     * Finds the closest log entry of {@code eventType} to {@code around},
     * within {@link #LOOKBACK_WINDOW_SECONDS}, optionally matching
     * {@code filename} when one's given (DETECTED entries should belong to
     * the same file; CONNECTED entries aren't per-file, so pass null).
     */
    private Optional<TransferLogEntry> nearest(List<TransferLogEntry> entries, String eventType,
                                                String filename, LocalDateTime around) {
        return entries.stream()
                .filter(e -> eventType.equals(e.eventType()))
                .filter(e -> filename == null || filename.equals(e.filename()))
                .map(e -> Map.entry(e, secondsBetween(around, parseTimestamp(e.occurredAt()))))
                .filter(pair -> pair.getValue() <= LOOKBACK_WINDOW_SECONDS)
                .min(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey);
    }

    private static long secondsBetween(LocalDateTime a, LocalDateTime b) {
        if (a == null || b == null) return Long.MAX_VALUE;
        return Math.abs(java.time.Duration.between(a, b).getSeconds());
    }

    private static LocalDateTime parseTimestamp(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return null; // relative-time strings ("12s ago") from the live feed rather than an ISO timestamp
        }
    }

    private VBox toTimelineItem(TransferLogEntry entry) {
        String title = switch (entry.eventType() == null ? "" : entry.eventType()) {
            case "CONNECTED" -> "Connected";
            case "DETECTED" -> "Detected";
            default -> entry.eventType();
        };
        String desc = entry.message() != null && !entry.message().isBlank()
                ? entry.message()
                : (entry.filename() != null ? entry.filename() : title);
        return timelineItem(title, entry.occurredAt() != null ? entry.occurredAt() : "", desc);
    }

    /** The step this dialog was opened for -- always real, since it's the event being viewed. */
    private VBox finalTimelineItem(TransferEvent event) {
        String title = "ok".equals(event.getStatus()) ? "Completed" : "Failed";
        return timelineItem(title, event.getTimestamp(), event.getMessage());
    }

    public void hide() {
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), panel);
        slide.setToX(400);
        slide.setOnFinished(e -> { setVisible(false); setMouseTransparent(true); });
        slide.play();

        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(200), backdrop);
        fade.setToValue(0);
        fade.play();
    }

    private VBox timelineItem(String title, String time, String desc) {
        Label t = new Label(title);
        t.getStyleClass().add("timeline-title");
        Label ti = new Label(time);
        ti.getStyleClass().add("timeline-time");
        Label d = new Label(desc);
        d.setWrapText(true);
        d.getStyleClass().add("timeline-desc");
        VBox box = new VBox(2, t, ti, d);
        box.getStyleClass().add("timeline-item");
        return box;
    }

    private VBox detailSection(String heading, HBox... rows) {
        Label h = new Label(heading.toUpperCase());
        h.getStyleClass().add("dsection-heading");
        VBox box = new VBox(4, h);
        box.getChildren().addAll(rows);
        return box;
    }

    private HBox row(String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("drow-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label v = new Label(value);
        v.getStyleClass().add("drow-value");
        HBox box = new HBox(8, l, spacer, v);
        box.getStyleClass().add("drow");
        return box;
    }
}
