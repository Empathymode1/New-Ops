package com.filewatcherui.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

/**
 * "Settings" tab — application configuration and preferences, per
 * architecture doc §4.
 *
 * The doc's wire protocol (§9) lists an UPDATE_CONFIGURATION command, but
 * the service side (ServiceWebSocketServer) does not currently implement a
 * handler for it, and AppConfig (doc §13) is loaded once from services.json
 * at service startup rather than exposed for live editing. Until that
 * round-trip exists, this panel is a read-only display of the settings that
 * govern this UI instance, so the tab matches the doc's structure (§4 lists
 * "Settings" as a top-level tab) without inventing an unsupported backend
 * call. Hooking this up to live UPDATE_CONFIGURATION is tracked as a
 * follow-up once the service implements that command.
 */
public class SettingsPanel {

    private final BorderPane root = new BorderPane();

    public SettingsPanel() {
        root.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");
        root.setTop(buildHeader());
        root.setCenter(buildContent());
    }

    public Region getRoot() { return root; }

    private HBox buildHeader() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 12, 10, 16));
        bar.setStyle("-fx-background-color: " + Theme.BG_SURFACE + ";");

        Label title = new Label("Settings");
        title.setStyle(
                "-fx-font-size: 15;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        bar.getChildren().add(title);
        return bar;
    }

    private VBox buildContent() {
        VBox box = new VBox(16);
        box.setPadding(new Insets(8, 24, 24, 24));

        box.getChildren().add(section("Connection",
                row("WebSocket host", "localhost"),
                row("WebSocket port", "9876")));

        box.getChildren().add(section("Logging",
                row("Log level", "INFO"),
                row("Rotation", "5 files \u00d7 10 MB")));

        Label note = new Label(
                "These values come from services.json on the service host and are " +
                        "currently read-only in this UI. Editing them here will be enabled " +
                        "once the service implements UPDATE_CONFIGURATION.");
        note.setWrapText(true);
        note.setMaxWidth(560);
        note.setStyle("-fx-font-size: 11; -fx-text-fill: " + Theme.TEXT_MUTED + ";");
        box.getChildren().add(note);

        return box;
    }

    private VBox section(String heading, HBox... rows) {
        Label h = new Label(heading);
        h.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: " + Theme.TEXT_PRIMARY + ";");

        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle(
                "-fx-background-color: " + Theme.BG_CARD + ";" +
                        "-fx-border-color: " + Theme.BORDER + ";" +
                        "-fx-border-radius: 6; -fx-background-radius: 6;");
        card.getChildren().addAll(rows);

        VBox wrapper = new VBox(6, h, card);
        return wrapper;
    }

    private HBox row(String label, String value) {
        Label l = new Label(label);
        l.setPrefWidth(160);
        l.setStyle("-fx-font-size: 12; -fx-text-fill: " + Theme.TEXT_SECONDARY + ";");

        Label v = new Label(value);
        v.setStyle("-fx-font-size: 12; -fx-text-fill: " + Theme.TEXT_PRIMARY + "; -fx-font-weight: bold;");

        HBox h = new HBox(l, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
