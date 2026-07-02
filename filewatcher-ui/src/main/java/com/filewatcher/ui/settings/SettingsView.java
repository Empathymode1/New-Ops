package com.filewatcher.ui.settings;

import com.filewatcher.theme.ThemeManager;
import com.filewatcher.ui.components.ToggleSwitch;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spec §10 — Settings: General / Network / Scheduler / Logging / SSH / Database, grouped nav + panel. */
public class SettingsView extends VBox {

    public SettingsView(ThemeManager themeManager) {
        getStyleClass().add("page");
        setSpacing(14);
        setPadding(new Insets(20, 22, 32, 22));

        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Application, network and service configuration.");
        sub.getStyleClass().add("page-subtitle");

        Map<String, VBox> groups = new LinkedHashMap<>();
        groups.put("General", generalGroup(themeManager));
        groups.put("Network", networkGroup());
        groups.put("Scheduler", schedulerGroup());
        groups.put("Logging", loggingGroup());
        groups.put("SSH", sshGroup());
        groups.put("Database", databaseGroup());

        VBox navList = new VBox(2);
        navList.getStyleClass().add("settings-nav");
        navList.setPrefWidth(170);

        StackPane content = new StackPane();
        content.getStyleClass().add("panel");
        content.setPadding(new Insets(6, 16, 6, 16));

        ToggleGroup tg = new ToggleGroup();
        boolean[] first = {true};
        groups.forEach((label, group) -> {
            ToggleButton btn = new ToggleButton(label);
            btn.getStyleClass().add("settings-nav-btn");
            btn.setToggleGroup(tg);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setOnAction(e -> content.getChildren().setAll(group));
            if (first[0]) { btn.setSelected(true); content.getChildren().setAll(group); first[0] = false; }
            navList.getChildren().add(btn);
        });

        HBox layout = new HBox(18, navList, content);
        HBox.setHgrow(content, Priority.ALWAYS);

        getChildren().addAll(new VBox(2, title, sub), layout);
    }

    private VBox generalGroup(ThemeManager themeManager) {
        HBox themeRow = settingRow("Theme", "Switch instantly, no restart required", null);
        HBox themePick = new HBox(6);
        for (ThemeManager.Theme t : ThemeManager.Theme.values()) {
            ToggleButton b = new ToggleButton(prettyThemeName(t));
            b.getStyleClass().add("theme-pick-btn");
            b.setSelected(t == themeManager.getCurrent());
            b.setOnAction(e -> themeManager.setTheme(t));
            themePick.getChildren().add(b);
        }
        ((HBox) themeRow).getChildren().add(themePick);

        ComboBox<String> lang = new ComboBox<>();
        lang.getItems().addAll("English (US)", "Deutsch", "日本語");
        lang.setValue("English (US)");

        return new VBox(0,
                themeRow,
                settingRowWithNode("Language", null, lang),
                settingRowWithNode("Auto Refresh", "Keep dashboard cards live-updating", new ToggleSwitch(true)),
                settingRowWithNode("Desktop Notifications", null, new ToggleSwitch(true))
        );
    }

    private VBox networkGroup() {
        return new VBox(0,
                settingRowWithNode("WebSocket Host", null, textField("wss://relay.internal.airport.net", 230)),
                settingRowWithNode("Port", null, textField("8443", 100)),
                settingRowWithNode("Reconnect Interval", null, textField("5s", 100)),
                settingRowWithNode("Timeout", null, textField("30s", 100))
        );
    }

    private VBox schedulerGroup() {
        return new VBox(0,
                settingRowWithNode("Heartbeat Interval", null, textField("10s", 100)),
                settingRowWithNode("Thread Pool Size", null, textField("16", 100)),
                settingRowWithNode("Polling Enabled", null, new ToggleSwitch(true)),
                settingRowWithNode("Max Concurrent Transfers", null, textField("8", 100))
        );
    }

    private VBox loggingGroup() {
        ComboBox<String> level = new ComboBox<>();
        level.getItems().addAll("INFO", "DEBUG", "WARN", "ERROR");
        level.setValue("INFO");

        ComboBox<String> rotation = new ComboBox<>();
        rotation.getItems().addAll("Daily", "Weekly", "Size-based (100MB)");
        rotation.setValue("Daily");

        return new VBox(0,
                settingRowWithNode("Log Level", null, level),
                settingRowWithNode("Log Rotation", null, rotation),
                settingRowWithNode("Retention", null, textField("30 days", 110)),
                settingRowWithNode("Log Folder", null, textField("/var/log/relay/", 190))
        );
    }

    private VBox sshGroup() {
        ComboBox<String> auth = new ComboBox<>();
        auth.getItems().addAll("Key-based", "Password", "Key + Passphrase");
        auth.setValue("Key-based");

        return new VBox(0,
                settingRowWithNode("Connection Timeout", null, textField("15s", 100)),
                settingRowWithNode("Authentication", null, auth),
                settingRowWithNode("Known Hosts", null, textField("~/.ssh/known_hosts", 190))
        );
    }

    private VBox databaseGroup() {
        Button backup = new Button("Run Backup Now");
        Button optimize = new Button("Optimize");
        Button vacuum = new Button("Vacuum");
        for (Button b : new Button[]{backup, optimize, vacuum}) b.getStyleClass().add("btn");

        return new VBox(0,
                settingRowWithNode("SQLite Location", null, textField("/data/relay.db", 170)),
                settingRowWithNode("Backup", null, backup),
                settingRowWithNode("Optimize Database", null, optimize),
                settingRowWithNode("Vacuum Database", null, vacuum)
        );
    }

    private HBox settingRow(String label, String desc, javafx.scene.Node trailing) {
        VBox labelBox = new VBox(2);
        Label l = new Label(label);
        l.getStyleClass().add("setting-label");
        labelBox.getChildren().add(l);
        if (desc != null) {
            Label d = new Label(desc);
            d.getStyleClass().add("setting-desc");
            labelBox.getChildren().add(d);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, labelBox, spacer);
        if (trailing != null) row.getChildren().add(trailing);
        row.getStyleClass().add("setting-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox settingRowWithNode(String label, String desc, javafx.scene.Node trailing) {
        return settingRow(label, desc, trailing);
    }

    private TextField textField(String value, double width) {
        TextField tf = new TextField(value);
        tf.getStyleClass().add("mono-field");
        tf.setPrefWidth(width);
        return tf;
    }

    private String prettyThemeName(ThemeManager.Theme t) {
        return switch (t) {
            case DARK -> "Dark";
            case LIGHT -> "Light";
            case HIGH_CONTRAST -> "High Contrast";
        };
    }
}
