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
import java.util.function.Consumer;
import java.util.function.Function;

public class JobTablePanel {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServiceClient client;
    private final ObservableList<WatchJob> jobs = FXCollections.observableArrayList();
    private final TableView<WatchJob> table = new TableView<>(jobs);
    private final BorderPane root = new BorderPane();
    private Consumer<WatchJob> onSelect;

    public JobTablePanel(ServiceClient client) {
        this.client = client;
        root.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        root.setTop(buildHeader());
        root.setCenter(buildTable());
        root.setBottom(buildFooter());
    }

    public Region getRoot() { return root; }

    public void setOnSelect(Consumer<WatchJob> cb) {
        this.onSelect = cb;
    }

    public void setJobs(List<WatchJob> list) {
        Platform.runLater(() -> {
            jobs.setAll(list);
        });
    }

    public void updateJob(WatchJob updated) {
        Platform.runLater(() -> {
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).getId().equals(updated.getId())) {
                    jobs.set(i, updated);
                    return;
                }
            }
            jobs.add(updated);
        });
    }

    // ── Header ────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 12, 10, 16));
        bar.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        Label title = new Label("Watch Jobs");
        title.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button startAll = pillBtn("Start All", Theme.SUCCESS);
        Button stopAll  = pillBtn("Stop All",  Theme.DANGER);
        Button addBtn   = pillBtn("+ Add",     Theme.ACCENT);

        startAll.setOnAction(e -> client.startAll());
        stopAll.setOnAction(e  -> client.stopAll());
        addBtn.setOnAction(e   -> new JobEditDialog(
                root.getScene().getWindow(), client, null).show());

        bar.getChildren().addAll(title, spacer, startAll, stopAll, addBtn);
        return bar;
    }

    // ── Table ─────────────────────────────────────────────────────────────

    private TableView<WatchJob> buildTable() {
        table.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: transparent;");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(52);
        table.setRowFactory(tv -> {
            TableRow<WatchJob> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    new JobEditDialog(root.getScene().getWindow(),
                            client, row.getItem()).show();
                }
            });
            row.itemProperty().addListener((obs, old, job) -> {
                if (job == null) {
                    row.setStyle("");
                } else {
                    row.setStyle("-fx-background-color: " +
                            (row.getIndex() % 2 == 0
                                    ? Theme.BG_CARD
                                    : Theme.BG_SURFACE) + ";");
                }
            });
            return row;
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, job) -> {
            if (job != null && onSelect != null) onSelect.accept(job);
        });

        // Name column
        TableColumn<WatchJob, String> nameCol = new TableColumn<>("Name");
        nameCol.setPrefWidth(180);
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getName()));
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) { setGraphic(null); return; }
                WatchJob job = getTableView().getItems().get(getIndex());
                VBox box = new VBox(2);
                box.setAlignment(Pos.CENTER_LEFT);
                Label n = new Label(name);
                n.setStyle("-fx-font-weight: bold; -fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
                Label sub = new Label(job.getSourcePath() != null ? job.getSourcePath() : "");
                sub.setStyle("-fx-font-size: 10; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
                box.getChildren().addAll(n, sub);
                setGraphic(box);
                setText(null);
            }
        });

        // Status column
        TableColumn<WatchJob, WatchJob.Status> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(100);
        statusCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getStatus()));
        statusCol.setCellFactory(col -> pillCell(
                s -> s == null ? "" : s.name().charAt(0) + s.name().substring(1).toLowerCase(),
                Theme::statusBgColor,
                Theme::statusFgColor));

        // Direction column
        TableColumn<WatchJob, WatchJob.Direction> dirCol = new TableColumn<>("Direction");
        dirCol.setPrefWidth(100);
        dirCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getDirection()));
        dirCol.setCellFactory(col -> pillCell(
                d -> d == null ? "" : switch ((WatchJob.Direction) d) {
                    case INBOUND        -> "Inbound";
                    case OUTBOUND       -> "Outbound";
                    case LOCAL_TO_LOCAL -> "Local";
                },
                d -> Theme.directionBgColor((WatchJob.Direction) d),
                d -> Theme.directionFgColor((WatchJob.Direction) d)));

        // Mode column
        TableColumn<WatchJob, WatchJob.TransferMode> modeCol = new TableColumn<>("Mode");
        modeCol.setPrefWidth(90);
        modeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(
                c.getValue().getTransferMode()));
        modeCol.setCellFactory(col -> pillCell(
                m -> m == null ? "" : switch ((WatchJob.TransferMode) m) {
                    case ENTIRE_FOLDER -> "All files";
                    case LATEST_ONLY   -> "Latest";
                    case SPECIFIC      -> "Pattern";
                },
                m -> switch ((WatchJob.TransferMode) m) {
                    case ENTIRE_FOLDER -> "#EEEBf8";
                    case LATEST_ONLY   -> "#E6F2FA";
                    case SPECIFIC      -> "#EDF5E8";
                },
                m -> switch ((WatchJob.TransferMode) m) {
                    case ENTIRE_FOLDER -> "#534AB7";
                    case LATEST_ONLY   -> "#185FA5";
                    case SPECIFIC      -> "#3B6D11";
                }));

        // Last transfer column
        TableColumn<WatchJob, String> lastCol = new TableColumn<>("Last Transfer");
        lastCol.setPrefWidth(130);
        lastCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getLastTransfer() != null
                        ? c.getValue().getLastTransfer().format(FMT) : "—"));
        lastCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty) { setText(null); return; }
                setText(val);
                setStyle("-fx-text-fill: " + Theme.TEXT_MUTED + ";" +
                        "-fx-font-size: 11;" +
                        "-fx-padding: 0 0 0 12;");
            }
        });

        table.getColumns().addAll(nameCol, statusCol, dirCol, modeCol, lastCol);
        return table;
    }

    // ── Footer ────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        HBox footer = new HBox(8);
        footer.setPadding(new Insets(8));
        footer.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 1 0 0 0;");

        Button startBtn  = pillBtn("Start",  Theme.SUCCESS);
        Button stopBtn   = pillBtn("Stop",   Theme.DANGER);
        Button editBtn   = pillBtn("Edit",   Theme.TEXT_SECONDARY);
        Button deleteBtn = pillBtn("Delete", Theme.DANGER);

        startBtn.setOnAction(e  -> actOnSelected(j -> client.startJob(j.getId())));
        stopBtn.setOnAction(e   -> actOnSelected(j -> client.stopJob(j.getId())));
        editBtn.setOnAction(e   -> actOnSelected(j ->
                new JobEditDialog(root.getScene().getWindow(), client, j).show()));
        deleteBtn.setOnAction(e -> actOnSelected(j -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete job '" + j.getName() + "'?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) client.deleteJob(j.getId());
            });
        }));

        footer.getChildren().addAll(startBtn, stopBtn, editBtn, deleteBtn);
        return footer;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void actOnSelected(Consumer<WatchJob> action) {
        WatchJob sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) action.accept(sel);
    }

    private Button pillBtn(String text, String colorHex) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + colorHex + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 11;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 5;" +
                        "-fx-padding: 5 12 5 12;" +
                        "-fx-cursor: hand;");
        return b;
    }

    private <T> TableCell<WatchJob, T> pillCell(
            Function<T, String> label,
            Function<T, String> bg,
            Function<T, String> fg) {
        return new TableCell<>() {
            @Override protected void updateItem(T val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) { setGraphic(null); return; }
                Label pill = new Label(label.apply(val));
                pill.setStyle(Theme.pillStyle(bg.apply(val), fg.apply(val)));
                HBox box = new HBox(pill);
                box.setPadding(new Insets(0, 4, 0, 12));
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setText(null);
            }
        };
    }
}