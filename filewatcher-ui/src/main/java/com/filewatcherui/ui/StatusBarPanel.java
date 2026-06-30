package com.filewatcherui.ui;

import com.filewatchercommon.model.WatchJob;
import com.filewatcherui.service.ServiceClient;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StatusBarPanel {

    private static final DateTimeFormatter CLOCK_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<WatchJob> jobs = new CopyOnWriteArrayList<>();
    private final Label activeLabel   = statLabel("0");
    private final Label watchingLabel = statLabel("0");
    private final Label errorLabel    = statLabel("0");
    private final Label clockLabel    = statLabel("—");
    private final HBox  root          = new HBox();
    private       Timeline clock;

    public StatusBarPanel(ServiceClient client) {
        client.addJobListListener(received -> {
            jobs.clear(); jobs.addAll(received);
            Platform.runLater(this::refresh);
        });
        client.addJobStateListener(updated -> {
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).getId().equals(updated.getId())) {
                    jobs.set(i, updated);
                    Platform.runLater(this::refresh);
                    return;
                }
            }
            jobs.add(updated);
            Platform.runLater(this::refresh);
        });

        root.setPrefHeight(32);
        root.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 1 0 0 0;");

        HBox left = new HBox(4);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPadding(new Insets(0, 0, 0, 12));
        addStat(left, "Total jobs:", activeLabel);
        addDivider(left);
        addStat(left, "Watching:", watchingLabel);
        addDivider(left);
        addStat(left, "Errors:", errorLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox right = new HBox(6);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setPadding(new Insets(0, 12, 0, 0));
        Label clockIcon = new Label("~");
        clockIcon.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED +
                "; -fx-font-size: 11;");
        right.getChildren().addAll(clockIcon, clockLabel);

        root.getChildren().addAll(left, spacer, right);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            clockLabel.setText(LocalDateTime.now().format(CLOCK_FMT));
            refresh();
        }));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        refresh();
    }

    public Region getRoot() { return root; }

    public void refresh() {
        long watching = jobs.stream()
                .filter(j -> j.getStatus() == WatchJob.Status.WATCHING
                        || j.getStatus() == WatchJob.Status.TRANSFERRING)
                .count();
        long errors = jobs.stream()
                .filter(j -> j.getStatus() == WatchJob.Status.ERROR).count();

        activeLabel.setText(String.valueOf(jobs.size()));
        watchingLabel.setText(String.valueOf(watching));
        errorLabel.setText(String.valueOf(errors));
        errorLabel.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 11;" +
                        "-fx-text-fill: " + (errors > 0 ? Theme.DANGER : Theme.SUCCESS) + ";");
    }

    public void dispose() { clock.stop(); }

    private void addStat(HBox p, String key, Label val) {
        Label k = new Label(key + " ");
        k.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        p.getChildren().addAll(k, val);
    }

    private void addDivider(HBox p) {
        Label d = new Label("  |  ");
        d.setStyle("-fx-text-fill: " + Theme.BORDER_STRONG +
                "; -fx-font-size: 11;");
        p.getChildren().add(d);
    }

    private Label statLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11;" +
                "-fx-text-fill: " + Theme.SUCCESS + ";");
        return l;
    }
}