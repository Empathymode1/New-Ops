package com.filewatcher.ui.logs;

import com.filewatcher.model.Job;
import com.filewatcher.model.TransferEvent;
import com.filewatcher.state.AppState;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.Optional;

/**
 * Slide-over panel shown when a Logs row is double-clicked (spec §9).
 * Implemented as an overlay inside the root StackPane rather than a
 * separate Stage, so it animates in/out like the HTML preview's version.
 */
public class TransferDetailsDialog extends StackPane {

    private final VBox panel = new VBox(16);
    private final Region backdrop = new Region();

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

    public void show(TransferEvent event, AppState state) {
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

        VBox timeline = new VBox(10,
                timelineItem("Detected", event.getTimestamp(), "Watcher found new file in source directory."),
                timelineItem("Transfer started", event.getTimestamp(), "Connection opened to destination host."),
                timelineItem("ok".equals(event.getStatus()) ? "Completed" : "Failed", event.getTimestamp(), event.getMessage())
        );

        Optional<Job> job = state.findJobByName(event.getJobName());

        VBox details = detailSection("Details",
                row("Job", event.getJobName()),
                row("Filename", event.getFilename()),
                row("Duration", event.getDuration()),
                row("File Size", event.getSize()),
                row("Retry Count", "Retried".equals(event.getEventType()) ? "2 / 3" : "0")
        );

        VBox pathInfo = detailSection("Source \u2192 Destination",
                row("Source", job.map(Job::sourcePathProperty).map(p -> p.get()).orElse("\u2014")),
                row("Destination", job.map(Job::destPathProperty).map(p -> p.get()).orElse("\u2014"))
        );

        panel.getChildren().setAll(header, timeline, details, pathInfo);

        backdrop.setMouseTransparent(false);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(200), backdrop);
        fade.setToValue(1);
        fade.play();

        TranslateTransition slide = new TranslateTransition(Duration.millis(240), panel);
        slide.setToX(0);
        slide.play();
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
