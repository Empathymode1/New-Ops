package com.filewatcher.ui.services;

import com.filewatcher.model.Job;
import com.filewatcher.model.JobStatus;
import com.filewatcher.service.JobCommand;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.ui.components.StatusBadge;
import com.filewatcher.ui.components.ToastNotification;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Spec §8 — Service Management: toolbar, watch job table, details panel. */
public class ServicesView extends VBox {

    public ServicesView(AppState state, ServiceClient client) {
        getStyleClass().add("page");
        setSpacing(14);
        setPadding(new Insets(20, 22, 32, 22));

        Label title = new Label("Service Management");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Configure and control watch jobs.");
        sub.getStyleClass().add("page-subtitle");

        TableView<Job> table = buildTable(state);
        JobDetailsPanel details = new JobDetailsPanel(client, state);

        HBox toolbar = buildToolbar(table, state, client, details);

        HBox split = new HBox(16);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox tableWrap = new VBox(table);
        tableWrap.getStyleClass().add("panel");
        HBox.setHgrow(tableWrap, Priority.ALWAYS);
        split.getChildren().addAll(tableWrap, details);

        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Job>) c -> {
            var selected = table.getSelectionModel().getSelectedItems();
            if (selected.size() == 1) details.show(selected.get(0));
            else details.showEmpty();
        });

        getChildren().addAll(new VBox(2, title, sub), toolbar, split);
    }

    @SuppressWarnings("unchecked")
    private TableView<Job> buildTable(AppState state) {
        TableView<Job> table = new TableView<>(state.getJobs());
        table.getStyleClass().add("data-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Job, String> name = new TableColumn<>("Job Name");
        name.setCellValueFactory(new PropertyValueFactory<>("name"));

        TableColumn<Job, String> type = new TableColumn<>("Service Type");
        type.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn<Job, String> source = new TableColumn<>("Source Path");
        source.setCellValueFactory(new PropertyValueFactory<>("sourcePath"));

        TableColumn<Job, String> dest = new TableColumn<>("Destination Path");
        dest.setCellValueFactory(new PropertyValueFactory<>("destPath"));

        TableColumn<Job, String> poll = new TableColumn<>("Polling Interval");
        poll.setCellValueFactory(new PropertyValueFactory<>("pollingInterval"));

        TableColumn<Job, String> cred = new TableColumn<>("Credential");
        cred.setCellValueFactory(new PropertyValueFactory<>("credential"));

        TableColumn<Job, Job> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Job job, boolean empty) {
                super.updateItem(job, empty);
                setGraphic(empty || job == null ? null : new StatusBadge(job.getStatus()));
            }
        });

        TableColumn<Job, Number> files = new TableColumn<>("Files Today");
        files.setCellValueFactory(new PropertyValueFactory<>("filesToday"));

        TableColumn<Job, String> last = new TableColumn<>("Last Transfer");
        last.setCellValueFactory(new PropertyValueFactory<>("lastTransfer"));

        table.getColumns().addAll(name, type, source, dest, poll, cred, status, files, last);
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private HBox buildToolbar(TableView<Job> table, AppState state, ServiceClient client, JobDetailsPanel details) {
        Button add = new Button("+ Add");
        add.getStyleClass().addAll("btn", "btn-primary");
        add.setOnAction(e -> JobFormDialog.showAdd(add, state, client).ifPresent(config ->
                client.addJob(config).thenAccept(result -> javafx.application.Platform.runLater(() -> {
                    if (result.success()) {
                        ToastNotification.show("Job added", config.name, false);
                    } else {
                        ToastNotification.show("Couldn't add job", result.error(), true);
                    }
                }))));

        Button edit = new Button("Edit");
        edit.setOnAction(e -> {
            var selected = table.getSelectionModel().getSelectedItems();
            if (selected.size() != 1) return;
            Job job = selected.get(0);
            com.filewatcher.model.WatchJobConfig prefill = job.getRawConfig();
            if (prefill == null) {
                prefill = new com.filewatcher.model.WatchJobConfig();
                prefill.name = job.getName();
            }
            JobFormDialog.showEdit(edit, prefill, state, client).ifPresent(config ->
                    client.updateJob(job.getId(), config).thenAccept(result -> javafx.application.Platform.runLater(() -> {
                        if (result.success()) {
                            ToastNotification.show("Job updated", config.name, false);
                        } else {
                            ToastNotification.show("Couldn't save job", result.error(), true);
                        }
                    })));
        });
        Button delete = new Button("Delete");
        delete.getStyleClass().add("btn-danger");
        Button start = new Button("\u25B6 Start");
        Button stop = new Button("\u25A0 Stop");
        Button restart = new Button("\u27F3 Restart");
        Button refresh = new Button("Refresh");
        Button export = new Button("Export");
        Button importBtn = new Button("Import");

        for (Button b : new Button[]{edit, delete, start, stop, restart, refresh, export, importBtn}) {
            b.getStyleClass().add("btn");
        }

        TextField search = new TextField();
        search.setPromptText("Search jobs…");
        search.setPrefWidth(180);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Runnable updateButtons = () -> {
            var selected = table.getSelectionModel().getSelectedItems();
            edit.setDisable(selected.size() != 1);
            delete.setDisable(selected.isEmpty());
            if (selected.isEmpty()) { start.setDisable(true); stop.setDisable(true); restart.setDisable(true); return; }
            // Enabled if AT LEAST ONE selected job is in a state the action applies to —
            // issueCommand skips the rest per-job, so a mixed selection (e.g. one running,
            // one stopped) can still bulk-start just the stopped ones in one click.
            boolean anyStartable = selected.stream().anyMatch(j -> j.getStatus() != JobStatus.RUNNING && j.getStatus() != JobStatus.STARTING && j.getStatus() != JobStatus.DISABLED);
            boolean anyStoppable = selected.stream().anyMatch(j -> j.getStatus() != JobStatus.STOPPED && j.getStatus() != JobStatus.DISABLED);
            start.setDisable(!anyStartable);
            stop.setDisable(!anyStoppable);
            restart.setDisable(!anyStoppable);
        };
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Job>) c -> updateButtons.run());
        updateButtons.run();

        start.setOnAction(e -> issueCommand(table, client, JobCommand.START, "started"));
        stop.setOnAction(e -> issueCommand(table, client, JobCommand.STOP, "stopped"));
        restart.setOnAction(e -> issueCommand(table, client, JobCommand.RESTART, "restarted"));
        delete.setOnAction(e -> {
            var selected = List.copyOf(table.getSelectionModel().getSelectedItems());
            if (selected.size() > 1) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete " + selected.size() + " selected jobs? This can't be undone.", ButtonType.YES, ButtonType.NO);
                confirm.setHeaderText("Delete " + selected.size() + " jobs");
                if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
            }
            issueCommand(table, client, JobCommand.DELETE, "deleted");
        });
        refresh.setOnAction(e -> {
            client.requestInitialSnapshot();
            ToastNotification.show("Refreshed", "Service list reloaded", false);
        });

        export.setOnAction(e -> {
            var selected = table.getSelectionModel().getSelectedItems();
            List<Job> toExport = selected.isEmpty() ? table.getItems() : selected;
            List<com.filewatcher.model.WatchJobConfig> configs = toExport.stream()
                    .map(Job::getRawConfig)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (configs.isEmpty()) {
                ToastNotification.show("Nothing to export", "No job configs available yet", true);
                return;
            }
            boolean ok = JobConfigIo.exportTo(export.getScene().getWindow(), configs);
            if (ok) {
                ToastNotification.show("Exported", configs.size() + " job(s) — passwords are not included", false);
            }
        });

        importBtn.setOnAction(e -> {
            List<com.filewatcher.model.WatchJobConfig> configs = JobConfigIo.importFrom(importBtn.getScene().getWindow());
            if (configs == null) return; // cancelled or unreadable
            if (configs.isEmpty()) {
                ToastNotification.show("Nothing imported", "File contained no jobs", true);
                return;
            }
            List<CompletableFuture<com.filewatcher.service.JobSaveResult>> futures = configs.stream()
                    .map(client::addJob).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, ex) ->
                    javafx.application.Platform.runLater(() -> {
                        long ok = futures.stream().filter(f -> f.getNow(null) != null && f.getNow(null).success()).count();
                        ToastNotification.show("Import complete",
                                ok + "/" + futures.size() + " job(s) added — set credentials via Edit before starting them",
                                ok < futures.size());
                    }));
        });

        HBox bar = new HBox(8, add, edit, delete, separator(), start, stop, restart, separator(), export, importBtn, spacer, search, refresh);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Runs {@code command} against every currently-selected job (bulk when
     * multiple are selected, same single-job behavior as before otherwise).
     * Jobs where the action doesn't apply (e.g. STOP on an already-stopped
     * job) are simply skipped rather than erroring the whole batch.
     */
    private void issueCommand(TableView<Job> table, ServiceClient client, JobCommand command, String pastTense) {
        var selected = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;

        List<Job> issuedFor = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Job job : selected) {
            if (!applies(command, job.getStatus())) continue;
            issuedFor.add(job);
            futures.add(client.sendCommand(job.getId(), command));
        }
        if (futures.isEmpty()) return;

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, ex) ->
                javafx.application.Platform.runLater(() -> {
                    String label = issuedFor.size() == 1 ? issuedFor.get(0).getName() : issuedFor.size() + " jobs";
                    ToastNotification.show(issuedFor.size() == 1 ? "Job " + pastTense : "Jobs " + pastTense, label, false);
                }));
    }

    private boolean applies(JobCommand command, JobStatus status) {
        return switch (command) {
            case START -> status != JobStatus.RUNNING && status != JobStatus.STARTING && status != JobStatus.DISABLED;
            case STOP, RESTART -> status != JobStatus.STOPPED && status != JobStatus.DISABLED;
            case DELETE, TEST_CONNECTION -> true;
        };
    }

    private Region separator() {
        Region r = new Region();
        r.getStyleClass().add("toolbar-sep");
        r.setPrefWidth(1);
        r.setPrefHeight(20);
        return r;
    }
}
