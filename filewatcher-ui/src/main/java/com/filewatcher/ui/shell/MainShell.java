package com.filewatcher.ui.shell;

import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.theme.ThemeManager;
import com.filewatcher.ui.components.ToastNotification;
import com.filewatcher.ui.dashboard.DashboardView;
import com.filewatcher.ui.logs.LogsView;
import com.filewatcher.ui.services.ServicesView;
import com.filewatcher.ui.settings.SettingsView;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

import java.util.LinkedHashMap;
import java.util.Map;

/** Root layout — spec §4/§5: Toolbar (top) + Sidebar (left) + Content (center) + Status bar (bottom). */
public class MainShell extends StackPane {

    private final Map<String, javafx.scene.Node> pages = new LinkedHashMap<>();
    private final StackPane contentStack = new StackPane();
    private SidebarNav sidebar;

    public MainShell(AppState state, ServiceClient client, ThemeManager themeManager) {
        getStyleClass().add("app-root");

        BorderPane border = new BorderPane();
        border.getStyleClass().add("app-shell");

        TopToolbar toolbar = new TopToolbar(themeManager);
        border.setTop(toolbar);

        sidebar = new SidebarNav(this::navigateTo);
        border.setLeft(sidebar);

        pages.put("dashboard", scrollable(new DashboardView(state)));
        pages.put("services", scrollable(new ServicesView(state, client)));
        pages.put("logs", new LogsView(state)); // owns its own scrolling/overlay
        pages.put("settings", scrollable(new SettingsView(themeManager)));

        pages.forEach((key, node) -> {
            node.setVisible(key.equals("dashboard"));
            node.setManaged(key.equals("dashboard"));
            contentStack.getChildren().add(node);
        });
        border.setCenter(contentStack);

        border.setBottom(new StatusBar(state));

        getChildren().add(border);
        ToastNotification.mount(this);
    }

    private ScrollPane scrollable(javafx.scene.Parent content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-scroll");
        return sp;
    }

    public void navigateTo(String key) {
        pages.forEach((k, node) -> {
            boolean active = k.equals(key);
            node.setVisible(active);
            node.setManaged(active);
        });
        sidebar.selectPage(key);
    }

    /** Registers Ctrl+D/S/L/, and Ctrl+F per spec §15. Call once the Scene exists. */
    public void registerShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN),
                () -> navigateTo("dashboard"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN),
                () -> navigateTo("services"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN),
                () -> navigateTo("logs"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                () -> navigateTo("settings"));
    }
}
