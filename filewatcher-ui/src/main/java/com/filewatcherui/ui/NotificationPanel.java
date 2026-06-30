package com.filewatcherui.ui;

import com.filewatchercommon.model.NotificationMessage;
import com.filewatchercommon.service.NotificationService;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class NotificationPanel {

    private static final int PANEL_WIDTH = 320;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss");

    private final NotificationService notificationService;
    private final ServiceClient client;
    private final BorderPane root = new BorderPane();
    private final VBox listPanel  = new VBox(1);
    private boolean panelVisible  = false;

    public NotificationPanel(NotificationService notificationService,
                             ServiceClient client) {
        this.notificationService = notificationService;
        this.client = client;

        root.setPrefWidth(PANEL_WIDTH);
        root.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-width: 0 0 0 1;");
        root.setVisible(false);

        root.setTop(buildHeader());

        listPanel.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        ScrollPane scroll = new ScrollPane(listPanel);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";" +
                "-fx-background: " + Theme.BG_SURFACE + ";");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setCenter(scroll);

        notificationService.addListener(this::refresh);
    }

    public Region getRoot() { return root; }

    public void toggle() {
        panelVisible = !panelVisible;
        root.setVisible(panelVisible);
        root.setManaged(panelVisible);
        if (panelVisible) {
            notificationService.markAllRead();
            refresh(notificationService.getAll());
        }
    }

    public boolean isVisible() { return panelVisible; }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(8);
        header.setPadding(new Insets(14, 16, 14, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        Label title = new Label("Notifications");
        title.setStyle("-fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button markRead = smallBtn("Mark all read");
        Button clear    = smallBtn("Clear");

        markRead.setOnAction(e -> {
            notificationService.markAllRead();
            refresh(notificationService.getAll());
        });
        clear.setOnAction(e -> notificationService.clear());

        header.getChildren().addAll(title, spacer, markRead, clear);
        return header;
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    private void refresh(List<NotificationMessage> notifications) {
        Platform.runLater(() -> {
            listPanel.getChildren().clear();
            if (notifications.isEmpty()) {
                Label empty = new Label("No notifications");
                empty.setStyle("-fx-text-fill: " + Theme.TEXT_MUTED +
                        "; -fx-font-size: 11;" +
                        "-fx-padding: 24 16 0 16;");
                listPanel.getChildren().add(empty);
            } else {
                for (NotificationMessage n : notifications)
                    listPanel.getChildren().add(notificationCard(n));
            }
        });
    }

    // ── Card ──────────────────────────────────────────────────────────────

    private VBox notificationCard(NotificationMessage n) {
        String accentColor = n.isRead() ? Theme.BORDER : "#DC3545";

        VBox card = new VBox(6);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setMaxWidth(PANEL_WIDTH);
        card.setStyle(
                "-fx-background-color: " +
                        (n.isRead() ? Theme.BG_SURFACE : Theme.BG_CARD) + ";" +
                        "-fx-border-color: " + accentColor + " transparent transparent transparent;" +
                        "-fx-border-width: 0 0 0 3;");

        // Top row
        Label jobLabel = new Label(n.getJobName());
        jobLabel.setStyle("-fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        Label timeLabel = new Label(n.getTimestamp().format(FMT));
        timeLabel.setStyle("-fx-font-size: 10;" +
                "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topRow = new HBox(jobLabel, spacer, timeLabel);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // Message
        Label msg = new Label(n.getMessage());
        msg.setStyle("-fx-font-size: 11;" +
                "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";");
        msg.setWrapText(true);

        // Restart button
        Button restart = smallBtn("Restart job");
        restart.setStyle(restart.getStyle() +
                "-fx-text-fill: " + Theme.ACCENT + ";");
        restart.setOnAction(e -> {
            n.markRead();
            client.stopJob(n.getJobId());
            client.startJob(n.getJobId());
            refresh(notificationService.getAll());
        });

        card.getChildren().addAll(topRow, msg, restart);
        return card;
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 3 8 3 8; -fx-font-size: 11;" +
                        "-fx-cursor: hand;");
        return b;
    }
    public void setVisible(boolean visible) {
        panelVisible = visible;
        root.setVisible(visible);
        root.setManaged(visible);
    }
}