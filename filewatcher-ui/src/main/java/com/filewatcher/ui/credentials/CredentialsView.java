package com.filewatcher.ui.credentials;

import com.filewatcher.model.CredentialConfig;
import com.filewatcher.model.CredentialInfo;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.ui.components.ToastNotification;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Credentials page — a saved-credential vault, independent of any single
 * job (a credential can be reused across several jobs; {@code usedByCount}
 * shows how many currently reference it). Talks to the backend via the
 * contract's CREDENTIALS_SNAPSHOT / ADD_CREDENTIAL / UPDATE_CREDENTIAL /
 * DELETE_CREDENTIAL messages (docs/relay-monitoring-ws-contract.md §1.5,
 * §2.5-§2.8) — same request/reply pattern as Service Management's job CRUD.
 */
public class CredentialsView extends VBox {

    public CredentialsView(AppState state, ServiceClient client) {
        getStyleClass().add("page");
        setSpacing(14);
        setPadding(new Insets(20, 22, 32, 22));

        Label title = new Label("Credentials");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Saved connection credentials, reusable across watch jobs.");
        sub.getStyleClass().add("page-subtitle");

        TableView<CredentialInfo> table = buildTable(state);
        HBox toolbar = buildToolbar(table, state, client);

        VBox tableWrap = new VBox(table);
        tableWrap.getStyleClass().add("panel");
        VBox.setVgrow(tableWrap, Priority.ALWAYS);

        getChildren().addAll(new VBox(2, title, sub), toolbar, tableWrap);

        // Backend already pushes CREDENTIALS_SNAPSHOT unprompted right after
        // connect (contract §3), but ask explicitly too in case this page is
        // built/shown after that initial push already happened.
        client.requestCredentials();
    }

    @SuppressWarnings("unchecked")
    private TableView<CredentialInfo> buildTable(AppState state) {
        TableView<CredentialInfo> table = new TableView<>(state.getCredentials());
        table.getStyleClass().add("data-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<CredentialInfo, String> host = new TableColumn<>("Host");
        host.setCellValueFactory(new PropertyValueFactory<>("host"));

        TableColumn<CredentialInfo, Number> port = new TableColumn<>("Port");
        port.setCellValueFactory(new PropertyValueFactory<>("port"));

        TableColumn<CredentialInfo, String> username = new TableColumn<>("Username");
        username.setCellValueFactory(new PropertyValueFactory<>("username"));

        TableColumn<CredentialInfo, String> protocol = new TableColumn<>("Protocol");
        protocol.setCellValueFactory(new PropertyValueFactory<>("protocol"));

        TableColumn<CredentialInfo, String> lastUsed = new TableColumn<>("Last Used");
        lastUsed.setCellValueFactory(new PropertyValueFactory<>("lastUsed"));

        TableColumn<CredentialInfo, Number> usedBy = new TableColumn<>("Used By");
        usedBy.setCellValueFactory(new PropertyValueFactory<>("usedByCount"));

        table.getColumns().addAll(host, port, username, protocol, lastUsed, usedBy);
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private HBox buildToolbar(TableView<CredentialInfo> table, AppState state, ServiceClient client) {
        Button add = new Button("+ Add");
        add.getStyleClass().addAll("btn", "btn-primary");
        add.setOnAction(e -> CredentialFormDialog.showAdd(add, client).ifPresent(config ->
                client.addCredential(config).thenAccept(result -> javafx.application.Platform.runLater(() -> {
                    if (result.success()) {
                        ToastNotification.show("Credential added", config.host, false);
                    } else {
                        ToastNotification.show("Couldn't add credential", result.error(), true);
                    }
                }))));

        Button edit = new Button("Edit");
        edit.getStyleClass().add("btn");
        edit.setOnAction(e -> {
            CredentialInfo info = table.getSelectionModel().getSelectedItem();
            if (info == null) return;
            CredentialConfig prefill = new CredentialConfig();
            prefill.host = info.getHost();
            prefill.port = info.getPort();
            prefill.username = info.getUsername();
            prefill.password = info.getPassword();
            prefill.protocol = parseProtocol(info.getProtocol());
            CredentialFormDialog.showEdit(edit, prefill, client).ifPresent(config ->
                    client.updateCredential(info.getId(), config).thenAccept(result -> javafx.application.Platform.runLater(() -> {
                        if (result.success()) {
                            ToastNotification.show("Credential updated", config.host, false);
                        } else {
                            ToastNotification.show("Couldn't save credential", result.error(), true);
                        }
                    })));
        });

        Button delete = new Button("Delete");
        delete.getStyleClass().add("btn-danger");
        delete.setOnAction(e -> {
            CredentialInfo info = table.getSelectionModel().getSelectedItem();
            if (info == null) return;
            if (confirmDelete(delete, info)) {
                client.deleteCredential(info.getId());
                ToastNotification.show("Credential deleted", info.getHost(), false);
            }
        });

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn");
        refresh.setOnAction(e -> {
            client.requestCredentials();
            ToastNotification.show("Refreshed", "Credential list reloaded", false);
        });

        Runnable updateButtons = () -> {
            boolean has = table.getSelectionModel().getSelectedItem() != null;
            edit.setDisable(!has);
            delete.setDisable(!has);
        };
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> updateButtons.run());
        updateButtons.run();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, add, edit, delete, spacer, refresh);
        bar.getStyleClass().add("toolbar-row");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Contract §2.8 explicitly allows deleting a still-referenced credential
     * (the backend doesn't block it) but recommends the UI warn first, since
     * any job using it will fail to authenticate on its next connection.
     */
    private boolean confirmDelete(javafx.scene.Node owner, CredentialInfo info) {
        int usedBy = info.getUsedByJobIds().size();
        String message = usedBy > 0
                ? "This credential is used by " + usedBy + " job" + (usedBy == 1 ? "" : "s")
                + ". Deleting it will cause " + (usedBy == 1 ? "that job" : "those jobs")
                + " to fail authentication on its next connection attempt.\n\nDelete anyway?"
                : "Delete this credential?";
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.setTitle("Delete Credential");
        alert.setHeaderText(info.getHost() + " (" + info.getUsername() + ")");
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private static com.filewatcher.model.Protocol parseProtocol(String s) {
        try {
            return com.filewatcher.model.Protocol.valueOf(s);
        } catch (Exception e) {
            return com.filewatcher.model.Protocol.SFTP;
        }
    }
}
