package com.filewatcher.ui.logs;

import com.filewatcher.model.TransferEvent;
import com.filewatcher.state.AppState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Spec §9 — Logs: filter toolbar, transfer table, double-click detail slide-over. */
public class LogsView extends StackPane {

    public LogsView(AppState state) {
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

        TableView<TransferEvent> table = buildTable(state);
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
                    dialog.show(row.getItem(), state);
                }
            });
            return row;
        });

        getChildren().addAll(page, dialog);
    }

    private HBox buildToolbar() {
        TextField search = new TextField();
        search.setPromptText("Search logs…");
        search.setPrefWidth(200);

        ComboBox<String> eventFilter = new ComboBox<>();
        eventFilter.getItems().addAll("All events", "Transferred", "Failed", "Started", "Stopped");
        eventFilter.setValue("All events");

        ComboBox<String> jobFilter = new ComboBox<>();
        jobFilter.getItems().addAll("All jobs", "PAX-Manifest-Sync", "Baggage-EDI-Feed", "Cargo-Docs-Relay");
        jobFilter.setValue("All jobs");

        ComboBox<String> dateFilter = new ComboBox<>();
        dateFilter.getItems().addAll("Last 24 hours", "Last 7 days", "Last 30 days", "Custom range");
        dateFilter.setValue("Last 24 hours");

        Button export = new Button("\u2913 Export CSV");
        export.getStyleClass().add("btn");
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, search, eventFilter, jobFilter, dateFilter, spacer, export, refresh);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private TableView<TransferEvent> buildTable(AppState state) {
        TableView<TransferEvent> table = new TableView<>(state.getLogs());
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
