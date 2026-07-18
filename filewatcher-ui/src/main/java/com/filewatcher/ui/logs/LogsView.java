package com.filewatcher.ui.logs;

import com.filewatcher.model.Job;
import com.filewatcher.model.TransferEvent;
import com.filewatcher.model.TransferLogEntry;
import com.filewatcher.service.LogsQuery;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Spec §9 — Logs: filter toolbar, transfer table, double-click detail
 * slide-over. Backed by {@link ServiceClient#requestLogs} (contract §2.9
 * LOGS_REQUEST / §1.7 LOGS_RESPONSE) — a genuine DB-backed job run
 * history, not just whatever happened to stream in live this session
 * (that's what the Dashboard's "Recent Activity" feed is for — this page
 * can answer "what happened yesterday" even after a restart).
 */
public class LogsView extends StackPane {

    private final AppState state;
    private final ServiceClient client;
    private final ObservableList<TransferEvent> rows = FXCollections.observableArrayList();

    private TextField search;
    private ComboBox<String> eventFilter;
    private ComboBox<String> jobFilter;
    private ComboBox<String> dateFilter;

    public LogsView(AppState state, ServiceClient client) {
        this.state = state;
        this.client = client;

        VBox page = new VBox(14);
        page.getStyleClass().add("page");
        page.setPadding(new Insets(20, 22, 32, 22));

        Label title = new Label("Logs");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Search historical transfer events.");
        sub.getStyleClass().add("page-subtitle");

        HBox toolbar = buildToolbar();

        Label hint = new Label("Double-click a row to open transfer details");
        hint.getStyleClass().add("hint-banner");

        TableView<TransferEvent> table = buildTable();
        VBox tableWrap = new VBox(table);
        tableWrap.getStyleClass().add("panel");
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(tableWrap, Priority.ALWAYS);

        page.getChildren().addAll(new VBox(2, title, sub), toolbar, hint, tableWrap);

        TransferDetailsDialog dialog = new TransferDetailsDialog();
        table.setRowFactory(tv -> {
            TableRow<TransferEvent> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    dialog.show(row.getItem(), state, client);
                }
            });
            return row;
        });

        getChildren().addAll(page, dialog);

        runQuery(); // initial load
    }

    private HBox buildToolbar() {
        search = new TextField();
        search.setPromptText("Search logs…");
        search.setPrefWidth(200);
        search.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) runQuery(); });

        eventFilter = new ComboBox<>();
        eventFilter.getItems().addAll("All events", "Transferred", "Failed", "Started", "Stopped");
        eventFilter.setValue("All events");
        eventFilter.setOnAction(e -> runQuery());

        jobFilter = new ComboBox<>();
        jobFilter.getItems().add("All jobs");
        jobFilter.getItems().addAll(state.getJobs().stream().map(Job::getName).toList());
        jobFilter.setValue("All jobs");
        jobFilter.setOnAction(e -> runQuery());
        // Keep the job list live rather than a snapshot taken at construction time --
        // the old version of this page hardcoded 3 demo job names here permanently.
        state.getJobs().addListener((javafx.collections.ListChangeListener<Job>) c -> {
            String current = jobFilter.getValue();
            jobFilter.getItems().setAll("All jobs");
            jobFilter.getItems().addAll(state.getJobs().stream().map(Job::getName).toList());
            jobFilter.setValue(jobFilter.getItems().contains(current) ? current : "All jobs");
        });

        dateFilter = new ComboBox<>();
        dateFilter.getItems().addAll("Last 24 hours", "Last 7 days", "Last 30 days", "All time");
        dateFilter.setValue("Last 24 hours");
        dateFilter.setOnAction(e -> runQuery());

        Button export = new Button("\u2913 Export CSV");
        export.getStyleClass().add("btn");
        export.setOnAction(e -> exportCsv());

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn");
        refresh.setOnAction(e -> runQuery());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, search, eventFilter, jobFilter, dateFilter, spacer, export, refresh);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** Re-runs the query against the current toolbar filter state and repopulates the table. */
    private void runQuery() {
        String jobId = null;
        if (!"All jobs".equals(jobFilter.getValue())) {
            jobId = state.getJobs().stream()
                    .filter(j -> j.getName().equals(jobFilter.getValue()))
                    .findFirst().map(Job::getId).orElse(null);
        }

        String eventType = switch (eventFilter.getValue()) {
            case "Transferred" -> "TRANSFERRED";
            case "Failed" -> "ERROR";
            case "Started" -> "STARTED";
            case "Stopped" -> "STOPPED";
            default -> null;
        };

        Long since = switch (dateFilter.getValue()) {
            case "Last 24 hours" -> epochSecondsAgo(1);
            case "Last 7 days" -> epochSecondsAgo(7);
            case "Last 30 days" -> epochSecondsAgo(30);
            default -> null; // "All time" -- no cutoff
        };

        String searchText = search.getText() == null || search.getText().isBlank() ? null : search.getText().trim();

        client.requestLogs(new LogsQuery(jobId, eventType, searchText, since, 500))
                .thenAccept(entries -> javafx.application.Platform.runLater(() ->
                        rows.setAll(entries.stream().map(LogsView::toDisplayRow).toList())));
    }

    private static Long epochSecondsAgo(int days) {
        return LocalDateTime.now().minusDays(days).atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static TransferEvent toDisplayRow(TransferLogEntry entry) {
        boolean ok = "TRANSFERRED".equals(entry.eventType()) || "STARTED".equals(entry.eventType())
                || "CONNECTED".equals(entry.eventType());
        String friendlyType = switch (entry.eventType() == null ? "" : entry.eventType()) {
            case "TRANSFERRED" -> "Transferred";
            case "ERROR" -> "Failed";
            case "STARTED" -> "Started";
            case "STOPPED" -> "Stopped";
            default -> entry.eventType() == null ? "—" : entry.eventType();
        };
        TransferEvent event = new TransferEvent(
                entry.occurredAt() != null ? entry.occurredAt() : "—",
                entry.jobName() != null ? entry.jobName() : "—",
                entry.filename() != null ? entry.filename() : "—",
                friendlyType,
                ok ? "ok" : "failed",
                "—", // per-event duration isn't tracked at this granularity yet
                formatSize(entry.sizeBytes()),
                entry.message() != null ? entry.message() : ""
        );
        event.setJobId(entry.jobId());
        return event;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void exportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Logs");
        chooser.setInitialFileName("transfer-logs.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        java.io.File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        StringBuilder csv = new StringBuilder("Timestamp,Job Name,Filename,Event Type,Status,Duration,File Size,Message\n");
        for (TransferEvent e : rows) {
            csv.append(csvField(e.getTimestamp())).append(',')
                    .append(csvField(e.getJobName())).append(',')
                    .append(csvField(e.getFilename())).append(',')
                    .append(csvField(e.getEventType())).append(',')
                    .append(csvField(e.getStatus())).append(',')
                    .append(csvField(e.getDuration())).append(',')
                    .append(csvField(e.getSize())).append(',')
                    .append(csvField(e.getMessage())).append('\n');
        }
        try {
            Files.writeString(file.toPath(), csv.toString());
        } catch (IOException ignored) {
            // best-effort export; a failed write here isn't worth a modal error for a log export
        }
    }

    private static String csvField(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")
                ? "\"" + escaped + "\"" : escaped;
    }

    @SuppressWarnings("unchecked")
    private TableView<TransferEvent> buildTable() {
        TableView<TransferEvent> table = new TableView<>(rows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<TransferEvent, String> ts = new TableColumn<>("Timestamp");
        ts.setCellValueFactory(new PropertyValueFactory<>("timestamp"));

        TableColumn<TransferEvent, String> job = new TableColumn<>("Job Name");
        job.setCellValueFactory(new PropertyValueFactory<>("jobName"));

        TableColumn<TransferEvent, String> file = new TableColumn<>("Filename");
        file.setCellValueFactory(new PropertyValueFactory<>("filename"));

        TableColumn<TransferEvent, String> ev = new TableColumn<>("Event Type");
        ev.setCellValueFactory(new PropertyValueFactory<>("eventType"));

        TableColumn<TransferEvent, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(new PropertyValueFactory<>("status"));
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setGraphic(null); return; }
                Label badge = new Label("ok".equals(value) ? "OK" : "Failed");
                badge.getStyleClass().addAll("badge", "ok".equals(value) ? "badge-running" : "badge-stopped");
                setGraphic(badge);
            }
        });

        TableColumn<TransferEvent, String> dur = new TableColumn<>("Duration");
        dur.setCellValueFactory(new PropertyValueFactory<>("duration"));

        TableColumn<TransferEvent, String> size = new TableColumn<>("File Size");
        size.setCellValueFactory(new PropertyValueFactory<>("size"));

        TableColumn<TransferEvent, String> msg = new TableColumn<>("Message");
        msg.setCellValueFactory(new PropertyValueFactory<>("message"));

        table.getColumns().addAll(ts, job, file, ev, status, dur, size, msg);
        return table;
    }
}
