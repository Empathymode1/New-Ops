package com.filewatcher.ui.services;

import com.filewatcher.model.CredentialInfo;
import com.filewatcher.model.Direction;
import com.filewatcher.model.Protocol;
import com.filewatcher.model.RemoteOs;
import com.filewatcher.model.TransferMode;
import com.filewatcher.model.WatchJobConfig;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.ui.components.RemoteBrowserDialog;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;import javafx.scene.control.Dialog;import javafx.scene.control.Label;import javafx.scene.control.ScrollPane;import javafx.scene.control.TextField;import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import javax.xml.transform.Source;
import java.awt.*;
import java.io.File;
import java.lang.annotation.Target;
import java.util.Optional;

/**
 * Modal form for creating or editing a watch job — backs the "+ Add" and
 * "Edit" buttons in Service Management's toolbar (spec §8).
 *
 * CONNECTION MODEL: "Source" is always THIS machine — auto-filled from
 * AppState.localHostProperty() (contract §1.1's SNAPSHOT.localHost),
 * never editable, browsed with a plain local DirectoryChooser. "Target"
 * is the other system and needs full details: Protocol, Host, Port,
 * Username, Password, browsed remotely over SFTP (contract §2.11
 * BROWSE_REMOTE). Direction just says which way files flow between
 * these two fixed roles:
 *   INBOUND  — Target → Source (download)
 *   OUTBOUND — Source → Target (upload)
 * LOCAL_TO_LOCAL needs no connection at all — Target becomes a second
 * local path.
 *
 * WIRE MAPPING (see docs/relay-monitoring-ws-contract.md §1.1's note on
 * source/dest vs. local/remote — this is the "worked example" it refers
 * to): WatchJobConfig's source*/
/*  dest fields mean "watch origin" /
 * "write destination", NOT "local" / "remote". Since INBOUND's watch
 * origin is the remote Target, Target's fields go into config.source*
 * and Source(local)'s path goes into config.destPath for INBOUND —
 * reversed from what the field names might suggest. OUTBOUND is the
 * direct mapping (Source→config.source*, Target→config.dest*). See
 * {@link #show} for the exact save-time and prefill-time mapping.
 */
public final class JobFormDialog {

    private JobFormDialog() {
    }

    /** Opens the form pre-filled with sensible defaults for a brand-new job. */
    public static Optional<WatchJobConfig> showAdd(Node owner, AppState state, ServiceClient client) {
        return show(owner, "Add Job", new WatchJobConfig(), state, client);
    }

    /**
     * Opens the form pre-filled from {@code initial} (typically {@code
     * job.getRawConfig().copy()}) for editing an existing job. Password
     * fields intentionally start blank — see WatchJobConfig's javadoc and
     * contract §2.4 for why leaving them blank means "keep unchanged".
     */
    public static Optional<WatchJobConfig> showEdit(Node owner, WatchJobConfig initial, AppState state, ServiceClient client) {
        return show(owner, "Edit Job", initial != null ? initial.copy() : new WatchJobConfig(), state, client);
    }

    private static Optional<WatchJobConfig> show(Node owner, String title, WatchJobConfig working, AppState state, ServiceClient client) {
        boolean isAdd = title.startsWith("Add");

        Dialog<WatchJobConfig> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getStyleClass().add("job-form-dialog");
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }

        ButtonType saveButtonType = new ButtonType(isAdd ? "Add Job" : "Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        Label error = new Label();
        error.setStyle("-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);

        TextField name = new TextField(working.name);

        ComboBox<Direction> direction = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(Direction.values()));
        direction.setValue(working.direction);
        direction.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Direction d) {
                if (d == null) return "";
                return switch (d) {
                    case INBOUND -> "Inbound — download: Target \u2192 Source (this machine)";
                    case OUTBOUND -> "Outbound — upload: Source (this machine) \u2192 Target";
                    case LOCAL_TO_LOCAL -> "Local to local (no remote connection)";
                };
            }
            @Override public Direction fromString(String s) { return null; }
        });

        ComboBox<TransferMode> transferMode = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(TransferMode.values()));
        transferMode.setValue(working.transferMode);
        TextField specificPattern = new TextField(working.specificPattern);
        specificPattern.setPromptText("e.g. *.xml");
        specificPattern.disableProperty().bind(transferMode.valueProperty().isNotEqualTo(TransferMode.SPECIFIC));

        // ── Source: always this machine ─────────────────────────────────────
        Label sourceHostLabel = new Label();
        sourceHostLabel.textProperty().bind(javafx.beans.binding.Bindings.concat(
                "This machine (", state.localHostProperty(), ")"));
        sourceHostLabel.setStyle("-fx-text-fill: -text-dim; -fx-font-style: italic;");
        TextField sourcePath = new TextField(working.sourcePath);
        sourcePath.setPromptText("/local/folder");
        Button browseSource = new Button("Browse\u2026");
        browseSource.setOnAction(e -> browseLocal(dialog.getDialogPane(), sourcePath));
        HBox sourcePathRow = new HBox(6, sourcePath, browseSource);
        HBox.setHgrow(sourcePath, Priority.ALWAYS);

        // ── Target: the other system ────────────────────────────────────────
        ComboBox<CredentialInfo> credPicker = credentialPicker(state.getCredentials());
        ComboBox<Protocol> protocol = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                Protocol.SFTP, Protocol.FTP, Protocol.SCP));
        protocol.setValue(working.protocol == Protocol.LOCAL ? Protocol.SFTP : working.protocol);
        Spinner<Integer> targetPort = new Spinner<>(1, 65535, defaultPortFor(protocol.getValue()));
        targetPort.setEditable(true);
        protocol.setOnAction(e -> targetPort.getValueFactory().setValue(defaultPortFor(protocol.getValue())));

        TextField targetHost = new TextField();
        targetHost.setPromptText("dcs01 or 192.168.1.10");
        TextField targetUser = new TextField();
        PasswordField targetPassword = new PasswordField();
        targetPassword.setPromptText(isAdd ? "" : "leave blank to keep unchanged");
        credPicker.setOnAction(e -> {
            CredentialInfo c = credPicker.getValue();
            if (c == null) return;
            targetHost.setText(c.getHost());
            targetPort.getValueFactory().setValue(c.getPort());
            targetUser.setText(c.getUsername());
            // Password deliberately not autofilled -- saved credentials never
            // expose it back to the client. Enter it once here, same as always.
        });
        TextField targetPath = new TextField();
        Button browseTarget = new Button("Browse\u2026");
        browseTarget.setOnAction(e -> {
            if (direction.getValue() == Direction.LOCAL_TO_LOCAL) {
                browseLocal(dialog.getDialogPane(), targetPath);
            } else {
                browseRemote(dialog.getDialogPane(), client, targetHost, targetPort, targetUser, targetPassword, targetPath);
            }
        });
        HBox targetPathRow = new HBox(6, targetPath, browseTarget);
        HBox.setHgrow(targetPath, Priority.ALWAYS);

        // Remote OS only matters for INBOUND (picks which OS-specific remote-watch
        // mechanism the backend uses -- see FileWatcherService.runInboundWatcher).
        // Left unset (null) is fine and falls back to SFTP polling -- see contract
        // §2.3's note. Auto-filled by Test Connection below when possible, but
        // still manually overridable in case detection guesses wrong.
        ComboBox<RemoteOs> remoteOs = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(RemoteOs.values()));
        remoteOs.setValue(working.remoteOs);
        remoteOs.setPromptText("Unknown (falls back to polling)");
        remoteOs.setMaxWidth(Double.MAX_VALUE);

        Label testResult = new Label();
        testResult.setWrapText(true);
        testResult.setManaged(false);
        testResult.setVisible(false);
        Button testConnection = new Button("Test Connection");
        testConnection.setOnAction(e -> {
            if (targetHost.getText() == null || targetHost.getText().isBlank()) {
                testResult.setText("Enter a host first.");
                testResult.setStyle("-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
                testResult.setManaged(true);
                testResult.setVisible(true);
                return;
            }
            boolean detectOs = direction.getValue() == Direction.INBOUND;
            testConnection.setDisable(true);
            testResult.setText("Testing\u2026");
            testResult.setStyle("-fx-text-fill: -text-dim; -fx-font-size: 12px;");
            testResult.setManaged(true);
            testResult.setVisible(true);
            client.testRawConnection(targetHost.getText().trim(), targetPort.getValue(),
                            targetUser.getText().trim(), targetPassword.getText(), detectOs)
                    .thenAccept(result -> Platform.runLater(() -> {
                        testConnection.setDisable(false);
                        if (result.success()) {
                            testResult.setText("\u2713 Connection succeeded");
                            testResult.setStyle("-fx-text-fill: -green; -fx-font-size: 12px;");
                            if (result.detectedOs() != null) {
                                try {
                                    remoteOs.setValue(RemoteOs.valueOf(result.detectedOs()));
                                } catch (IllegalArgumentException ignored) {
                                    // unrecognized value from a mismatched backend version -- leave the field alone
                                }
                            }
                        } else {
                            testResult.setText("\u2717 " + result.error());
                            testResult.setStyle("-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
                        }
                    }));
        });
        HBox testRow = new HBox(10, testConnection, testResult);

        Spinner<Integer> watchDepth = new Spinner<>(1, 20, Math.max(1, working.watchDepth));
        watchDepth.setEditable(true);
        Spinner<Integer> intervalSeconds = new Spinner<>(1, 86400, Math.max(1, working.intervalSeconds));
        intervalSeconds.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(4, 0, 4, 0));
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(130);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        int r = 0;
        r = addRow(grid, r, "Name", name);
        r = addRow(grid, r, "Direction", direction);
        r = addRow(grid, r, "Transfer Mode", transferMode);
        r = addRow(grid, r, "File Pattern", specificPattern);

        r = addSectionHeading(grid, r, "Source");
        r = addRow(grid, r, "Host", sourceHostLabel);
        r = addRow(grid, r, "Path", sourcePathRow);

        int targetSectionRow = r;
        r = addSectionHeading(grid, r, "Target");
        int credRow = r;    r = addRow(grid, r, "Use Saved Credential", credPicker);
        int protoRow = r;   r = addRow(grid, r, "Protocol", protocol);
        int hostRow = r;    r = addRow(grid, r, "Host", targetHost);
        int portRow = r;    r = addRow(grid, r, "Port", targetPort);
        int userRow = r;    r = addRow(grid, r, "Username", targetUser);
        int passRow = r;    r = addRow(grid, r, "Password", targetPassword);
        int testRowIdx = r; r = addRow(grid, r, "", testRow);
        r = addRow(grid, r, "Path", targetPathRow);
        int remoteOsRow = r; r = addRow(grid, r, "Remote OS", remoteOs);

        r = addSectionHeading(grid, r, "Schedule");
        if (isAdd) r = addRow(grid, r, "Interval (seconds)", intervalSeconds);
        r = addRow(grid, r, "Watch Depth", watchDepth);

        Runnable updateVisibility = () -> {
            boolean remote = direction.getValue() != Direction.LOCAL_TO_LOCAL;
            boolean inbound = direction.getValue() == Direction.INBOUND;
            setRowVisible(grid, targetSectionRow, true); // "Target" heading always shown -- label just says what it means
            setRowVisible(grid, credRow, remote);
            setRowVisible(grid, protoRow, remote);
            setRowVisible(grid, hostRow, remote);
            setRowVisible(grid, portRow, remote);
            setRowVisible(grid, userRow, remote);
            setRowVisible(grid, passRow, remote);
            setRowVisible(grid, testRowIdx, remote);
            setRowVisible(grid, remoteOsRow, inbound);
            targetPath.setPromptText(remote ? "/inbound (on target)" : "/local/folder");
        };
        direction.setOnAction(e -> updateVisibility.run());
        updateVisibility.run();

        // Prefill Target/Source from working, per the direction->field mapping
        // documented in the class javadoc.
        if (working.direction == Direction.INBOUND) {
            targetHost.setText(working.sourceHost);
            if (working.sourcePort > 0) targetPort.getValueFactory().setValue(working.sourcePort);
            targetUser.setText(working.sourceUser);
            targetPath.setText(working.sourcePath);
            sourcePath.setText(working.destPath);
        } else if (working.direction == Direction.OUTBOUND) {
            sourcePath.setText(working.sourcePath);
            targetHost.setText(working.destHost);
            if (working.destPort > 0) targetPort.getValueFactory().setValue(working.destPort);
            targetUser.setText(working.destUser);
            targetPath.setText(working.destPath);
        } else {
            sourcePath.setText(working.sourcePath);
            targetPath.setText(working.destPath);
        }

        VBox content = new VBox(10, error, grid);
        content.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(460);
        scroll.setPrefViewportWidth(460);
        dialog.getDialogPane().setContent(scroll);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = validate(name.getText(), sourcePath.getText(), targetPath.getText(),
                    transferMode.getValue(), specificPattern.getText(), direction.getValue(), targetHost.getText());
            if (problem != null) {
                error.setText(problem);
                error.setManaged(true);
                error.setVisible(true);
                event.consume();
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType != saveButtonType) return null;
            Direction d = direction.getValue();
            WatchJobConfig result = new WatchJobConfig();
            result.name = name.getText().trim();
            result.direction = d;
            result.transferMode = transferMode.getValue();
            result.protocol = d == Direction.LOCAL_TO_LOCAL ? Protocol.LOCAL : protocol.getValue();
            result.specificPattern = transferMode.getValue() == TransferMode.SPECIFIC
                    ? specificPattern.getText().trim() : null;
            result.intervalSeconds = isAdd ? intervalSeconds.getValue() : 0; // add-only, contract §2.4
            result.watchDepth = watchDepth.getValue();

            // See class javadoc for why this mapping is reversed for INBOUND.
            if (d == Direction.INBOUND) {
                result.sourceHost = blankToNull(targetHost.getText());
                result.sourcePort = targetPort.getValue();
                result.sourceUser = blankToNull(targetUser.getText());
                result.sourcePassword = targetPassword.getText();
                result.sourcePath = targetPath.getText().trim();
                result.destHost = null;
                result.destPath = sourcePath.getText().trim();
                result.remoteOs = remoteOs.getValue();
            } else if (d == Direction.OUTBOUND) {
                result.sourceHost = null;
                result.sourcePath = sourcePath.getText().trim();
                result.destHost = blankToNull(targetHost.getText());
                result.destPort = targetPort.getValue();
                result.destUser = blankToNull(targetUser.getText());
                result.destPassword = targetPassword.getText();
                result.destPath = targetPath.getText().trim();
            } else {
                result.sourceHost = null;
                result.sourcePath = sourcePath.getText().trim();
                result.destHost = null;
                result.destPath = targetPath.getText().trim();
            }
            return result;
        });

        return dialog.showAndWait();
    }

    private static int defaultPortFor(Protocol p) {
        return p == Protocol.FTP ? 21 : 22; // SFTP/SCP both use SSH's port 22
    }

    private static void browseLocal(Node owner, TextField pathTarget) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Local Folder");
        if (pathTarget.getText() != null && !pathTarget.getText().isBlank()) {
            File initial = new File(pathTarget.getText());
            if (initial.isDirectory()) chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(owner.getScene() != null ? owner.getScene().getWindow() : null);
        if (selected != null) pathTarget.setText(selected.getAbsolutePath());
    }

    private static void browseRemote(Node owner, ServiceClient client, TextField host, Spinner<Integer> port,
                                      TextField user, PasswordField password, TextField pathTarget) {
        if (host.getText() == null || host.getText().isBlank()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Enter the Target's host (and username/password) first.", ButtonType.OK);
            a.setHeaderText("Nothing to connect to yet");
            a.showAndWait();
            return;
        }
        RemoteBrowserDialog.show(owner, client, host.getText().trim(), port.getValue(), user.getText().trim(),
                        password.getText(), pathTarget.getText())
                .ifPresent(selected -> Platform.runLater(() -> pathTarget.setText(selected)));
    }

    private static void setRowVisible(GridPane grid, int row, boolean visible) {
        for (Node n : grid.getChildren()) {
            Integer rowIndex = GridPane.getRowIndex(n);
            if (rowIndex != null && rowIndex == row) {
                n.setVisible(visible);
                n.setManaged(visible);
            }
        }
    }

    private static String validate(String name, String sourcePath, String targetPath,
                                    TransferMode mode, String specificPattern, Direction direction, String targetHost) {
        if (name == null || name.isBlank()) return "Name is required.";
        if (sourcePath == null || sourcePath.isBlank()) return "Source path is required.";
        if (targetPath == null || targetPath.isBlank()) return "Target path is required.";
        if (mode == TransferMode.SPECIFIC && (specificPattern == null || specificPattern.isBlank())) {
            return "A file pattern is required when transfer mode is Specific.";
        }
        if (direction != Direction.LOCAL_TO_LOCAL && (targetHost == null || targetHost.isBlank())) {
            return "Target host is required.";
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** A picker over the saved Credentials vault, for autofilling host/user without retyping them per job. */
    private static ComboBox<CredentialInfo> credentialPicker(ObservableList<CredentialInfo> credentials) {
        ComboBox<CredentialInfo> picker = new ComboBox<>(credentials);
        picker.setPromptText(credentials.isEmpty() ? "No saved credentials yet" : "— choose to autofill —");
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CredentialInfo c) {
                return c == null ? "" : c.getUsername() + "@" + c.getHost();
            }

            @Override
            public CredentialInfo fromString(String s) {
                return null; // not editable — selection-only
            }
        });
        return picker;
    }

    private static int addRow(GridPane grid, int row, String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("drow-label");
        if (field instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row, l, field);
        return row + 1;
    }

    private static int addSectionHeading(GridPane grid, int row, String text) {
        Label heading = new Label(text.toUpperCase());
        heading.getStyleClass().add("dsection-heading");
        GridPane.setColumnSpan(heading, 2);
        grid.add(heading, 0, row);
        GridPane.setMargin(heading, new Insets(8, 0, 0, 0));
        return row + 1;
    }
}
