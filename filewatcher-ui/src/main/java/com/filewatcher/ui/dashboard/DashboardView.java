package com.filewatcher.ui.dashboard;

import com.filewatcher.model.ActivityEvent;
import com.filewatcher.model.Job;
import com.filewatcher.state.AppState;
import com.filewatcher.ui.components.InfoCard;
import com.filewatcher.ui.components.StatusBadge;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;

/** Spec §7 — Dashboard: summary cards, live service table, recent activity, health overview. */
public class DashboardView extends VBox {

    public DashboardView(AppState state) {
        getStyleClass().add("page");
        setSpacing(16);
        setPadding(new Insets(20, 22, 32, 22));

        getChildren().addAll(
                pageHeader(),
                buildCards(state),
                buildLiveTable(state),
                buildTwoColumn(state)
        );
    }

    private VBox pageHeader() {
        Label title = new Label("Dashboard");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Real-time operational overview — updates arrive over WebSocket, no polling.");
        sub.getStyleClass().add("page-subtitle");
        VBox box = new VBox(2, title, sub);
        return box;
    }

    private FlowPane buildCards(AppState state) {
        FlowPane grid = new FlowPane(10, 10);
        grid.getStyleClass().add("cards-grid");

        InfoCard running = new InfoCard("Running Jobs", "-green");
        InfoCard stopped = new InfoCard("Stopped Jobs", "-gray");
        InfoCard today = new InfoCard("Transfers Today", "-accent");
        InfoCard failed = new InfoCard("Failed Transfers", "-red");
        InfoCard active = new InfoCard("Active Connections", "-blue");
        InfoCard scheduler = new InfoCard("Scheduler Status", "-green");
        InfoCard ws = new InfoCard("WebSocket Status", "-blue");

        bindInt(running, state.getStats().runningJobsProperty());
        bindInt(stopped, state.getStats().stoppedJobsProperty());
        bindIntFlash(today, state.getStats().transfersTodayProperty(), today);
        bindInt(failed, state.getStats().failedTransfersProperty());
        bindInt(active, state.getStats().activeConnectionsProperty());
        scheduler.setValue(state.getStats().schedulerStatusProperty().get());
        state.getStats().schedulerStatusProperty().addListener((o, ov, nv) -> scheduler.setValue(nv));
        ws.setValue(state.getStats().webSocketStatusProperty().get());
        state.getStats().webSocketStatusProperty().addListener((o, ov, nv) -> ws.setValue(nv));

        for (InfoCard c : new InfoCard[]{running, stopped, today, failed, active, scheduler, ws}) {
            c.setPrefWidth(150);
            grid.getChildren().add(c);
        }
        return grid;
    }

    private void bindInt(InfoCard card, javafx.beans.property.IntegerProperty prop) {
        card.setValue(String.valueOf(prop.get()));
        prop.addListener((o, ov, nv) -> card.setValue(String.valueOf(nv)));
    }

    private void bindIntFlash(InfoCard card, javafx.beans.property.IntegerProperty prop, InfoCard toFlash) {
        card.setValue(String.valueOf(prop.get()));
        prop.addListener((o, ov, nv) -> { card.setValue(String.valueOf(nv)); toFlash.flash(); });
    }

    @SuppressWarnings("unchecked")
    private VBox buildLiveTable(AppState state) {
        TableView<Job> table = new TableView<>(state.getJobs());
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Job, String> name = new TableColumn<>("Service");
        name.setCellValueFactory(new PropertyValueFactory<>("name"));

        TableColumn<Job, Job> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Job job, boolean empty) {
                super.updateItem(job, empty);
                setGraphic(empty || job == null ? null : new StatusBadge(job.getStatus()));
            }
        });

        TableColumn<Job, String> heartbeat = new TableColumn<>("Last Heartbeat");
        heartbeat.setCellValueFactory(new PropertyValueFactory<>("lastTransfer"));

        TableColumn<Job, Number> files = new TableColumn<>("Files Processed");
        files.setCellValueFactory(new PropertyValueFactory<>("filesToday"));

        TableColumn<Job, String> lastTransfer = new TableColumn<>("Last Transfer");
        lastTransfer.setCellValueFactory(new PropertyValueFactory<>("lastTransfer"));

        TableColumn<Job, String> activity = new TableColumn<>("Current Activity");
        activity.setCellValueFactory(new PropertyValueFactory<>("currentActivity"));

        TableColumn<Job, String> error = new TableColumn<>("Last Error");
        error.setCellValueFactory(new PropertyValueFactory<>("lastError"));

        table.getColumns().addAll(name, status, heartbeat, files, lastTransfer, activity, error);
        table.setPrefHeight(230);

        return panel("Live Service Status", table);
    }

    private HBox buildTwoColumn(AppState state) {
        HBox row = new HBox(16);

        // Recent Activity
        ListView<ActivityEvent> activityList = new ListView<>(state.getActivityFeed());
        activityList.getStyleClass().add("activity-list");
        activityList.setPrefHeight(280);
        activityList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ActivityEvent event, boolean empty) {
                super.updateItem(event, empty);
                if (empty || event == null) { setText(null); setGraphic(null); return; }
                Circle dot = new Circle(3.5);
                dot.getStyleClass().add("activity-dot-" + event.colorKey());
                Label body = new Label(event.description());
                body.getStyleClass().add("activity-desc");
                body.setWrapText(true);
                Label time = new Label(event.timeLabel());
                time.getStyleClass().add("activity-time");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(8, dot, body, spacer, time);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        VBox activityPanel = panel("Recent Activity", activityList);
        HBox.setHgrow(activityPanel, Priority.ALWAYS);

        // Health Overview — populated end-to-end from the backend's periodic
        // HEALTH message (contract §1.4); WebSocket derives from the same
        // connection-state signal the "WebSocket Status" card above uses.
        VBox health = new VBox(9);
        health.setPadding(new Insets(12, 14, 12, 14));
        health.getChildren().addAll(
                healthRow("Database", state.getStats().databaseHealthProperty()),
                healthRow("Scheduler", state.getStats().schedulerHealthProperty()),
                healthRowFromWebSocketStatus("WebSocket", state.getStats().webSocketStatusProperty()),
                healthRow("Monitoring Service", state.getStats().monitoringServiceHealthProperty()),
                healthRow("Socket Service (beta)", state.getStats().socketServiceHealthProperty())
        );
        VBox healthPanel = panel("Health Overview", health);
        healthPanel.setPrefWidth(320);

        row.getChildren().addAll(activityPanel, healthPanel);
        return row;
    }

    /** Live health row bound to a "HEALTHY"/"UNHEALTHY"/"DISABLED"/"UNKNOWN" status string. */
    private HBox healthRow(String label, javafx.beans.property.StringProperty status) {
        Label l = new Label(label);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label badge = new Label();
        HBox row = new HBox(8, l, spacer, badge);
        row.getStyleClass().add("health-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Runnable update = () -> applyHealthBadge(badge, status.get());
        status.addListener((o, ov, nv) -> update.run());
        update.run();
        return row;
    }

    /** WebSocket's health row reuses the existing connection-status string rather than a separate one. */
    private HBox healthRowFromWebSocketStatus(String label, javafx.beans.property.StringProperty connectionStatus) {
        Label l = new Label(label);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label badge = new Label();
        HBox row = new HBox(8, l, spacer, badge);
        row.getStyleClass().add("health-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Runnable update = () -> applyHealthBadge(badge,
                "Connected".equalsIgnoreCase(connectionStatus.get()) ? "HEALTHY" : "UNHEALTHY");
        connectionStatus.addListener((o, ov, nv) -> update.run());
        update.run();
        return row;
    }

    private void applyHealthBadge(Label badge, String status) {
        String s = status == null ? "UNKNOWN" : status;
        String text = switch (s) {
            case "HEALTHY" -> "Healthy";
            case "DISABLED" -> "Disabled";
            case "UNHEALTHY" -> "Unhealthy";
            default -> "Checking…";
        };
        String styleClass = switch (s) {
            case "HEALTHY" -> "badge-running";
            case "DISABLED" -> "badge-disabled";
            case "UNHEALTHY" -> "badge-stopped";
            default -> "badge-disabled";
        };
        badge.setText(text);
        badge.getStyleClass().setAll("badge", styleClass);
    }

    private VBox panel(String title, javafx.scene.Node content) {
        Label heading = new Label(title.toUpperCase());
        heading.getStyleClass().add("panel-heading");
        VBox head = new VBox(heading);
        head.getStyleClass().add("panel-head");
        VBox panel = new VBox(head, content);
        panel.getStyleClass().add("panel");
        return panel;
    }
}
