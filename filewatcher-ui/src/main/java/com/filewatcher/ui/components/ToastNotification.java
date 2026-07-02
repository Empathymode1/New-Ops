package com.filewatcher.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Toast container anchored bottom-right of the root StackPane (spec §12).
 * Call ToastNotification.mount(rootStack) once, then .show(title, sub, isError) anywhere.
 */
public class ToastNotification {

    private static VBox container;

    public static void mount(StackPane root) {
        container = new VBox(8);
        container.getStyleClass().add("toast-container");
        container.setAlignment(Pos.BOTTOM_RIGHT);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(container, new Insets(0, 16, 38, 0));
        container.setMouseTransparent(true);
        root.getChildren().add(container);
    }

    /** Convenience overload — used as the EventDispatcher's toast handler (BiConsumer&lt;String, Boolean&gt;). */
    public static void show(String title, boolean isError) {
        show(title, "", isError);
    }

    public static void show(String title, String subtitle, boolean isError) {
        if (container == null) return;

        VBox toast = new VBox(2);
        toast.getStyleClass().addAll("toast", isError ? "toast-error" : "toast-ok");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("toast-title");
        toast.getChildren().add(titleLabel);
        if (subtitle != null && !subtitle.isBlank()) {
            Label subLabel = new Label(subtitle);
            subLabel.getStyleClass().add("toast-sub");
            toast.getChildren().add(subLabel);
        }
        toast.setMaxWidth(280);

        container.getChildren().add(toast);

        PauseTransition wait = new PauseTransition(Duration.seconds(3.6));
        wait.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(Duration.millis(250), toast);
            fade.setFromValue(1); fade.setToValue(0);
            fade.setOnFinished(ev -> container.getChildren().remove(toast));
            fade.play();
        });
        wait.play();
    }
}
