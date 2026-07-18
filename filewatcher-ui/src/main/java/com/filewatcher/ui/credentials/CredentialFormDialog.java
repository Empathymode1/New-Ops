package com.filewatcher.ui.credentials;

import com.filewatcher.model.CredentialConfig;
import com.filewatcher.model.Protocol;
import com.filewatcher.service.ServiceClient;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * Modal form for creating or editing a saved credential — backs the "+ Add"
 * and "Edit" buttons on the Credentials page, the same pattern
 * {@link com.filewatcher.ui.services.JobFormDialog} uses for jobs.
 *
 * Produces a {@link CredentialConfig}, matching the contract's ADD_CREDENTIAL
 * / UPDATE_CREDENTIAL "credential" shape exactly
 * (docs/relay-monitoring-ws-contract.md §2.6/§2.7) — the caller calls
 * {@code client.addCredential(...)}/{@code client.updateCredential(...)}
 * with the result; this class only builds and validates the config.
 */
public final class CredentialFormDialog {

    private CredentialFormDialog() {
    }

    /** Opens the form pre-filled with sensible defaults for a brand-new credential. */
    public static Optional<CredentialConfig> showAdd(Node owner, ServiceClient client) {
        return show(owner, "Add Credential", new CredentialConfig(), client);
    }

    /**
     * Opens the form pre-filled from {@code initial} for editing an existing
     * credential — including the actual password (contract §1.5 sends it
     * back in plain text now, on explicit request; masked by default in
     * this form with a show/hide toggle). Leaving the password field blank
     * still means "keep the stored password unchanged" (§2.7) — clearing
     * it intentionally, not just "it happened to start empty".
     */
    public static Optional<CredentialConfig> showEdit(Node owner, CredentialConfig initial, ServiceClient client) {
        return show(owner, "Edit Credential", initial != null ? initial.copy() : new CredentialConfig(), client);
    }

    private static Optional<CredentialConfig> show(Node owner, String title, CredentialConfig working, ServiceClient client) {
        Dialog<CredentialConfig> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getStyleClass().add("credential-form-dialog");
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }

        boolean isAdd = title.startsWith("Add");
        ButtonType saveButtonType = new ButtonType(isAdd ? "Add Credential" : "Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        Label error = new Label();
        error.setStyle("-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);

        TextField host = new TextField(working.host);
        host.setPromptText("dcs01");
        Spinner<Integer> port = new Spinner<>(1, 65535, working.port <= 0 ? 22 : working.port);
        port.setEditable(true);
        TextField username = new TextField(working.username);
        username.setPromptText("svc-account");

        // Password now round-trips for real (contract §1.5 sends it back, on
        // explicit request -- see that section's note on the tradeoff), so
        // Edit actually has something to prefill. Masked by default via the
        // usual PasswordField, with a show/hide toggle rather than displaying
        // it in the clear by default.
        PasswordField passwordHidden = new PasswordField();
        passwordHidden.setText(working.password);
        passwordHidden.setPromptText(isAdd ? "" : "leave blank to keep unchanged");
        TextField passwordVisible = new TextField();
        passwordVisible.textProperty().bindBidirectional(passwordHidden.textProperty());
        passwordVisible.setPromptText(passwordHidden.getPromptText());
        passwordVisible.setManaged(false);
        passwordVisible.setVisible(false);
        Button togglePasswordVisible = new Button("\uD83D\uDC41");
        togglePasswordVisible.getStyleClass().add("btn");
        togglePasswordVisible.setOnAction(e -> {
            boolean nowShowing = !passwordVisible.isVisible();
            passwordVisible.setVisible(nowShowing);
            passwordVisible.setManaged(nowShowing);
            passwordHidden.setVisible(!nowShowing);
            passwordHidden.setManaged(!nowShowing);
        });
        javafx.scene.layout.StackPane passwordStack = new javafx.scene.layout.StackPane(passwordHidden, passwordVisible);
        HBox.setHgrow(passwordStack, Priority.ALWAYS);
        HBox passwordRow = new HBox(6, passwordStack, togglePasswordVisible);

        ComboBox<Protocol> protocol = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(Protocol.values()));
        protocol.setValue(working.protocol);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(4, 0, 4, 0));
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(110);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        int r = 0;
        r = addRow(grid, r, "Host", host);
        r = addRow(grid, r, "Port", port);
        r = addRow(grid, r, "Username", username);
        r = addRow(grid, r, "Password", passwordRow);
        r = addRow(grid, r, "Protocol", protocol);

        Label testResult = new Label();
        testResult.setWrapText(true);
        testResult.setManaged(false);
        testResult.setVisible(false);

        // Tracks whether the connection has been tested since the last time any
        // field changed, and whether that test passed -- used both to show a
        // status message and to decide whether Save should ask for confirmation.
        boolean[] testedOk = {false};
        Button testButton = new Button("Test Connection");
        testButton.getStyleClass().add("btn");
        testButton.setOnAction(e -> {
            testButton.setDisable(true);
            testResult.setText("Testing…");
            testResult.setStyle("-fx-text-fill: -text-dim; -fx-font-size: 12px;");
            testResult.setManaged(true);
            testResult.setVisible(true);
            client.testRawConnection(host.getText().trim(), port.getValue(), username.getText().trim(), passwordHidden.getText(), false)
                    .thenAccept(result -> Platform.runLater(() -> {
                        testButton.setDisable(false);
                        testedOk[0] = result.success();
                        testResult.setText(result.success() ? "\u2713 Connection succeeded" : "\u2717 " + result.error());
                        testResult.setStyle(result.success()
                                ? "-fx-text-fill: -green; -fx-font-size: 12px;"
                                : "-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
                    }));
        });
        // Any field change invalidates a previous successful test.
        for (TextField f : new TextField[]{host, username}) {
            f.textProperty().addListener((o, ov, nv) -> testedOk[0] = false);
        }
        passwordHidden.textProperty().addListener((o, ov, nv) -> testedOk[0] = false);
        port.valueProperty().addListener((o, ov, nv) -> testedOk[0] = false);

        HBox testRow = new HBox(10, testButton, testResult);
        testRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, error, grid, testRow);
        content.setPadding(new Insets(12));
        content.setPrefWidth(360);
        dialog.getDialogPane().setContent(content);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = validate(host.getText(), username.getText());
            if (problem != null) {
                error.setText(problem);
                error.setManaged(true);
                error.setVisible(true);
                event.consume();
                return;
            }
            if (!testedOk[0]) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "This connection hasn't been tested (or the last test failed). Save it anyway?",
                        ButtonType.YES, ButtonType.NO);
                confirm.setHeaderText("Connection not verified");
                if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                    event.consume();
                }
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType != saveButtonType) return null;
            CredentialConfig result = new CredentialConfig();
            result.host = host.getText().trim();
            result.port = port.getValue();
            result.username = username.getText().trim();
            result.password = passwordHidden.getText(); // blank means "unchanged" on edit — see contract §2.7
            result.protocol = protocol.getValue();
            return result;
        });

        return dialog.showAndWait();
    }

    private static String validate(String host, String username) {
        if (host == null || host.isBlank()) return "Host is required.";
        if (username == null || username.isBlank()) return "Username is required.";
        return null;
    }

    private static int addRow(GridPane grid, int row, String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("drow-label");
        if (field instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row, l, field);
        return row + 1;
    }
}
