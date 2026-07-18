package com.filewatcher.ui.shell;

import com.filewatcher.state.AppState;
import com.filewatcher.theme.ThemeManager;
import com.filewatcher.ui.components.ConnectionIndicator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/** Maps to spec §5 toolbar: logo/name, search, theme toggle, connected chip. */
public class TopToolbar extends HBox {

    private final TextField searchField = new TextField();

    public TopToolbar(ThemeManager themeManager, AppState state) {
        getStyleClass().add("top-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(16);
        setPadding(new Insets(0, 16, 0, 16));
        setPrefHeight(52);

        HBox brand = buildBrand();

        searchField.setPromptText("Search jobs, logs, settings…");
        searchField.getStyleClass().add("toolbar-search");
        searchField.setPrefWidth(280);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button themeToggle = new Button("\u25D1"); // half-circle glyph as a simple sun/moon stand-in
        themeToggle.getStyleClass().add("icon-btn");
        themeToggle.setOnAction(e -> themeManager.toggle());

        ConnectionIndicator indicator = new ConnectionIndicator();
        Label connLabel = new Label();
        HBox connChip = new HBox(7);
        connChip.getStyleClass().add("conn-chip");
        connChip.setAlignment(Pos.CENTER_LEFT);
        connChip.getChildren().addAll(indicator, connLabel);

        // BUG FIX: this used to be a permanently-hardcoded Label("Connected") with a
        // ConnectionIndicator whose setConnected(boolean) was never actually called --
        // so it showed "Connected" even with the backend fully disconnected. Now bound
        // to the same live signal the Dashboard's Health Overview WebSocket row uses.
        Runnable update = () -> {
            boolean connected = "Connected".equalsIgnoreCase(state.getStats().webSocketStatusProperty().get());
            indicator.setConnected(connected);
            connLabel.setText(connected ? "Connected" : "Reconnecting…");
        };
        state.getStats().webSocketStatusProperty().addListener((o, ov, nv) -> update.run());
        update.run();

        getChildren().addAll(brand, searchField, spacer, themeToggle, connChip);
    }

    private HBox buildBrand() {
        Circle mark = new Circle(6);
        mark.getStyleClass().add("brand-mark");
        Label name = new Label("Relay");
        name.getStyleClass().add("brand-name");
        Label sub = new Label("Transfer Monitoring Console");
        sub.getStyleClass().add("brand-sub");
        HBox box = new HBox(9, mark, name, sub);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public TextField getSearchField() { return searchField; }
}
