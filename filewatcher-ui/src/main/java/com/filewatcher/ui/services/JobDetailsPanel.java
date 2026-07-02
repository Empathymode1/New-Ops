package com.filewatcher.ui.services;

import com.filewatcher.model.Job;
import com.filewatcher.service.JobCommand;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.ui.components.StatusBadge;
import com.filewatcher.ui.components.ToastNotification;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Right-hand details panel in Service Management (spec §8): General/Source/Destination/Credentials/Polling/Statistics. */
public class JobDetailsPanel extends VBox {

    private final ServiceClient client;

    public JobDetailsPanel(ServiceClient client) {
        this.client = client;
        getStyleClass().add("details-panel");
        setPadding(new Insets(14));
        setPrefWidth(320);
        showEmpty();
    }

    public void showEmpty() {
        getChildren().setAll(centeredLabel("Select a job to view its configuration and statistics."));
    }

    public void show(Job job) {
        VBox root = new VBox(14);

        root.getChildren().add(section("General",
                row("Name", job.getName()),
                row("Type", job.typeProperty().get()),
                badgeRow("Status", job)
        ));
        root.getChildren().add(section("Source", row("Path", job.sourcePathProperty().get())));
        root.getChildren().add(section("Destination", row("Path", job.destPathProperty().get())));
        root.getChildren().add(section("Credentials", row("Credential", job.credentialProperty().get())));
        root.getChildren().add(section("Polling", row("Interval", job.pollingIntervalProperty().get())));
        root.getChildren().add(section("Statistics",
                row("Files Today", String.valueOf(job.filesTodayProperty().get())),
                row("Last Transfer", job.lastTransferProperty().get())
        ));

        HBox actions = new HBox(8);
        Button save = new Button("Save");
        save.getStyleClass().addAll("btn", "btn-primary");
        save.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(save, Priority.ALWAYS);
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn");
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        actions.getChildren().addAll(save, cancel);

        Button testConn = new Button("Test Connection");
        testConn.getStyleClass().add("btn");
        testConn.setMaxWidth(Double.MAX_VALUE);
        testConn.setOnAction(e -> {
            client.sendCommand(job.getId(), JobCommand.TEST_CONNECTION)
                    .thenRun(() -> javafx.application.Platform.runLater(() ->
                            ToastNotification.show("Connection OK", job.getName(), false)));
        });

        root.getChildren().addAll(actions, testConn);
        getChildren().setAll(root);
    }

    private VBox section(String title, HBox... rows) {
        Label heading = new Label(title.toUpperCase());
        heading.getStyleClass().add("dsection-heading");
        VBox box = new VBox(4, heading);
        box.getChildren().addAll(rows);
        return box;
    }

    private HBox row(String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("drow-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label v = new Label(value);
        v.getStyleClass().add("drow-value");
        HBox box = new HBox(8, l, spacer, v);
        box.getStyleClass().add("drow");
        return box;
    }

    private HBox badgeRow(String label, Job job) {
        Label l = new Label(label);
        l.getStyleClass().add("drow-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        StatusBadge badge = new StatusBadge(job.getStatus());
        job.statusProperty().addListener((o, ov, nv) -> badge.update(nv));
        HBox box = new HBox(8, l, spacer, badge);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Label centeredLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("details-empty");
        l.setWrapText(true);
        l.setAlignment(Pos.CENTER);
        l.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(l, Priority.ALWAYS);
        return l;
    }
}
