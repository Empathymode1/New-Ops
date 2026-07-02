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
        JobDetailsPanel details = new JobDetailsPanel(client);

        HBox toolbar = buildToolbar(table, state, client, details);

        HBox split = new HBox(16);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox tableWrap = new VBox(table);
        tableWrap.getStyleClass().add("panel");
        HBox.setHgrow(tableWrap, Priority.ALWAYS);
        split.getChildren().addAll(tableWrap, details);

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, job) -> {
            if (job == null) details.showEmpty(); else details.show(job);
        });

        getChildren().addAll(new VBox(2, title, sub), toolbar, split);
    }

    @SuppressWarnings("unchecked")
    private TableView<Job> buildTable(AppState state) {
        TableView<Job> table = new TableView<>(state.getJobs());
        table.getStyleClass().add("data-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
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
        add.setOnAction(e -> ToastNotification.show("Add Job", "Wizard would open here (5 steps)", false));

        Button edit = new Button("Edit");
        Button delete = new Button("Delete");
        delete.getStyleClass().add("btn-danger");
        Button start = new Button("\u25B6 Start");
        Button stop = new Button("\u25A0 Stop");
        Button restart = new Button("\u27F3 Restart");
        Button refresh = new Button("Refresh");

        for (Button b : new Button[]{edit, delete, start, stop, restart, refresh}) {
            b.getStyleClass().add("btn");
        }

        TextField search = new TextField();
        search.setPromptText("Search jobs…");
        search.setPrefWidth(180);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Runnable updateButtons = () -> {
            Job job = table.getSelectionModel().getSelectedItem();
            boolean has = job != null;
            edit.setDisable(!has);
            delete.setDisable(!has);
            if (!has) { start.setDisable(true); stop.setDisable(true); restart.setDisable(true); return; }
            JobStatus s = job.getStatus();
            start.setDisable(s == JobStatus.RUNNING || s == JobStatus.STARTING || s == JobStatus.DISABLED);
            stop.setDisable(s == JobStatus.STOPPED || s == JobStatus.DISABLED);
            restart.setDisable(s == JobStatus.STOPPED || s == JobStatus.DISABLED);
        };
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> updateButtons.run());
        updateButtons.run();

        start.setOnAction(e -> issueCommand(table, client, JobCommand.START, "started"));
        stop.setOnAction(e -> issueCommand(table, client, JobCommand.STOP, "stopped"));
        restart.setOnAction(e -> issueCommand(table, client, JobCommand.RESTART, "restarted"));
        delete.setOnAction(e -> issueCommand(table, client, JobCommand.DELETE, "deleted"));
        refresh.setOnAction(e -> {
            client.requestInitialSnapshot();
            ToastNotification.show("Refreshed", "Service list reloaded", false);
        });

        HBox bar = new HBox(8, add, edit, delete, separator(), start, stop, restart, spacer, search, refresh);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return bar;
    }

    private void issueCommand(TableView<Job> table, ServiceClient client, JobCommand command, String pastTense) {
        Job job = table.getSelectionModel().getSelectedItem();
        if (job == null) return;
        client.sendCommand(job.getId(), command).thenRun(() ->
                javafx.application.Platform.runLater(() ->
                        ToastNotification.show("Job " + pastTense, job.getName(), false)));
    }

    private Region separator() {
        Region r = new Region();
        r.getStyleClass().add("toolbar-sep");
        r.setPrefWidth(1);
        r.setPrefHeight(20);
        return r;
    }
}
