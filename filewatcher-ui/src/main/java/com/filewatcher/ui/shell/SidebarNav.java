package com.filewatcher.ui.shell;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Left navigation (spec §6): Dashboard / Services / Logs / Settings, plus a "Coming soon" section. */
public class SidebarNav extends VBox {

    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final ToggleGroup group = new ToggleGroup();

    public SidebarNav(Consumer<String> onNavigate) {
        getStyleClass().add("sidebar");
        setPadding(new Insets(12, 10, 12, 10));
        setSpacing(2);
        setPrefWidth(190);

        addItem("dashboard", "Dashboard", "Ctrl+D", onNavigate);
        addItem("services", "Services", "Ctrl+S", onNavigate);
        addItem("logs", "Logs", "Ctrl+L", onNavigate);
        addItem("settings", "Settings", "Ctrl+,", onNavigate);

        Label futureLabel = new Label("COMING SOON");
        futureLabel.getStyleClass().add("nav-section-label");
        VBox.setMargin(futureLabel, new Insets(14, 4, 4, 8));

        Label analytics = disabledItem("Analytics");
        Label plugins = disabledItem("Plugins");

        getChildren().addAll(futureLabel, analytics, plugins);
        selectPage("dashboard");
    }

    private void addItem(String key, String label, String shortcut, Consumer<String> onNavigate) {
        ToggleButton btn = new ToggleButton(label);
        btn.getStyleClass().add("nav-item");
        btn.setToggleGroup(group);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> onNavigate.accept(key));
        buttons.put(key, btn);
        getChildren().add(btn);
    }

    private Label disabledItem(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("nav-item-future");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /** Called by MainShell so keyboard shortcuts keep the sidebar's active highlight in sync. */
    public void selectPage(String key) {
        ToggleButton btn = buttons.get(key);
        if (btn != null) btn.setSelected(true);
    }
}
