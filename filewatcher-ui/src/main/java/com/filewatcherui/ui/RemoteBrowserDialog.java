package com.filewatcherui.ui;

import com.filewatchercommon.model.WatchJob;
import com.filewatcherui.service.ServiceClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

public class RemoteBrowserDialog {

    private String selectedPath = null;

    private final ServiceClient     client;
    private final WatchJob.Protocol protocol;
    private final String            host;
    private final int               port;
    private final String            user;
    private final String            password;
    private final Stage             stage = new Stage();

    private final TreeView<String>  tree;
    private       Label             currentPathLabel;
    private       Button            selectBtn;

    public RemoteBrowserDialog(Window parent, ServiceClient client,
                               WatchJob.Protocol protocol,
                               String host, int port,
                               String user, String password,
                               String startPath, List<String> rootEntries) {
        this.client   = client;
        this.protocol = protocol;
        this.host     = host;
        this.port     = port;
        this.user     = user;
        this.password = password;

        stage.initOwner(parent);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Browse Remote: " + user + "@" + host);
        stage.setWidth(480);
        stage.setHeight(520);

        // Tree
        TreeItem<String> root = new TreeItem<>(startPath);
        root.setExpanded(true);
        populateNode(root, rootEntries);

        tree = new TreeView<>(root);
        tree.setStyle("-fx-background-color: " + Theme.BG_CARD + ";");

        tree.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> {
                    if (item != null) {
                        currentPathLabel.setText(buildPath(item));
                        selectBtn.setDisable(false);
                    }
                });

        // Lazy load on expand
        tree.getRoot().addEventHandler(TreeItem.<String>branchExpandedEvent(),
                event -> {
                    TreeItem<String> node = event.getTreeItem();
                    if (node.getChildren().size() == 1 &&
                            "Loading...".equals(node.getChildren().get(0).getValue())) {
                        node.getChildren().clear();
                        loadChildren(node);
                    }
                });

        stage.setScene(new Scene(buildLayout(startPath)));
    }

    public void show() { stage.showAndWait(); }

    public String getSelectedPath() { return selectedPath; }

    // ── Layout ────────────────────────────────────────────────────────────

    private BorderPane buildLayout(String startPath) {
        BorderPane main = new BorderPane();
        main.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        main.setPadding(new Insets(16));

        Label header = new Label("Select a folder on " + host);
        header.setStyle("-fx-font-size: 15; -fx-font-weight: bold;" +
                "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");
        main.setTop(new VBox(header, new Region() {{ setPrefHeight(8); }}));

        ScrollPane scroll = new ScrollPane(tree);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color: " + Theme.BG_CARD + ";" +
                "-fx-border-color: " + Theme.BORDER + ";");
        main.setCenter(scroll);

        // Bottom
        currentPathLabel = new Label(startPath);
        currentPathLabel.setStyle("-fx-font-family: Consolas;" +
                "-fx-font-size: 11;" +
                "-fx-text-fill: " + Theme.TEXT_MUTED + ";");
        HBox.setHgrow(currentPathLabel, Priority.ALWAYS);

        Button cancelBtn = styledBtn("Cancel", false);
        selectBtn = styledBtn("Select folder", true);
        selectBtn.setDisable(true);

        cancelBtn.setOnAction(e -> stage.close());
        selectBtn.setOnAction(e -> {
            TreeItem<String> item =
                    tree.getSelectionModel().getSelectedItem();
            if (item != null) selectedPath = buildPath(item);
            stage.close();
        });

        HBox btns = new HBox(6, cancelBtn, selectBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);

        HBox bottom = new HBox(8, currentPathLabel, btns);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 0, 0, 0));
        main.setBottom(bottom);

        return main;
    }

    // ── Remote loading ────────────────────────────────────────────────────

    private void loadChildren(TreeItem<String> node) {
        TreeItem<String> loading = new TreeItem<>("Loading...");
        node.getChildren().add(loading);

        client.listRemoteDirectory(protocol, host, port, user, password,
                buildPath(node),
                entries -> Platform.runLater(() -> {
                    node.getChildren().clear();
                    populateNode(node, entries);
                }),
                error -> Platform.runLater(() -> {
                    node.getChildren().clear();
                    node.getChildren().add(new TreeItem<>("[!] " + error));
                })
        );
    }

    private void populateNode(TreeItem<String> node, List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            node.getChildren().add(new TreeItem<>("(empty)"));
        } else {
            for (String entry : entries) {
                TreeItem<String> child = new TreeItem<>(entry);
                child.getChildren().add(new TreeItem<>("Loading..."));
                node.getChildren().add(child);
            }
        }
    }

    // ── Path builder ──────────────────────────────────────────────────────

    private String buildPath(TreeItem<String> item) {
        StringBuilder sb = new StringBuilder();
        TreeItem<String> current = item;
        while (current != null) {
            String seg = current.getValue();
            if (!"Loading...".equals(seg) && !"(empty)".equals(seg)) {
                if (!seg.startsWith("/")) sb.insert(0, "/" + seg);
                else sb.insert(0, seg);
            }
            current = current.getParent();
        }
        String result = sb.toString().replaceAll("/+", "/");
        return result.isEmpty() ? "/" : result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Button styledBtn(String text, boolean primary) {
        Button b = new Button(text);
        b.setStyle(primary
                ? "-fx-background-color: " + Theme.ACCENT + ";" +
                "-fx-text-fill: white; -fx-font-weight: bold;" +
                "-fx-background-radius: 6; -fx-padding: 6 16 6 16;" +
                "-fx-cursor: hand;"
                : "-fx-background-color: " + Theme.BG_CARD + ";" +
                "-fx-text-fill: " + Theme.TEXT_SECONDARY + ";" +
                "-fx-border-color: " + Theme.BORDER_STRONG + ";" +
                "-fx-border-radius: 6; -fx-background-radius: 6;" +
                "-fx-padding: 6 14 6 14; -fx-cursor: hand;");
        return b;
    }
}