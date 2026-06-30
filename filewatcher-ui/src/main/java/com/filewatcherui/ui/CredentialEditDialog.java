package com.filewatcherui.ui;

import com.filewatchercommon.model.CredentialMessage;
import com.filewatcherui.service.ServiceClient;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

public class CredentialEditDialog {

    private CredentialMessage saved = null;
    private final ServiceClient client;
    private final CredentialMessage editing;
    private final Stage stage = new Stage();

    private TextField     hostField, portField, userField;
    private PasswordField passwordField;
    private ComboBox<String> protocolCombo;

    public CredentialEditDialog(Window parent, ServiceClient client,
                                CredentialMessage existing) {
        this.client  = client;
        this.editing = existing;

        stage.initOwner(parent);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(existing == null ? "Add credential" : "Edit credential");
        stage.setWidth(420);
        stage.setHeight(360);
        stage.setResizable(false);

        stage.setScene(new Scene(buildForm()));
        if (existing != null) populate(existing);
    }

    public void show() { stage.showAndWait(); }

    public CredentialMessage getSaved() { return saved; }

    // ── Form ──────────────────────────────────────────────────────────────

    private VBox buildForm() {
        VBox root = new VBox(0);
        root.setPadding(new Insets(20, 24, 16, 24));
        root.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        hostField     = styledField("192.168.1.100");
        portField     = styledField("22");
        protocolCombo = new ComboBox<>();
        protocolCombo.getItems().addAll("SFTP", "FTP", "LOCAL");
        protocolCombo.setValue("SFTP");
        protocolCombo.setMaxWidth(Double.MAX_VALUE);
        protocolCombo.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4;");

        userField     = styledField("ops_user");
        passwordField = new PasswordField();
        passwordField.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 4 8 4 8;");

        GridPane conn = formGrid();
        addRow(conn, "Host",     hostField,     0);
        addRow(conn, "Port",     portField,     1);
        addRow(conn, "Protocol", protocolCombo, 2);

        GridPane auth = formGrid();
        addRow(auth, "Username", userField,     0);
        addRow(auth, "Password", passwordField, 1);

        Button cancel = styledBtn("Cancel", false);
        Button save   = styledBtn("Save",   true);
        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> doSave());

        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(
                sectionLabel("Connection"), vStrut(8),  conn,
                vStrut(14),
                sectionLabel("Authentication"), vStrut(8), auth,
                vStrut(18), btns
        );
        return root;
    }

    private void populate(CredentialMessage c) {
        hostField.setText(c.getHost());
        portField.setText(String.valueOf(c.getPort()));
        userField.setText(c.getUsername());
        passwordField.setText(c.getPassword());
        protocolCombo.setValue(c.getProtocol());
    }

    private void doSave() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        if (host.isEmpty() || user.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Host and username are required.", ButtonType.OK);
            a.setHeaderText(null);
            a.initOwner(stage);
            a.showAndWait();
            return;
        }
        CredentialMessage c = editing != null ? editing : new CredentialMessage();
        c.setHost(host);
        try { c.setPort(Integer.parseInt(portField.getText().trim())); }
        catch (NumberFormatException e) { c.setPort(22); }
        c.setUsername(user);
        c.setPassword(passwordField.getText());
        c.setProtocol(protocolCombo.getValue());
        client.saveCredential(c);
        saved = c;
        stage.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6);
        ColumnConstraints lbl = new ColumnConstraints(80);
        ColumnConstraints val = new ColumnConstraints();
        val.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(lbl, val);
        return g;
    }

    private void addRow(GridPane g, String lbl,
                        javafx.scene.Node field, int row) {
        Label l = new Label(lbl);
        l.setStyle("-fx-font-size: 11; -fx-text-fill: " +
                Theme.TEXT_SECONDARY + ";");
        g.add(l, 0, row);
        g.add(field, 1, row);
    }

    private TextField styledField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 4 8 4 8;");
        return f;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-font-size: 10; -fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        return l;
    }

    private Button styledBtn(String text, boolean primary) {
        Button b = new Button(text);
        b.setStyle(primary
                ? "-fx-background-color: " + Theme.ACCENT + ";" +
                "-fx-text-fill: white; -fx-font-weight: bold;" +
                "-fx-background-radius: 6; -fx-padding: 6 18 6 18;" +
                "-fx-cursor: hand;"
                : "-fx-background-color: " + Theme.BG_CARD + ";" +
                "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                "-fx-border-radius: 6; -fx-background-radius: 6;" +
                "-fx-padding: 6 18 6 18; -fx-cursor: hand;");
        return b;
    }

    private Region vStrut(double h) {
        Region r = new Region(); r.setPrefHeight(h); return r;
    }
}