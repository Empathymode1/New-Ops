package com.filewatcher.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Dashboard summary card — bind valueLabel to an IntegerProperty/StringProperty for live updates. */
public class InfoCard extends VBox {

    private final Label valueLabel = new Label();
    private final Label captionLabel;

    public InfoCard(String caption, String accentColorVar) {
        getStyleClass().add("info-card");
        setAlignment(Pos.TOP_LEFT);
        setStyle("-fx-border-color: " + "transparent;"); // left accent bar handled in CSS via style class below

        valueLabel.getStyleClass().add("info-card-value");
        valueLabel.setStyle("-fx-text-fill: " + accentColorVar + ";");

        captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("info-card-caption");

        getChildren().addAll(valueLabel, captionLabel);
        getStyleClass().add(accentColorVar.replace("-fx-", "").replace("-", "") + "-accent");
    }

    public void setValue(String value) { valueLabel.setText(value); }

    /** Briefly flashes the card border — used when a stat changes, matching the HTML preview's ".flash" effect. */
    public void flash() {
        getStyleClass().add("flash");
        javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        pt.setOnFinished(e -> getStyleClass().remove("flash"));
        pt.play();
    }
}
