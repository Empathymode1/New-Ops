package com.filewatcher.ui.shell;

import com.filewatcher.state.AppState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Bottom status bar (spec §11): connection, scheduler, jobs, websocket, clock, version. */
public class StatusBar extends HBox {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Label clock = new Label();
    private final Label jobsLabel = new Label();

    public StatusBar(AppState state) {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(0, 16, 0, 16));
        setPrefHeight(28);

        Circle greenDot = new Circle(3.5);
        greenDot.getStyleClass().add("status-dot-green");
        Circle blueDot = new Circle(3.5);
        blueDot.getStyleClass().add("status-dot-blue");

        jobsLabel.getStyleClass().add("mono-label");
        state.getStats().runningJobsProperty().addListener((o, ov, nv) -> updateJobsLabel(state));
        state.getStats().stoppedJobsProperty().addListener((o, ov, nv) -> updateJobsLabel(state));
        updateJobsLabel(state);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        clock.getStyleClass().add("mono-label");

        getChildren().addAll(
                seg(greenDot, "Service Connected"),
                sep(),
                seg(null, "Scheduler: Running"),
                sep(),
                seg(null, "Jobs:"), jobsLabel,
                sep(),
                seg(blueDot, "WebSocket Live"),
                grow,
                clock,
                sep(),
                seg(null, "Relay v1.0.0")
        );

        Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickClock()));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();
        tickClock();
    }

    private void updateJobsLabel(AppState state) {
        int running = state.getStats().runningJobsProperty().get();
        int total = state.getJobs().size();
        jobsLabel.setText(running + "/" + total);
    }

    private void tickClock() {
        clock.setText(LocalTime.now().format(FMT));
    }

    private HBox seg(javafx.scene.Node icon, String text) {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        if (icon != null) box.getChildren().add(icon);
        box.getChildren().add(new Label(text));
        box.getStyleClass().add("status-seg");
        return box;
    }

    private Region sep() {
        Region r = new Region();
        r.getStyleClass().add("status-sep");
        r.setPrefWidth(1);
        r.setPrefHeight(12);
        return r;
    }
}
