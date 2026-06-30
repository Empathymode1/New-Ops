package com.filewatcherui.ui;

import com.filewatchercommon.service.NotificationService;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.logging.Logger;

public class MainWindow {

    private  ServiceClient client;
    private final NotificationService notificationService = new NotificationService();

    private  JobTablePanel  jobTable;
    private  EventLogPanel  eventLog;
    private  JobDetailPanel detailPanel;
    private  StatusBarPanel statusBar;
    private  LogsPanel      logsPanel;
    private       NotificationPanel notificationPanel;
    private       Button            bellButton;
    private       Label             badgeLabel;
    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    public MainWindow(Stage stage) throws Exception {
        stage.setTitle("FileWatcher — Ops Monitor");
        stage.setWidth(1280);
        stage.setHeight(780);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

//
        // ── Connect WebSocket ─────────────────────────────────────────────
        client = new ServiceClient();

        // ── Build panels ──────────────────────────────────────────────────
        jobTable    = new JobTablePanel(client);
        eventLog    = new EventLogPanel();
        detailPanel = new JobDetailPanel();
        statusBar   = new StatusBarPanel(client);

        CredentialsPanel credentialsPanel = new CredentialsPanel(client);
        HealthPanel      healthPanel      = new HealthPanel(client);
        logsPanel = new LogsPanel(client);
        notificationPanel = new NotificationPanel(notificationService, client);
        notificationPanel.setVisible(false);

        // ── WebSocket listeners ───────────────────────────────────────────
        client.addJobListListener(jobs -> Platform.runLater(() -> {
            jobTable.setJobs(jobs);
            statusBar.refresh();
            logsPanel.setAvailableJobs(jobs);
        }));

        client.addJobStateListener(job -> Platform.runLater(() -> {
            jobTable.updateJob(job);
            statusBar.refresh();
            detailPanel.refresh();
        }));

        client.addEventListener(event -> Platform.runLater(() -> {
            eventLog.appendEvent(event);
            statusBar.refresh();
        }));

        client.addNotificationListener(n -> Platform.runLater(() ->
                notificationService.addError(n.getJobId(), n.getJobName(), n.getMessage())));

        client.addConnectListener(() -> Platform.runLater(() -> {
            credentialsPanel.loadFromService();
            logsPanel.refreshFromServer();
        }));

        notificationService.addListener(notifications -> Platform.runLater(() -> {
            long unread = notificationService.getUnreadCount();
            badgeLabel.setText(String.valueOf(unread));
            badgeLabel.setVisible(unread > 0);
            bellButton.setStyle(bellButton.getStyle() +
                    (!notificationPanel.isVisible() && unread > 0
                            ? "-fx-text-fill: #DC3545;"
                            : "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";"));
        }));

        // ── Selection ─────────────────────────────────────────────────────
        jobTable.setOnSelect(job -> {
            detailPanel.showJob(job);
            eventLog.setFilter(job.getId());
        });

        // ── Layout ────────────────────────────────────────────────────────
        SplitPane leftSplit = new SplitPane(jobTable.getRoot(), eventLog.getRoot());
        leftSplit.setDividerPositions(0.28);

        SplitPane mainSplit = new SplitPane(leftSplit, detailPanel.getRoot());
        mainSplit.setDividerPositions(0.72);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        Tab jobsTab   = new Tab("Watch Jobs",     mainSplit);
        Tab credsTab  = new Tab("Credentials",    credentialsPanel.getRoot());
        Tab healthTab = new Tab("Service Health", healthPanel.getRoot());
        Tab logsTab   = new Tab("Logs",           logsPanel.getRoot());
        tabs.getTabs().addAll(jobsTab, credsTab, healthTab, logsTab);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");
        root.setTop(buildTitleBar(stage));
        root.setCenter(tabs);
        root.setBottom(statusBar.getRoot());
        root.setRight(notificationPanel.getRoot());

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            try { client.closeBlocking(); } catch (Exception ignored) {}
            healthPanel.dispose();
            statusBar.dispose();
            Platform.exit();
        });

        stage.show();

        // Connect after UI is up
        new Thread(() -> {
            int attempts = 0;
            while (attempts < 10) {
                try {
                    LOG.info("Connect attempt " + (attempts + 1) + " to ws://localhost:9876...");
                    client.connectBlocking();
                    LOG.info("Connected successfully!");
                    return;
                } catch (Exception e) {
                    attempts++;
                    LOG.warning("Attempt " + attempts + " failed: " + e.getClass().getName() + " — " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
            Platform.runLater(() -> {
                showError("Cannot connect to FileWatcher service.\nMake sure the service is running.");
                stage.close();
            });
        }, "ws-connect").start();

    }

    // ── Title bar ─────────────────────────────────────────────────────────

    private HBox buildTitleBar(Stage stage) {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 16, 0, 16));
        bar.setPrefHeight(44);
        bar.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 1 0;");

        // Brand
        Label icon = new Label("FW");
        icon.setStyle(
                "-fx-font-size: 16;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.ACCENT + ";" +
                        "-fx-background-color: " + Theme.PILL_WATCH_BG + ";" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 2 6 2 6;");

        Label appName = new Label("FileWatcher");
        appName.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Label consoleBadge = new Label("OPS CONSOLE");
        consoleBadge.setStyle(
                "-fx-font-size: 9;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_MUTED + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 3;" +
                        "-fx-background-radius: 3;" +
                        "-fx-padding: 2 6 2 6;");

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Action buttons
        Button startAll = styledBtn("Start All", "success");
        Button stopAll  = styledBtn("Stop All",  "danger");
        Button newJob   = styledBtn("+ New Job", "primary");

        startAll.setOnAction(e -> client.startAll());
        stopAll.setOnAction(e  -> client.stopAll());
        newJob.setOnAction(e   -> new JobEditDialog(stage, client, null).show());

        // Bell + badge
        StackPane bellWrapper = new StackPane();
        bellWrapper.setPrefSize(36, 36);

        bellButton = new Button("A"); // custom drawn below
        bellButton.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-cursor: hand;" +
                        "-fx-font-size: 16;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        bellButton.setOnAction(e -> notificationPanel.toggle());

        // Draw bell icon via canvas
        javafx.scene.canvas.Canvas bellCanvas = new javafx.scene.canvas.Canvas(22, 22);
        drawBell(bellCanvas, Theme.TEXT_PRIMARY);
        bellButton.setGraphic(bellCanvas);
        bellButton.setText("");

        badgeLabel = new Label("0");
        badgeLabel.setStyle(
                "-fx-background-color: #DC3545;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 8;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 1 4 1 4;" +
                        "-fx-min-width: 16;");
        badgeLabel.setVisible(false);
        StackPane.setAlignment(badgeLabel, Pos.TOP_RIGHT);

        bellWrapper.getChildren().addAll(bellButton, badgeLabel);

        bar.getChildren().addAll(
                icon, appName, consoleBadge, spacer,
                startAll, stopAll, newJob, bellWrapper);
        return bar;
    }

    private void drawBell(javafx.scene.canvas.Canvas c, String colorHex) {
        var g = c.getGraphicsContext2D();
        g.setFill(Color.web(colorHex));
        // Bell body
        g.fillRoundRect(4, 6, 14, 10, 5, 5);
        // Bell flare
        g.fillRoundRect(2, 14, 18, 4, 3, 3);
        // Handle arc
        g.setStroke(Color.web(colorHex));
        g.setLineWidth(1.5);
        g.strokeArc(8, 1, 6, 6, 0, 180, javafx.scene.shape.ArcType.OPEN);
        // Clapper
        g.fillOval(9, 18, 4, 4);
    }

    private Button styledBtn(String text, String type) {
        Button b = new Button(text);
        String style = switch (type) {
            case "primary" -> "-fx-background-color: " + Theme.ACCENT + ";" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;";
            case "success" -> "-fx-background-color: " + Theme.SUCCESS + ";" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;";
            case "danger"  -> "-fx-background-color: " + Theme.DANGER + ";" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;";
            default        -> "-fx-background-color: " + Theme.BG_CARD + ";" +
                    "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;";
        };
        b.setStyle(style);
        return b;
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}