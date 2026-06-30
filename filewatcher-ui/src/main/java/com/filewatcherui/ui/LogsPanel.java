package com.filewatcherui.ui;

import com.filewatchercommon.model.LogEntryMessage;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * "Logs" tab — search/filter/export over the transfer_logs table (persisted
 * history), per architecture doc section 4. This is distinct from
 * EventLogPanel, which only shows events pushed live while the UI happens
 * to be connected and never queries the database.
 *
 * Filtering is server-side (GET_LOGS with optional jobId/eventType/searchText),
 * so the UI never holds more than one page of results and never touches SQL —
 * it only sends filter params and renders whatever rows come back, per the
 * doc's "UI must never perform monitoring or file transfer operations"
 * principle (section 3), extended here to mean "never query the DB directly."
 */
public class LogsPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 500;

    private final ServiceClient client;
    private final BorderPane root = new BorderPane();

    private TextField searchField;
    private ComboBox<String> eventTypeFilter;
    private ComboBox<String> jobFilter; // populated externally via setAvailableJobs()
    private TableView<LogEntryMessage> table;
    private Label resultCountLabel;

    public LogsPanel(ServiceClient client) {
        this.client = client;

        root.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");
        root.setTop(buildToolbar());
        root.setCenter(buildTable());

        client.addLogsListener(logs -> Platform.runLater(() -> renderResults(logs)));
        client.addLogsExportListener(csv -> Platform.runLater(() -> saveCsv(csv)));

        // Initial load — most recent logs, no filters
        refresh();
    }

    public Region getRoot() { return root; }

    /** Called by MainWindow whenever the job list changes, so the job filter dropdown stays current. */
    public void setAvailableJobs(List<com.filewatchercommon.model.WatchJob> jobs) {
        String previousSelection = jobFilter.getValue();
        jobFilter.getItems().setAll("All jobs");
        jobs.forEach(j -> jobFilter.getItems().add(j.getName() + "|" + j.getId()));
        jobFilter.setValue(previousSelection != null && jobFilter.getItems().contains(previousSelection)
                ? previousSelection : "All jobs");
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 1 0;");

        Label title = new Label("Logs");
        title.setStyle("-fx-font-size: 15; -fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        searchField = new TextField();
        searchField.setPromptText("Search filename, message, or job...");
        styleField(searchField);
        searchField.setPrefWidth(240);
        searchField.setOnAction(e -> refresh()); // Enter triggers search

        eventTypeFilter = new ComboBox<>();
        eventTypeFilter.getItems().addAll(
                "All types", "DETECTED", "TRANSFERRED", "SKIPPED",
                "ERROR", "CONNECTED", "DISCONNECTED", "STARTED", "STOPPED");
        eventTypeFilter.setValue("All types");
        eventTypeFilter.setOnAction(e -> refresh());

        jobFilter = new ComboBox<>();
        jobFilter.getItems().add("All jobs");
        jobFilter.setValue("All jobs");
        jobFilter.setOnAction(e -> refresh());

        Button searchBtn = smallBtn("Search");
        searchBtn.setOnAction(e -> refresh());

        Button clearBtn = smallBtn("Clear filters");
        clearBtn.setOnAction(e -> {
            searchField.clear();
            eventTypeFilter.setValue("All types");
            jobFilter.setValue("All jobs");
            refresh();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        resultCountLabel = new Label("");
        resultCountLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");

        Button exportBtn = smallBtn("Export CSV");
        exportBtn.setOnAction(e -> requestExport());

        bar.getChildren().addAll(title, vSep(),
                searchField, eventTypeFilter, jobFilter, searchBtn, clearBtn,
                spacer, resultCountLabel, exportBtn);
        return bar;
    }

    // ── Table ─────────────────────────────────────────────────────────────

    private TableView<LogEntryMessage> buildTable() {
        table = new TableView<>();
        table.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No log entries match the current filters"));

        TableColumn<LogEntryMessage, String> tsCol = new TableColumn<>("Timestamp");
        tsCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getOccurredAt() != null
                        ? data.getValue().getOccurredAt().format(FMT) : ""));
        tsCol.setPrefWidth(150);

        TableColumn<LogEntryMessage, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getEventType()));
        typeCol.setPrefWidth(100);

        TableColumn<LogEntryMessage, String> jobCol = new TableColumn<>("Job");
        jobCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getJobName() != null ? data.getValue().getJobName() : ""));
        jobCol.setPrefWidth(140);

        TableColumn<LogEntryMessage, String> fileCol = new TableColumn<>("Filename");
        fileCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getFilename() != null ? data.getValue().getFilename() : ""));
        fileCol.setPrefWidth(160);

        TableColumn<LogEntryMessage, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getSizeBytes() > 0
                        ? formatBytes(data.getValue().getSizeBytes())
                        : ""));
        sizeCol.setPrefWidth(80);

        TableColumn<LogEntryMessage, String> msgCol = new TableColumn<>("Message");
        msgCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getMessage() != null ? data.getValue().getMessage() : ""));
        msgCol.setPrefWidth(360);

        table.getColumns().addAll(List.of(tsCol, typeCol, jobCol, fileCol, sizeCol, msgCol));

        // Double-click → view full transfer detail (message often truncated in the cell)
        table.setRowFactory(tv -> {
            TableRow<LogEntryMessage> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showDetail(row.getItem());
                }
            });
            return row;
        });

        return table;
    }

    private void showDetail(LogEntryMessage entry) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Transfer Detail");
        a.setHeaderText((entry.getJobName() != null ? entry.getJobName() : "Unknown job")
                + " — " + entry.getEventType());
        StringBuilder body = new StringBuilder();
        if (entry.getOccurredAt() != null) body.append("Time: ").append(entry.getOccurredAt().format(FMT)).append("\n");
        if (entry.getFilename() != null && !entry.getFilename().isBlank())
            body.append("File: ").append(entry.getFilename()).append("\n");
        if (entry.getSizeBytes() > 0)
            body.append("Size: ").append(formatBytes(entry.getSizeBytes())).append("\n");
        body.append("\n").append(entry.getMessage());
        a.setContentText(body.toString());
        a.setHeaderText(a.getHeaderText());
        a.getDialogPane().setPrefWidth(480);
        a.showAndWait();
    }

    // ── Data flow ─────────────────────────────────────────────────────────

    private void refresh() {
        client.getLogs(selectedJobId(), selectedEventType(), searchField.getText(), DEFAULT_LIMIT);
    }

    /**
     * Re-issues the current filtered query against the service. Intended to be
     * called by MainWindow's connect listener: the initial refresh() fired
     * from the constructor runs before the WebSocket is open (ServiceClient
     * connects asynchronously after the UI is shown), so its GET_LOGS command
     * is silently dropped. This lets the panel populate itself as soon as a
     * connection actually exists, and again after any reconnect.
     */
    public void refreshFromServer() {
        refresh();
    }

    private void requestExport() {
        client.exportLogs(selectedJobId(), selectedEventType(), searchField.getText(), 10000);
    }

    private String selectedJobId() {
        String v = jobFilter.getValue();
        if (v == null || v.equals("All jobs")) return null;
        int sep = v.lastIndexOf('|');
        return sep >= 0 ? v.substring(sep + 1) : null;
    }

    private String selectedEventType() {
        String v = eventTypeFilter.getValue();
        return (v == null || v.equals("All types")) ? null : v;
    }

    private void renderResults(List<LogEntryMessage> logs) {
        table.getItems().setAll(logs);
        resultCountLabel.setText(logs.size() + " result" + (logs.size() == 1 ? "" : "s"));
    }

    private void saveCsv(String csv) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export logs");
        fc.setInitialFileName("filewatcher-logs.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File chosen = fc.showSaveDialog(root.getScene().getWindow());
        if (chosen == null) return;

        try (PrintWriter pw = new PrintWriter(chosen)) {
            pw.print(csv);
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "Export failed: " + ex.getMessage(), ButtonType.OK);
            a.setHeaderText(null);
            a.showAndWait();
        }
    }

    // ── Style helpers ─────────────────────────────────────────────────────

    private void styleField(Control f) {
        f.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 4 8 4 8; -fx-font-size: 12;");
    }

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 4 10 4 10; -fx-font-size: 11; -fx-cursor: hand;");
        return b;
    }

    private Separator vSep() {
        Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
        s.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return s;
    }

    /**
     * Local copy of byte-formatting logic — deliberately NOT imported from
     * com.filewatcherservice.service.FileWatcherService.formatBytes(), since
     * the UI module must have zero compile-time dependency on the service
     * module (architecture doc section 1: "two independent Java
     * applications", communicating only via WebSocket).
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}