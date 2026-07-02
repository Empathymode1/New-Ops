package com.filewatcher.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Small pulsing dot used in the toolbar "Connected" chip and status bar (spec §5, §11). */
public class ConnectionIndicator extends StackPane {

    private final Circle dot = new Circle(4);
    private final Circle ring = new Circle(4);

    public ConnectionIndicator() {
        dot.getStyleClass().add("conn-dot");
        ring.getStyleClass().add("conn-ring");
        ring.setFill(Color.TRANSPARENT);
        getChildren().addAll(ring, dot);
        startPulse();
    }

    private void startPulse() {
        ScaleTransition scale = new ScaleTransition(Duration.seconds(1.8), ring);
        scale.setFromX(0.5); scale.setFromY(0.5);
        scale.setToX(2.2); scale.setToY(2.2);
        scale.setCycleCount(Timeline.INDEFINITE);

        FadeTransition fade = new FadeTransition(Duration.seconds(1.8), ring);
        fade.setFromValue(0.9);
        fade.setToValue(0);
        fade.setCycleCount(Timeline.INDEFINITE);

        scale.play();
        fade.play();
    }

    public void setConnected(boolean connected) {
        dot.getStyleClass().removeAll("conn-dot-ok", "conn-dot-down");
        dot.getStyleClass().add(connected ? "conn-dot-ok" : "conn-dot-down");
    }
}
