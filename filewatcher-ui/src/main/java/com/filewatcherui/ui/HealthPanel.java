package com.filewatcherui.ui;

import com.filewatchercommon.util.FileUtils;
import com.filewatcherui.service.ServiceClient;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class HealthPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private final ServiceClient client;
    private final BorderPane root = new BorderPane();
    private Timeline autoRefresh;

    private final Label connStatusLabel  = valueLabel("—");
    private final Label uptimeLabel      = valueLabel("—");
    private final Label memUsedLabel     = valueLabel("—");
    private final Label memMaxLabel      = valueLabel("—");
    private final Label cpuLabel         = valueLabel("—");
    private final Label activeJobsLabel  = valueLabel("—");
    private final Label totalJobsLabel   = valueLabel("—");
    private final Label totalFilesLabel  = valueLabel("—");
    private final Label totalBytesLabel  = valueLabel("—");
    private final Label lastErrorLabel   = valueLabel("—");
    private final Label lastRefreshLabel = valueLabel("—");

    public HealthPanel(ServiceClient client) {
        this.client = client;
        root.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");
        root.setTop(buildHeader());
        root.setCenter(buildContent());

        client.addHealthListener(stats ->
                Platform.runLater(() -> updateStats(stats)));

        client.addConnectListener(() -> Platform.runLater(() -> {
            connStatusLabel.setText("Connected");
            connStatusLabel.setStyle("-fx-text-fill: " + Theme.SUCCESS +
                    "; -fx-font-weight: bold;");
            requestHealth();
        }));

        client.addDisconnectListener(() -> Platform.runLater(() -> {
            connStatusLabel.setText("Disconnected");
            connStatusLabel.setStyle("-fx-text-fill: " + Theme.DANGER +
                    "; -fx-font-weight: bold;");
            lastRefreshLabel.setText(LocalDateTime.now().format(FMT));
        }));

        autoRefresh = new Timeline(new KeyFrame(
                Duration.seconds(30), e -> requestHealth()));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();

        if (client.isOpen()) requestHealth();
    }

    public Region getRoot() { return root; }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 1 0;");
        header.setPadding(new Insets(14, 20, 14, 20));

        Label title = new Label("Service Health");
        title.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = styledBtn("Refresh now");
        refresh.setOnAction(e -> requestHealth());

        header.getChildren().addAll(title, spacer, refresh);
        return header;
    }

    // ── Content ───────────────────────────────────────────────────────────

    private ScrollPane buildContent() {
        VBox content = new VBox(24);
        content.setPadding(new Insets(24));
        content.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");

        content.getChildren().addAll(
                sectionLabel("Connection"),
                card(buildGrid(new String[][]{
                        {"WebSocket",    "ws://localhost:9876"},
                        {"Status",       null},
                        {"Last refresh", null}
                }, new Label[]{null, connStatusLabel, lastRefreshLabel})),

                sectionLabel("Service"),
                card(buildGrid(new String[][]{
                        {"Uptime",    null},
                        {"CPU usage", null}
                }, new Label[]{uptimeLabel, cpuLabel})),

                sectionLabel("Memory"),
                card(buildGrid(new String[][]{
                        {"Heap used", null},
                        {"Heap max",  null}
                }, new Label[]{memUsedLabel, memMaxLabel})),

                sectionLabel("Jobs"),
                card(buildGrid(new String[][]{
                        {"Active jobs",       null},
                        {"Total jobs",        null},
                        {"Files transferred", null},
                        {"Bytes transferred", null}
                }, new Label[]{
                        activeJobsLabel, totalJobsLabel,
                        totalFilesLabel, totalBytesLabel})),

                sectionLabel("Last Error"),
                lastErrorLabel
        );

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_BASE + ";" +
                "-fx-background: " + Theme.BG_BASE + ";");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    // ── Grid ──────────────────────────────────────────────────────────────

    private GridPane buildGrid(String[][] rows, Label[] dynamic) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints key = new ColumnConstraints(140);
        ColumnConstraints val = new ColumnConstraints();
        val.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(key, val);

        for (int i = 0; i < rows.length; i++) {
            Label k = new Label(rows[i][0]);
            k.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
            grid.add(k, 0, i);
            if (dynamic[i] != null) {
                grid.add(dynamic[i], 1, i);
            } else {
                Label v = valueLabel(rows[i][1] != null ? rows[i][1] : "—");
                grid.add(v, 1, i);
            }
        }
        return grid;
    }

    // ── Update stats ──────────────────────────────────────────────────────

    private void updateStats(JsonObject stats) {
        connStatusLabel.setText("Connected");
        connStatusLabel.setStyle("-fx-text-fill: " + Theme.SUCCESS +
                "; -fx-font-weight: bold;");
        lastRefreshLabel.setText(LocalDateTime.now().format(FMT));

        long uptimeMs = stats.get("uptimeMs").getAsLong();
        uptimeLabel.setText(formatUptime(uptimeMs));

        double cpu = stats.get("cpuPercent").getAsDouble();
        cpuLabel.setText(String.format("%.1f%%", cpu));
        cpuLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " +
                (cpu > 80 ? Theme.DANGER : cpu > 50 ? Theme.WARNING : Theme.SUCCESS) + ";");

        long heapUsed = stats.get("heapUsedBytes").getAsLong();
        long heapMax  = stats.get("heapMaxBytes").getAsLong();
        memUsedLabel.setText(FileUtils.formatBytes(heapUsed));
        memMaxLabel.setText(FileUtils.formatBytes(heapMax));

        double memPct = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;
        memUsedLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " +
                (memPct > 80 ? Theme.DANGER : memPct > 60
                        ? Theme.WARNING : Theme.TEXT_PRIMARY) + ";");

        activeJobsLabel.setText(stats.get("activeJobs").getAsString()
                + " / " + stats.get("totalJobs").getAsString());
        totalFilesLabel.setText(stats.get("totalTransfers").getAsString());
        totalBytesLabel.setText(FileUtils.formatBytes(
                stats.get("totalBytes").getAsLong()));

        if (stats.has("lastError") && !stats.get("lastError").isJsonNull()) {
            lastErrorLabel.setText(stats.get("lastError").getAsString());
            lastErrorLabel.setStyle("-fx-text-fill: " + Theme.DANGER + ";");
        } else {
            lastErrorLabel.setText("None");
            lastErrorLabel.setStyle("-fx-text-fill: " + Theme.SUCCESS + ";");
        }
    }

    private void requestHealth() {
        if (client.isOpen()) {
            client.requestHealth();
        } else {
            connStatusLabel.setText("Disconnected");
            connStatusLabel.setStyle("-fx-text-fill: " + Theme.DANGER +
                    "; -fx-font-weight: bold;");
            lastRefreshLabel.setText(LocalDateTime.now().format(FMT));
        }
    }

    public void dispose() { if (autoRefresh != null) autoRefresh.stop(); }

    // ── Helpers ───────────────────────────────────────────────────────────

    private VBox card(GridPane grid) {
        VBox card = new VBox(grid);
        card.setStyle(Theme.cardStyle() + "-fx-padding: 14 16 14 16;");
        return card;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
                "-fx-font-size: 10;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_MUTED + ";" +
                        "-fx-padding: 0 0 6 0;");
        return l;
    }

    private Label valueLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        return l;
    }

    private Button styledBtn(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 6;" +
                        "-fx-background-radius: 6;" +
                        "-fx-padding: 6 14 6 14;" +
                        "-fx-cursor: hand;");
        return b;
    }

    private static String formatUptime(long ms) {
        long s = ms/1000, m = s/60, h = m/60, d = h/24;
        if (d > 0) return String.format("%dd %02dh %02dm", d, h%24, m%60);
        if (h > 0) return String.format("%dh %02dm %02ds", h, m%60, s%60);
        if (m > 0) return String.format("%dm %02ds", m, s%60);
        return s + "s";
    }
}