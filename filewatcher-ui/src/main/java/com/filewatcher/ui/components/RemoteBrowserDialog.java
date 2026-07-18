package com.filewatcher.ui.components;

import com.filewatcher.model.RemoteEntryInfo;
import com.filewatcher.service.RemoteBrowseResult;
import com.filewatcher.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal directory browser — connects over SFTP with the given credentials
 * (contract §2.11 BROWSE_REMOTE) and lets the user click through folders to
 * pick a path, instead of typing one blind. Used by JobFormDialog's
 * "Browse…" buttons next to the source/destination path fields.
 *
 * Only lists directories (not files) — this is a path *picker*, not a full
 * file browser; picking a file to watch/deliver into doesn't make sense
 * for this tool's job model (it watches/writes whole folders).
 */
public final class RemoteBrowserDialog {

    private RemoteBrowserDialog() {
    }

    /**
     * @param startPath initial path to open (null/blank = the account's default/home directory)
     * @return the selected path, or empty if the user cancelled
     */
    public static Optional<String> show(Node owner, ServiceClient client,
                                         String host, int port, String username, String password,
                                         String startPath) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Browse Remote Path");
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }

        ButtonType selectType = new ButtonType("Select This Folder", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectType, ButtonType.CANCEL);
        Button selectButton = (Button) dialog.getDialogPane().lookupButton(selectType);

        Label pathLabel = new Label("Connecting…");
        pathLabel.getStyleClass().add("drow-label");
        Label error = new Label();
        error.setStyle("-fx-text-fill: #e05b5b; -fx-font-size: 12px;");
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);

        ListView<RemoteEntryInfo> list = new ListView<>();
        list.setPrefSize(420, 320);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(RemoteEntryInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "\uD83D\uDCC1  " + item.name());
            }
        });

        AtomicReference<String> currentPath = new AtomicReference<>(startPath);
        Button up = new Button("\u2191 Up");
        up.setDisable(true);

        Runnable[] loadHolder = new Runnable[1];
        loadHolder[0] = () -> {
            selectButton.setDisable(true);
            list.setDisable(true);
            client.browseRemote(host, port, username, password, currentPath.get())
                    .thenAccept(result -> Platform.runLater(() -> {
                        list.setDisable(false);
                        if (result.success()) {
                            currentPath.set(result.path());
                            pathLabel.setText(result.path());
                            up.setDisable(result.path() == null || result.path().equals("/"));
                            list.getItems().setAll(result.entries().stream()
                                    .filter(RemoteEntryInfo::directory).toList());
                            error.setVisible(false);
                            error.setManaged(false);
                            selectButton.setDisable(false);
                        } else {
                            error.setText(result.error());
                            error.setVisible(true);
                            error.setManaged(true);
                            selectButton.setDisable(true);
                        }
                    }));
        };

        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                RemoteEntryInfo selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    String base = currentPath.get() == null ? "" : currentPath.get();
                    currentPath.set((base.endsWith("/") ? base : base + "/") + selected.name());
                    loadHolder[0].run();
                }
            }
        });

        up.setOnAction(e -> {
            String path = currentPath.get();
            if (path == null) return;
            int idx = path.lastIndexOf('/');
            currentPath.set(idx <= 0 ? "/" : path.substring(0, idx));
            loadHolder[0].run();
        });

        HBox pathRow = new HBox(8, up, pathLabel);
        pathRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, error, pathRow, list);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        loadHolder[0].run(); // initial load

        dialog.setResultConverter(bt -> bt == selectType ? currentPath.get() : null);
        return dialog.showAndWait();
    }
}
