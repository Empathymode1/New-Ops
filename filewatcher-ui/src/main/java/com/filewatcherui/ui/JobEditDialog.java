package com.filewatcherui.ui;

import com.filewatchercommon.model.CredentialMessage;
import com.filewatchercommon.model.WatchJob;
import com.filewatcherui.service.ServiceClient;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class JobEditDialog {

    private final ServiceClient client;
    private final WatchJob editing;
    private List<CredentialMessage> cachedCredentials = new ArrayList<>();

    private final Stage stage = new Stage();

    // Form fields
    private TextField nameField, srcHostField, srcPortField, srcUserField,
            srcPathField, dstHostField, dstPortField, dstUserField,
            dstPathField, patternField;
    private PasswordField srcPasswordField, dstPasswordField;
    private ComboBox<WatchJob.Direction>    directionCombo;
    private ComboBox<WatchJob.TransferMode> modeCombo;
    private ComboBox<WatchJob.Protocol>     protocolCombo;
    private Spinner<Integer> intervalSpinner, depthSpinner;

    private VBox srcHostPanel, dstHostPanel;

    public JobEditDialog(Window parent, ServiceClient client, WatchJob existing) {
        this.client  = client;
        this.editing = existing;

        client.addCredentialListener(creds -> cachedCredentials = creds);
        client.getCredentials();

        stage.initOwner(parent);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(existing == null ? "New Watch Job" : "Edit Watch Job");
        stage.setWidth(560);
        stage.setHeight(700);
        stage.setResizable(false);

        ScrollPane scroll = new ScrollPane(buildForm());
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_CARD + ";" +
                "-fx-background: " + Theme.BG_CARD + ";");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        stage.setScene(new Scene(scroll));

        if (existing != null) populate(existing);
        updateFieldVisibility();
    }

    public void show() { stage.show(); }

    // ── Form ──────────────────────────────────────────────────────────────

    private VBox buildForm() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");
        root.setPadding(new Insets(24, 28, 20, 28));

        // General
        root.getChildren().addAll(
                sectionLabel("General"), vStrut(8),
                buildGeneralGrid(),
                vStrut(16),

                // Source
                sectionLabel("Source"), vStrut(8));

        srcHostField     = styledField("192.168.1.100");
        srcPortField     = styledField("22");
        srcUserField     = styledField("ops");
        srcPasswordField = styledPassword();
        srcPathField     = styledField("/data/inbound");

        srcHostPanel = new VBox(6);
        srcHostPanel.getChildren().addAll(
                formRow("Host",     srcHostField),
                formRow("Port",     srcPortField),
                formRow("User",     srcUserField),
                formRow("Password", srcPasswordField),
                buildLoadCredRow(srcHostField, srcPortField,
                        srcUserField, srcPasswordField));

        HBox srcPathRow = buildPathRow(srcPathField, true);

        root.getChildren().addAll(
                srcHostPanel, vStrut(4),
                formRow("Source path", srcPathRow),
                vStrut(16),

                // Destination
                sectionLabel("Destination"), vStrut(8));

        dstHostField     = styledField("192.168.1.200");
        dstPortField     = styledField("22");
        dstUserField     = styledField("deploy");
        dstPasswordField = styledPassword();
        dstPathField     = styledField("/data/processed");

        dstHostPanel = new VBox(6);
        dstHostPanel.getChildren().addAll(
                formRow("Host",     dstHostField),
                formRow("Port",     dstPortField),
                formRow("User",     dstUserField),
                formRow("Password", dstPasswordField),
                buildLoadCredRow(dstHostField, dstPortField,
                        dstUserField, dstPasswordField));

        HBox dstPathRow = buildPathRow(dstPathField, false);

        root.getChildren().addAll(
                dstHostPanel, vStrut(4),
                formRow("Destination path", dstPathRow),
                vStrut(16),

                // Options
                sectionLabel("Options"), vStrut(8),
                buildOptionsGrid(),
                vStrut(20),

                // Buttons
                buildButtonRow()
        );

        return root;
    }

    private GridPane buildGeneralGrid() {
        nameField     = styledField("ops-inbound-sftp");
        directionCombo = styledCombo(WatchJob.Direction.values());
        modeCombo      = styledCombo(WatchJob.TransferMode.values());
        protocolCombo  = styledCombo(WatchJob.Protocol.values());

        directionCombo.setOnAction(e -> updateFieldVisibility());

        GridPane grid = formGrid();
        addRow(grid, "Job name",      nameField,      0);
        addRow(grid, "Direction",     directionCombo, 1);
        addRow(grid, "Transfer mode", modeCombo,      2);
        addRow(grid, "Protocol",      protocolCombo,  3);
        return grid;
    }

    private GridPane buildOptionsGrid() {
        patternField    = styledField("*.csv, *.json");
        intervalSpinner = new Spinner<>(5, 3600, 30, 5);
        depthSpinner    = new Spinner<>(0, 10, 1, 1);
        styleSpinner(intervalSpinner);
        styleSpinner(depthSpinner);

        GridPane grid = formGrid();
        addRow(grid, "File pattern",     patternField,    0);
        addRow(grid, "Poll interval (s)", intervalSpinner, 1);
        addRow(grid, "Watch depth",      depthSpinner,    2);
        return grid;
    }

    private HBox buildButtonRow() {
        Button cancel = styledBtn("Cancel", "ghost");
        Button save   = styledBtn("Save job", "primary");

        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> save());

        HBox row = new HBox(8, cancel, save);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private HBox buildLoadCredRow(TextField host, TextField port,
                                  TextField user, PasswordField pw) {
        Button btn = styledBtn("Load saved credential", "ghost");
        btn.setOnAction(e -> {
            if (cachedCredentials.isEmpty()) {
                alert("No saved credentials yet. Add one in the Credentials tab.");
                return;
            }
            List<String> labels = cachedCredentials.stream()
                    .map(c -> c.getUsername() + "@" + c.getHost() + ":" + c.getPort())
                    .toList();
            ChoiceDialog<String> dlg = new ChoiceDialog<>(labels.get(0), labels);
            dlg.setHeaderText("Select a saved credential");
            dlg.showAndWait().ifPresent(choice -> {
                int idx = labels.indexOf(choice);
                if (idx < 0) return;
                CredentialMessage cred = cachedCredentials.get(idx);
                host.setText(cred.getHost());
                port.setText(String.valueOf(cred.getPort()));
                user.setText(cred.getUsername());
                pw.setText(cred.getPassword());
            });
        });
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().add(btn);
        return row;
    }

    private HBox buildPathRow(TextField pathField, boolean isSource) {
        Button browse = styledBtn("...", "ghost");
        browse.setOnAction(e -> handleBrowse(pathField, isSource));
        HBox row = new HBox(4, pathField, browse);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        return row;
    }

    // ── Browse ────────────────────────────────────────────────────────────

    private void handleBrowse(TextField target, boolean isSource) {
        WatchJob.Direction dir = directionCombo.getValue();
        boolean isRemote = isSource
                ? dir == WatchJob.Direction.INBOUND
                : dir == WatchJob.Direction.OUTBOUND;
        if (isRemote) browseRemote(target, isSource);
        else          browseLocal(target);
    }

    private void browseLocal(TextField target) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select folder");
        String current = target.getText().trim();
        if (!current.isEmpty()) {
            File f = new File(current);
            if (f.exists()) dc.setInitialDirectory(f);
        }
        File chosen = dc.showDialog(stage);
        if (chosen != null) target.setText(chosen.getAbsolutePath());
    }

    private void browseRemote(TextField target, boolean isSource) {
        TextField    hostField = isSource ? srcHostField     : dstHostField;
        TextField    portField = isSource ? srcPortField     : dstPortField;
        TextField    userField = isSource ? srcUserField     : dstUserField;
        PasswordField pwField  = isSource ? srcPasswordField : dstPasswordField;
        WatchJob.Protocol protocol = protocolCombo.getValue();

        String host = hostField.getText().trim();
        String user = userField.getText().trim();

        if (host.isEmpty() || user.isEmpty()) {
            alert("Please fill in the host and username before browsing.");
            return;
        }

        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException ex) { port = 22; }
        final int finalPort = port;
        String password  = pwField.getText();
        String startPath = target.getText().trim().isEmpty()
                ? "/" : target.getText().trim();

        // Connecting dialog
        Stage connecting = buildConnectingStage();
        connecting.show();

        client.listRemoteDirectory(protocol, host, finalPort, user, password, startPath,
                entries -> {
                    javafx.application.Platform.runLater(() -> {
                        connecting.close();
                        RemoteBrowserDialog dlg = new RemoteBrowserDialog(
                                stage, client, protocol, host, finalPort,
                                user, password, startPath, entries);
                        dlg.show();
                        String chosen = dlg.getSelectedPath();
                        if (chosen != null) target.setText(chosen);
                    });
                },
                error -> javafx.application.Platform.runLater(() -> {
                    connecting.close();
                    alert("Could not connect to " + host + ":" + finalPort + "\n\n" + error);
                })
        );
    }

    private Stage buildConnectingStage() {
        Stage s = new Stage();
        s.initOwner(stage);
        s.initModality(Modality.APPLICATION_MODAL);
        s.setTitle("Connecting...");
        s.setResizable(false);

        ProgressBar bar = new ProgressBar();
        bar.setProgress(-1);
        bar.setPrefWidth(260);

        Label msg = new Label("Connecting to remote server...");
        msg.setStyle("-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        Button cancel = styledBtn("Cancel", "ghost");
        cancel.setOnAction(e -> s.close());

        VBox box = new VBox(12, msg, bar, cancel);
        box.setPadding(new Insets(20, 24, 20, 24));
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");

        s.setScene(new Scene(box));
        s.sizeToScene();
        return s;
    }

    // ── Visibility ────────────────────────────────────────────────────────

    private void updateFieldVisibility() {
        if (directionCombo == null) return;
        WatchJob.Direction dir = directionCombo.getValue();
        if (dir == null) return;
        srcHostPanel.setVisible(dir == WatchJob.Direction.INBOUND);
        srcHostPanel.setManaged(dir == WatchJob.Direction.INBOUND);
        dstHostPanel.setVisible(dir == WatchJob.Direction.OUTBOUND);
        dstHostPanel.setManaged(dir == WatchJob.Direction.OUTBOUND);
    }

    // ── Populate ──────────────────────────────────────────────────────────

    private void populate(WatchJob j) {
        nameField.setText(j.getName());
        directionCombo.setValue(j.getDirection());
        modeCombo.setValue(j.getTransferMode());
        protocolCombo.setValue(j.getProtocol());

        srcHostField.setText(nullSafe(j.getSourceHost()));
        srcPortField.setText(String.valueOf(j.getSourcePort()));
        srcUserField.setText(nullSafe(j.getSourceUser()));
        srcPasswordField.setText(nullSafe(j.getSourcePassword()));
        srcPathField.setText(nullSafe(j.getSourcePath()));

        dstHostField.setText(nullSafe(j.getDestHost()));
        dstPortField.setText(String.valueOf(j.getDestPort()));
        dstUserField.setText(nullSafe(j.getDestUser()));
        dstPasswordField.setText(nullSafe(j.getDestPassword()));
        dstPathField.setText(nullSafe(j.getDestPath()));

        patternField.setText(nullSafe(j.getSpecificPattern()));
        intervalSpinner.getValueFactory().setValue(j.getIntervalSeconds());
        depthSpinner.getValueFactory().setValue(j.getWatchDepth());
    }

    // ── Save ──────────────────────────────────────────────────────────────

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { alert("Job name is required."); return; }

        WatchJob job = editing != null ? editing : new WatchJob();
        job.setName(name);
        job.setDirection(directionCombo.getValue());
        job.setTransferMode(modeCombo.getValue());
        job.setProtocol(protocolCombo.getValue());

        job.setSourceHost(srcHostField.getText().trim());
        try { job.setSourcePort(Integer.parseInt(srcPortField.getText().trim())); }
        catch (NumberFormatException e) { job.setSourcePort(22); }
        job.setSourceUser(srcUserField.getText().trim());
        job.setSourcePassword(srcPasswordField.getText());
        job.setSourcePath(srcPathField.getText().trim());

        job.setDestHost(dstHostField.getText().trim());
        try { job.setDestPort(Integer.parseInt(dstPortField.getText().trim())); }
        catch (NumberFormatException e) { job.setDestPort(22); }
        job.setDestUser(dstUserField.getText().trim());
        job.setDestPassword(dstPasswordField.getText());
        job.setDestPath(dstPathField.getText().trim());

        job.setSpecificPattern(patternField.getText().trim());
        job.setIntervalSeconds(intervalSpinner.getValue());
        job.setWatchDepth(depthSpinner.getValue());

        if (editing == null) client.addJob(job);
        else                 client.updateJob(job);

        stage.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(10);
        ColumnConstraints label = new ColumnConstraints(110);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(label, field);
        return g;
    }

    private HBox formRow(String labelText, javafx.scene.Node field) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_SECONDARY + ";");
        lbl.setMinWidth(110);
        HBox row = new HBox(8, lbl, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void addRow(GridPane g, String lbl, javafx.scene.Node field, int row) {
        Label l = new Label(lbl);
        l.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_SECONDARY + ";");
        g.add(l, 0, row);
        g.add(field, 1, row);
    }

    private TextField styledField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 6 10 6 10;" +
                        "-fx-font-size: 13;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        return f;
    }

    private PasswordField styledPassword() {
        PasswordField f = new PasswordField();
        f.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 6 10 6 10;" +
                        "-fx-font-size: 13;");
        return f;
    }

    private <T> ComboBox<T> styledCombo(T[] items) {
        ComboBox<T> c = new ComboBox<>();
        c.getItems().addAll(items);
        if (items.length > 0) c.setValue(items[0]);
        c.setMaxWidth(Double.MAX_VALUE);
        c.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-font-size: 13;");
        return c;
    }

    private void styleSpinner(Spinner<Integer> s) {
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-font-size: 13;");
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
                "-fx-font-size: 10;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_MUTED + ";" +
                        "-fx-padding: 4 0 4 0;");
        return l;
    }

    private Button styledBtn(String text, String type) {
        Button b = new Button(text);
        b.setStyle(switch (type) {
            case "primary" ->
                    "-fx-background-color: " + Theme.ACCENT + ";" +
                            "-fx-text-fill: white;" +
                            "-fx-font-weight: bold;" +
                            "-fx-background-radius: 6;" +
                            "-fx-padding: 7 16 7 16;" +
                            "-fx-cursor: hand;";
            case "danger" ->
                    "-fx-background-color: " + Theme.DANGER + ";" +
                            "-fx-text-fill: white;" +
                            "-fx-font-weight: bold;" +
                            "-fx-background-radius: 6;" +
                            "-fx-padding: 7 16 7 16;" +
                            "-fx-cursor: hand;";
            default ->
                    "-fx-background-color: " + Theme.BG_CARD + ";" +
                            "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                            "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                            "-fx-border-radius: 6;" +
                            "-fx-background-radius: 6;" +
                            "-fx-padding: 6 14 6 14;" +
                            "-fx-cursor: hand;";
        });
        return b;
    }

    private Region vStrut(double h) {
        Region r = new Region(); r.setPrefHeight(h); return r;
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
}