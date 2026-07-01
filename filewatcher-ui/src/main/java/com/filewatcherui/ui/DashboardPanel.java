package com.filewatcherui.ui;

import com.filewatchercommon.model.WatchJob;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * "Dashboard" tab — read-only service overview, per architecture doc §4.
 *
 * Doc-specified fields, one row per service:
 *   Service Name | Current Status | Last Heartbeat | Files Processed |
 *   Last Error | Last Transfer Time
 *
 * This is intentionally separate from ServiceManagementPanel (JobTablePanel):
 * the Dashboard never issues commands, it only renders state pushed by the
 * service over WebSocket (GET_JOBS / job-state events), consistent with
 * "UI must never perform monitoring or file transfer operations" (doc §3).
 *
 * Note: the service does not currently track a discrete per-job heartbeat
 * timestamp (architecture doc §6's heartbeat is a single global liveness
 * tick, not per-service), so "Last Heartbeat" falls back to "Last Transfer"
 * as the best available liveness signal until the service model exposes one.
 */
public class DashboardPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObservableList<WatchJob> jobs = FXCollections.observableArrayList();
    private final TableView<WatchJob> table = new TableView<>(jobs);
    private final BorderPane root = new BorderPane();

    public DashboardPanel(ServiceClient client) {
        root.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        root.setTop(buildHeader());
        root.setCenter(buildTable());

        client.addJobListListener(list -> Platform.runLater(() -> jobs.setAll(list)));
        client.addJobStateListener(updated -> Platform.runLater(() -> {
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).getId().equals(updated.getId())) {
                    jobs.set(i, updated);
                    return;
                }
            }
            jobs.add(updated);
        }));
    }

    public Region getRoot() { return root; }

    private HBox buildHeader() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 12, 10, 16));
        bar.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        Label title = new Label("Dashboard");
        title.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Label subtitle = new Label("Live status across all services");
        subtitle.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

        VBox box = new VBox(2, title, subtitle);
        bar.getChildren().add(box);
        return bar;
    }

    private TableView<WatchJob> buildTable() {
        table.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: transparent;");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(44);
        table.setPlaceholder(new Label("No services configured yet"));

        TableColumn<WatchJob, String> nameCol = new TableColumn<>("Service Name");
        nameCol.setPrefWidth(180);
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getName()));

        TableColumn<WatchJob, WatchJob.Status> statusCol = new TableColumn<>("Current Status");
        statusCol.setPrefWidth(120);
        statusCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getStatus()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(WatchJob.Status s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); setText(null); return; }
                Label pill = new Label(s.name().charAt(0) + s.name().substring(1).toLowerCase());
                pill.setStyle(Theme.pillStyle(Theme.statusBgColor(s), Theme.statusFgColor(s)));
                setGraphic(pill);
                setText(null);
            }
        });

        TableColumn<WatchJob, String> heartbeatCol = new TableColumn<>("Last Heartbeat");
        heartbeatCol.setPrefWidth(150);
        heartbeatCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getLastTransfer() != null
                        ? c.getValue().getLastTransfer().format(FMT) : "—"));

        TableColumn<WatchJob, String> filesCol = new TableColumn<>("Files Processed");
        filesCol.setPrefWidth(120);
        filesCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().getFilesTransferred())));

        TableColumn<WatchJob, String> errorCol = new TableColumn<>("Last Error");
        errorCol.setPrefWidth(220);
        errorCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getLastError() != null && !c.getValue().getLastError().isBlank()
                        ? c.getValue().getLastError() : "—"));
        errorCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty) { setText(null); return; }
                setText(val);
                setStyle("-fx-text-fill: " +
                        (!"—".equals(val) ? Theme.DANGER : Theme.TEXT_MUTED) + ";" +
                        "-fx-font-size: 11;");
            }
        });

        TableColumn<WatchJob, String> lastTransferCol = new TableColumn<>("Last Transfer Time");
        lastTransferCol.setPrefWidth(150);
        lastTransferCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getLastTransfer() != null
                        ? c.getValue().getLastTransfer().format(FMT) : "—"));

        table.getColumns().addAll(List.of(
                nameCol, statusCol, heartbeatCol, filesCol, errorCol, lastTransferCol));
        return table;
    }
}
