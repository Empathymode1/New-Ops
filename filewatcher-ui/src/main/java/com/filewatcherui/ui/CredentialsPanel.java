package com.filewatcherui.ui;

import com.filewatchercommon.model.CredentialMessage;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CredentialsPanel {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ServiceClient client;
    private List<CredentialMessage> allCredentials = new ArrayList<>();
    private CredentialMessage selected = null;
    private boolean editMode = false;

    private final BorderPane root = new BorderPane();
    private final ListView<CredentialMessage> credList = new ListView<>();
    private TextField searchField;

    // Detail fields
    private Label detailTitle, detailSub;
    private Label fieldHost, fieldPort, fieldUser, pwDots;
    private TextField editHost, editPort, editUser;
    private PasswordField editPassword;
    private ComboBox<String> editProtocol;
    private FlowPane usedByPanel;
    private Button showPwBtn, editBtn, testBtn, deleteBtn, saveBtn, cancelBtn;
    private boolean pwVisible = false;

    // Card stacks for view/edit toggle
    private StackPane hostStack, portStack, userStack, pwStack, protocolStack;

    public CredentialsPanel(ServiceClient client) {
        this.client = client;
        root.setStyle("-fx-background-color: " + Theme.BG_BASE + ";");
        root.setTop(buildToolbar());
        root.setCenter(buildSplit());

        client.addCredentialListener(creds -> Platform.runLater(() -> {
            allCredentials = new ArrayList<>(creds);
            applyFilter();
        }));
    }

    public Region getRoot() { return root; }

    public void loadFromService() { client.getCredentials(); }

    // ── Toolbar ───────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(8, 12, 8, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-width: 0 0 1 0;");

        searchField = new TextField();
        searchField.setPromptText("Filter by host or username...");
        searchField.setStyle(
                "-fx-background-color: " + Theme.BG_SURFACE + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 4 8 4 8;");
        searchField.textProperty().addListener((obs, o, n) -> applyFilter());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button addBtn = styledBtn("+ Add credential", "primary");
        addBtn.setOnAction(e -> openAddDialog());

        bar.getChildren().addAll(searchField, addBtn);
        return bar;
    }

    // ── Split ─────────────────────────────────────────────────────────────

    private SplitPane buildSplit() {
        credList.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");
        credList.setCellFactory(lv -> new CredCell());
        credList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, cred) -> {
                    selected = cred;
                    editMode = false;
                    refreshDetail();
                });

        ScrollPane listScroll = new ScrollPane(credList);
        listScroll.setFitToWidth(true);
        listScroll.setFitToHeight(true);
        listScroll.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");

        ScrollPane detailScroll = new ScrollPane(buildDetailPane());
        detailScroll.setFitToWidth(true);
        detailScroll.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SplitPane split = new SplitPane(listScroll, detailScroll);
        split.setDividerPositions(0.3);
        return split;
    }

    // ── Detail pane ───────────────────────────────────────────────────────

    private VBox buildDetailPane() {
        VBox p = new VBox(0);
        p.setPadding(new Insets(20, 22, 20, 22));
        p.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        // Header
        detailTitle = new Label("Select a credential");
        detailTitle.setStyle(
                "-fx-font-size: 15; -fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        detailSub = new Label("—");
        detailSub.setStyle("-fx-font-size: 11; -fx-text-fill: " +
                Theme.TEXT_MUTED + ";");

        p.getChildren().addAll(
                detailTitle, vStrut(3), detailSub,
                vStrut(14), separator(), vStrut(14),

                sectionLabel("Connection"),
                vStrut(8),
                buildConnectionGrid(),
                vStrut(14), separator(), vStrut(14),

                sectionLabel("Authentication"),
                vStrut(8),
                buildAuthGrid(),
                vStrut(14), separator(), vStrut(10),

                sectionLabel("Used by jobs"),
                vStrut(6)
        );

        usedByPanel = new FlowPane(6, 4);
        usedByPanel.setStyle("-fx-background-color: transparent;");

        p.getChildren().addAll(
                usedByPanel,
                vStrut(16), separator(), vStrut(12),
                buildActionRow()
        );

        return p;
    }

    private GridPane buildConnectionGrid() {
        fieldHost = readonlyLabel("—");
        fieldPort = readonlyLabel("—");
        editHost  = editField("");
        editPort  = editField("");
        editProtocol = new ComboBox<>();
        editProtocol.getItems().addAll("SFTP", "FTP", "LOCAL");
        editProtocol.setValue("SFTP");
        Label protoView = readonlyLabel("—");

        hostStack     = stack(fieldHost, editHost);
        portStack     = stack(fieldPort, editPort);
        protocolStack = stack(protoView, editProtocol);

        GridPane grid = detailGrid();
        addDetailRow(grid, "Hostname / IP", hostStack,     0);
        addDetailRow(grid, "Port",          portStack,     1);
        addDetailRow(grid, "Protocol",      protocolStack, 2);
        return grid;
    }

    private VBox buildAuthGrid() {
        fieldUser    = readonlyLabel("—");
        pwDots       = readonlyLabel("********");
        editUser     = editField("");
        editPassword = new PasswordField();
        styleField(editPassword);

        userStack = stack(fieldUser, editUser);
        pwStack   = stack(pwDots, editPassword);

        GridPane grid = detailGrid();
        addDetailRow(grid, "Username", userStack, 0);

        // Password row with show/copy
        showPwBtn = smallBtn("Show");
        showPwBtn.setOnAction(e -> togglePassword());
        Button copyBtn = smallBtn("Copy");
        copyBtn.setOnAction(e -> {
            if (selected != null) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        new javafx.scene.input.ClipboardContent() {{
                            putString(selected.getPassword());
                        }});
            }
        });

        HBox pwBtns = new HBox(4, showPwBtn, copyBtn);
        pwBtns.setAlignment(Pos.CENTER_RIGHT);

        HBox pwRight = new HBox(6, pwStack, pwBtns);
        HBox.setHgrow(pwStack, Priority.ALWAYS);



        VBox authBox = new VBox(8, grid);

        Label pwLabel = new Label("Password");
        pwLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        HBox pwRow = new HBox(8, pwLabel, pwRight);
        pwLabel.setMinWidth(120);
        HBox.setHgrow(pwRight, Priority.ALWAYS);
        pwRow.setAlignment(Pos.CENTER_LEFT);
        authBox.getChildren().add(pwRow);
        return authBox;
    }

    private HBox buildActionRow() {
        editBtn   = actionBtn("Edit",           "primary");
        testBtn   = actionBtn("Test connection","ghost");
        deleteBtn = actionBtn("Delete",         "danger");
        saveBtn   = actionBtn("Save",           "success");
        cancelBtn = actionBtn("Cancel",         "ghost");

        editBtn.setOnAction(e   -> setEditMode(true));
        cancelBtn.setOnAction(e -> setEditMode(false));
        saveBtn.setOnAction(e   -> saveEdits());
        testBtn.setOnAction(e   -> testConnection());
        deleteBtn.setOnAction(e -> deleteSelected());

        HBox row = new HBox(8,
                editBtn, testBtn, deleteBtn, saveBtn, cancelBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    private void applyFilter() {
        String q = searchField.getText().trim().toLowerCase();
        List<CredentialMessage> filtered = allCredentials.stream()
                .filter(c -> q.isEmpty()
                        || c.getHost().toLowerCase().contains(q)
                        || c.getUsername().toLowerCase().contains(q))
                .collect(Collectors.toList());
        credList.getItems().setAll(filtered);
        if (selected != null && filtered.contains(selected))
            credList.getSelectionModel().select(selected);
    }

    private void refreshDetail() {
        boolean has = selected != null;
        editBtn.setVisible(has && !editMode);
        testBtn.setVisible(has && !editMode);
        deleteBtn.setVisible(has && !editMode);
        saveBtn.setVisible(has && editMode);
        cancelBtn.setVisible(has && editMode);

        if (!has) {
            detailTitle.setText("Select a credential");
            detailSub.setText("—");
            fieldHost.setText("—");
            fieldPort.setText("—");
            fieldUser.setText("—");
            pwDots.setText("********");
            usedByPanel.getChildren().clear();
            return;
        }

        detailTitle.setText(selected.getUsername() + "@" + selected.getHost());
        detailSub.setText(selected.getLastUsed() != null
                ? "Last used: " + selected.getLastUsed().format(FMT)
                : "Never used");

        fieldHost.setText(selected.getHost());
        fieldPort.setText(String.valueOf(selected.getPort()));
        fieldUser.setText(selected.getUsername());
        pwDots.setText("********");
        pwVisible = false;
        showPwBtn.setText("Show");

        usedByPanel.getChildren().clear();
        List<String> jobs = selected.getUsedByJobIds();
        if (jobs.isEmpty()) {
            Label none = new Label("No jobs reference this credential");
            none.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
            usedByPanel.getChildren().add(none);
        } else {
            jobs.forEach(j -> usedByPanel.getChildren().add(chip(j)));
        }
    }

    // ── Edit mode ─────────────────────────────────────────────────────────

    private void setEditMode(boolean on) {
        editMode = on;
        showCard(hostStack,     on ? 1 : 0);
        showCard(portStack,     on ? 1 : 0);
        showCard(userStack,     on ? 1 : 0);
        showCard(pwStack,       on ? 1 : 0);
        showCard(protocolStack, on ? 1 : 0);

        boolean has = selected != null;
        editBtn.setVisible(has && !on);
        testBtn.setVisible(has && !on);
        deleteBtn.setVisible(has && !on);
        saveBtn.setVisible(has && on);
        cancelBtn.setVisible(has && on);

        if (on && selected != null) {
            editHost.setText(selected.getHost());
            editPort.setText(String.valueOf(selected.getPort()));
            editUser.setText(selected.getUsername());
            editPassword.setText(selected.getPassword());
            editProtocol.setValue(selected.getProtocol());
        }
    }

    private void showCard(StackPane stack, int index) {
        for (int i = 0; i < stack.getChildren().size(); i++)
            stack.getChildren().get(i).setVisible(i == index);
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private void saveEdits() {
        if (selected == null) return;
        String host = editHost.getText().trim();
        String user = editUser.getText().trim();
        if (host.isEmpty() || user.isEmpty()) {
            alert("Host and username are required.");
            return;
        }
        selected.setHost(host);
        try { selected.setPort(Integer.parseInt(editPort.getText().trim())); }
        catch (NumberFormatException e) { selected.setPort(22); }
        selected.setUsername(user);
        selected.setPassword(editPassword.getText());
        selected.setProtocol(editProtocol.getValue());
        client.saveCredential(selected);
        setEditMode(false);
        refreshDetail();
        client.getCredentials();
    }

    private void deleteSelected() {
        if (selected == null) return;
        if (!selected.getUsedByJobIds().isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "This credential is used by " +
                            selected.getUsedByJobIds().size() + " job(s). Delete anyway?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES)
                return;
        }
        client.deleteCredential(selected.getId());
        selected = null;
        refreshDetail();
        client.getCredentials();
    }

    private void testConnection() {
        if (selected == null) return;
        testBtn.setDisable(true);
        testBtn.setText("Testing...");
        String host = selected.getHost();
        String user = selected.getUsername();
        int    port = selected.getPort();

        client.addTestCredentialListener((credId, result) -> {
            if (!credId.equals(selected.getId())) return;
            Platform.runLater(() -> {
                testBtn.setDisable(false);
                testBtn.setText("Test connection");
                if (result == null) {
                    alert("Connected successfully to " + user + "@" + host + ":" + port);
                } else {
                    alert("Connection failed:\n" + result);
                }
            });
        });
        client.testCredential(selected.getId());
    }

    private void togglePassword() {
        if (selected == null) return;
        pwVisible = !pwVisible;
        pwDots.setText(pwVisible ? selected.getPassword() : "********");
        showPwBtn.setText(pwVisible ? "Hide" : "Show");
    }

    private void openAddDialog() {
        CredentialEditDialog dlg = new CredentialEditDialog(
                root.getScene().getWindow(), client, null);
        dlg.show();
        client.getCredentials();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private GridPane detailGrid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        ColumnConstraints lbl = new ColumnConstraints(120);
        ColumnConstraints val = new ColumnConstraints();
        val.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(lbl, val);
        return g;
    }

    private void addDetailRow(GridPane g, String lbl,
                              javafx.scene.Node val, int row) {
        Label l = new Label(lbl);
        l.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        g.add(l, 0, row);
        g.add(val, 1, row);
    }

    private StackPane stack(javafx.scene.Node view, javafx.scene.Node edit) {
        StackPane sp = new StackPane(view, edit);
        edit.setVisible(false);
        return sp;
    }

    private Label readonlyLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-family: Consolas; -fx-font-size: 12;" +
                "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";");
        return l;
    }

    private TextField editField(String text) {
        TextField f = new TextField(text);
        styleField(f);
        return f;
    }

    private void styleField(Control f) {
        f.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 3 7 3 7;" +
                        "-fx-font-size: 12;");
        f.setMaxWidth(Double.MAX_VALUE);
    }

    private Label chip(String text) {
        Label l = new Label(text);
        l.setStyle(
                "-fx-background-color: " + Theme.BG_HOVER + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-background-radius: 20;" +
                        "-fx-padding: 3 10 3 10;" +
                        "-fx-font-size: 11;");
        return l;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-font-size: 10; -fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        return l;
    }

    private Separator separator() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return s;
    }

    private Region vStrut(double h) {
        Region r = new Region(); r.setPrefHeight(h); return r;
    }

    private Button styledBtn(String text, String type) {
        Button b = new Button(text);
        b.setStyle(switch (type) {
            case "primary" ->
                    "-fx-background-color: " + Theme.ACCENT + ";" +
                            "-fx-text-fill: white; -fx-font-weight: bold;" +
                            "-fx-background-radius: 6; -fx-padding: 6 14 6 14;" +
                            "-fx-cursor: hand;";
            case "danger" ->
                    "-fx-background-color: " + Theme.BG_CARD + ";" +
                            "-fx-text-fill: " + Theme.DANGER + ";" +
                            "-fx-border-color: " + Theme.DANGER + ";" +
                            "-fx-border-radius: 6; -fx-background-radius: 6;" +
                            "-fx-padding: 5 12 5 12; -fx-cursor: hand;";
            case "success" ->
                    "-fx-background-color: " + Theme.BG_CARD + ";" +
                            "-fx-text-fill: " + Theme.SUCCESS + ";" +
                            "-fx-border-color: " + Theme.SUCCESS + ";" +
                            "-fx-border-radius: 6; -fx-background-radius: 6;" +
                            "-fx-padding: 5 12 5 12; -fx-cursor: hand;";
            default ->
                    "-fx-background-color: " + Theme.BG_CARD + ";" +
                            "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                            "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                            "-fx-border-radius: 6; -fx-background-radius: 6;" +
                            "-fx-padding: 5 12 5 12; -fx-cursor: hand;";
        });
        b.setVisible(false);
        return b;
    }

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                        "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                        "-fx-border-radius: 4; -fx-background-radius: 4;" +
                        "-fx-padding: 2 8 2 8; -fx-font-size: 11; -fx-cursor: hand;");
        return b;
    }

    private Button actionBtn(String text, String type) {
        Button b = styledBtn(text, type);
        b.setVisible(false);
        return b;
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    // ── Cell renderer ─────────────────────────────────────────────────────

    private static class CredCell extends ListCell<CredentialMessage> {
        @Override
        protected void updateItem(CredentialMessage c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) { setGraphic(null); return; }

            // Avatar circle
            String initials = initials(c.getUsername());
            Label avatar = new Label(initials);
            avatar.setStyle(
                    "-fx-background-color: #FEF3DA;" +
                            "-fx-text-fill: " + Theme.ACCENT + ";" +
                            "-fx-font-weight: bold;" +
                            "-fx-font-size: 11;" +
                            "-fx-background-radius: 18;" +
                            "-fx-alignment: center;" +
                            "-fx-min-width: 36; -fx-min-height: 36;" +
                            "-fx-max-width: 36; -fx-max-height: 36;");

            Label name = new Label(c.getUsername());
            name.setStyle("-fx-font-weight: bold;" +
                    "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
            Label sub = new Label(c.getHost() + ":" + c.getPort());
            sub.setStyle("-fx-font-size: 11;" +
                    "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
            VBox info = new VBox(2, name, sub);

            int jobCount = c.getUsedByJobIds().size();
            Label badge = new Label(jobCount > 0
                    ? jobCount + " job" + (jobCount > 1 ? "s" : "") : "unused");
            badge.setStyle(
                    "-fx-font-size: 10;" +
                            "-fx-text-fill: " + (jobCount > 0 ? Theme.ACCENT : Theme.TEXT_MUTED) + ";" +
                            "-fx-border-color: " + (jobCount > 0 ? Theme.ACCENT : Theme.BORDER_STRONG) + ";" +
                            "-fx-border-radius: 4; -fx-background-radius: 4;" +
                            "-fx-padding: 1 6 1 6;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(10, avatar, info, spacer, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.setStyle("-fx-background-color: " +
                    (isSelected() ? Theme.BG_HOVER : Theme.BG_SURFACE) + ";" +
                    "-fx-border-color: " + Theme.BORDER + ";" +
                    "-fx-border-width: 0 0 1 0;");

            setGraphic(row);
            setText(null);
            setStyle("-fx-padding: 0; -fx-background-color: transparent;");
        }

        private static String initials(String user) {
            String[] parts = user.split("[_.\\-]");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) if (!p.isEmpty())
                sb.append(Character.toUpperCase(p.charAt(0)));
            String r = sb.toString();
            return r.isEmpty() ? "?" : r.length() > 2 ? r.substring(0, 2) : r;
        }
    }
}