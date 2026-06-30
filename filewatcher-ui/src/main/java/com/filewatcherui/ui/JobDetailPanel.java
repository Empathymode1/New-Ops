package com.filewatcherui.ui;

import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.util.FileUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;

import java.time.format.DateTimeFormatter;

public class JobDetailPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WatchJob current;

    private final BorderPane root       = new BorderPane();
    private final Label      nameLabel  = new Label("Select a job");
    private final Label      dirLabel   = new Label("—");
    private final Circle     statusDot  = new Circle(6);

    private final Label filesLabel        = metricValue("—");
    private final Label bytesLabel        = metricValue("—");
    private final Label lastTransferLabel = metricValue("—");
    private final Label intervalLabel     = metricValue("—");

    private final Label srcLabel     = configValue("—");
    private final Label dstLabel     = configValue("—");
    private final Label modeLabel    = configValue("—");
    private final Label patternLabel = configValue("—");

    public JobDetailPanel() {
        root.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 0 1;");

        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";" +
                "-fx-background: " + Theme.BG_SURFACE + ";");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setCenter(scroll);

        showEmpty();
    }

    public Region getRoot() { return root; }

    // ── Content ───────────────────────────────────────────────────────────

    private VBox buildContent() {
        VBox content = new VBox(0);
        content.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        content.setPadding(new Insets(20));

        // Header
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        statusDot.setFill(javafx.scene.paint.Color.web(Theme.IDLE_CLR));

        nameLabel.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        titleRow.getChildren().addAll(statusDot, nameLabel);

        dirLabel.setStyle(
                "-fx-font-size: 11;" +
                        "-fx-text-fill: " + Theme.TEXT_MUTED + ";");

        VBox header = new VBox(4, titleRow, dirLabel);
        header.setMaxHeight(60);

        content.getChildren().addAll(
                header,
                vStrut(20),
                separator(),
                vStrut(16),
                sectionHeader("Transfer Stats"),
                vStrut(10),
                buildMetricCards(),
                vStrut(20),
                separator(),
                vStrut(16),
                sectionHeader("Configuration"),
                vStrut(10),
                buildConfigGrid()
        );

        return content;
    }

    private GridPane buildMetricCards() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(metricCard("Files transferred", filesLabel),        0, 0);
        grid.add(metricCard("Data transferred",  bytesLabel),        1, 0);
        grid.add(metricCard("Last transfer",      lastTransferLabel), 0, 1);
        grid.add(metricCard("Poll interval",      intervalLabel),     1, 1);
        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        grid.getColumnConstraints().addAll(cc, cc);
        return grid;
    }

    private GridPane buildConfigGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(8);
        addConfigRow(grid, "Source",      srcLabel,     0);
        addConfigRow(grid, "Destination", dstLabel,     1);
        addConfigRow(grid, "Mode",        modeLabel,    2);
        addConfigRow(grid, "Pattern",     patternLabel, 3);
        ColumnConstraints key = new ColumnConstraints(100);
        ColumnConstraints val = new ColumnConstraints();
        val.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(key, val);
        return grid;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void showJob(WatchJob job) {
        this.current = job;
        refresh();
    }

    public void refresh() {
        if (current == null) { showEmpty(); return; }

        nameLabel.setText(current.getName());
        statusDot.setFill(javafx.scene.paint.Color.web(
                Theme.statusFgColor(current.getStatus())));

        String dirTxt = switch (current.getDirection()) {
            case INBOUND        -> "INBOUND  |  " + current.getProtocol();
            case OUTBOUND       -> "OUTBOUND  |  " + current.getProtocol();
            case LOCAL_TO_LOCAL -> "LOCAL TO LOCAL";
        };
        dirLabel.setText(dirTxt);
        dirLabel.setStyle(
                "-fx-font-size: 11;" +
                        "-fx-text-fill: " + Theme.directionFgColor(current.getDirection()) + ";");

        filesLabel.setText(String.valueOf(current.getFilesTransferred()));
        bytesLabel.setText(FileUtils.formatBytes(current.getBytesTransferred()));
        lastTransferLabel.setText(current.getLastTransfer() != null
                ? current.getLastTransfer().format(FMT) : "—");
        intervalLabel.setText(current.getIntervalSeconds() + " s");

        boolean remote = current.getDirection() != WatchJob.Direction.LOCAL_TO_LOCAL;
        String src = remote && current.getSourceHost() != null
                ? current.getSourceHost() + ":" + current.getSourcePath()
                : nullSafe(current.getSourcePath());
        String dst = (current.getDirection() == WatchJob.Direction.INBOUND) || !remote
                ? nullSafe(current.getDestPath())
                : current.getDestHost() + ":" + current.getDestPath();

        srcLabel.setText(src.isEmpty() ? "—" : src);
        dstLabel.setText(dst.isEmpty() ? "—" : dst);
        modeLabel.setText(current.getTransferMode().name().replace("_", " "));
        patternLabel.setText(current.getSpecificPattern() == null
                || current.getSpecificPattern().isBlank()
                ? "* (all files)" : current.getSpecificPattern());
    }

    private void showEmpty() {
        nameLabel.setText("Select a job");
        statusDot.setFill(javafx.scene.paint.Color.web(Theme.IDLE_CLR));
        dirLabel.setText("—");
        filesLabel.setText("—");
        bytesLabel.setText("—");
        lastTransferLabel.setText("—");
        intervalLabel.setText("—");
        srcLabel.setText("—");
        dstLabel.setText("—");
        modeLabel.setText("—");
        patternLabel.setText("—");
    }

    // ── Component builders ────────────────────────────────────────────────

    private VBox metricCard(String labelText, Label value) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        VBox card = new VBox(6, lbl, value);
        card.setStyle(Theme.cardStyle() + "-fx-padding: 10 14 10 14;");
        return card;
    }

    private void addConfigRow(GridPane grid, String key, Label val, int row) {
        Label k = new Label(key);
        k.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        grid.add(k, 0, row);
        grid.add(val, 1, row);
    }

    private Label metricValue(String text) {
        Label l = new Label(text);
        l.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        return l;
    }

    private Label configValue(String text) {
        Label l = new Label(text);
        l.setStyle(
                "-fx-font-family: Consolas;" +
                        "-fx-font-size: 12;" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";");
        return l;
    }

    private Label sectionHeader(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
                "-fx-font-size: 10;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        return l;
    }

    private Separator separator() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return s;
    }

    private Region vStrut(double h) {
        Region r = new Region();
        r.setPrefHeight(h);
        return r;
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
}