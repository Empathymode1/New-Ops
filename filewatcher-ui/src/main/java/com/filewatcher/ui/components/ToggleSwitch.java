package com.filewatcher.ui.components;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Simple on/off switch used throughout Settings (spec §10) — JavaFX has no built-in equivalent. */
public class ToggleSwitch extends StackPane {

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Region track = new Region();
    private final Circle thumb = new Circle(7.5);

    public ToggleSwitch(boolean initial) {
        getStyleClass().add("toggle-switch");
        track.getStyleClass().add("toggle-track");
        track.setPrefSize(38, 21);
        thumb.getStyleClass().add("toggle-thumb");

        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(track, thumb);
        thumb.setTranslateX(2);

        setOnMouseClicked(e -> setSelected(!isSelected()));
        selected.addListener((obs, old, val) -> animate(val));
        setSelected(initial);
    }

    private void animate(boolean on) {
        getStyleClass().removeAll("on", "off");
        getStyleClass().add(on ? "on" : "off");
        TranslateTransition tt = new TranslateTransition(Duration.millis(140), thumb);
        tt.setToX(on ? 19 : 2);
        tt.play();
    }

    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean value) { selected.set(value); animate(value); }
    public BooleanProperty selectedProperty() { return selected; }
}
