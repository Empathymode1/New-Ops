package com.filewatcher.theme;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;

/**
 * Runtime theme switching (spec §13: "Theme switching does not require
 * restarting the application"). Each theme is a pair of stylesheets:
 * a small "variables" sheet defining -fx-* custom properties, and the
 * shared app.css which only ever references those variables — so adding
 * a new theme is just adding one more variables-*.css file.
 */
public class ThemeManager {

    public enum Theme {
        DARK("/css/variables-dark.css"),
        LIGHT("/css/variables-light.css"),
        HIGH_CONTRAST("/css/variables-dark.css"); // placeholder: point at a dedicated file when designed

        final String variablesPath;
        Theme(String variablesPath) { this.variablesPath = variablesPath; }
    }

    private static final String BASE_CSS = "/css/app.css";

    private final ObjectProperty<Theme> current = new SimpleObjectProperty<>(Theme.DARK);
    private Scene scene;

    public void attach(Scene scene) {
        this.scene = scene;
        apply(current.get());
    }

    public ObjectProperty<Theme> currentProperty() { return current; }
    public Theme getCurrent() { return current.get(); }

    public void setTheme(Theme theme) {
        current.set(theme);
        apply(theme);
    }

    public void toggle() {
        setTheme(getCurrent() == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    private void apply(Theme theme) {
        if (scene == null) return;
        scene.getStylesheets().setAll(
                getClass().getResource(theme.variablesPath).toExternalForm(),
                getClass().getResource(BASE_CSS).toExternalForm()
        );
    }
}
