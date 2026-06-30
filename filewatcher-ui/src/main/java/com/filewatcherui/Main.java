package com.filewatcherui;

import com.filewatcherui.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/**
 * Entry point for filewatcher-ui.jar.
 * Always opens the UI — no service mode, no flag check.
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            new MainWindow(primaryStage);
        } catch (Exception e) {
            e.printStackTrace(); // ← tells us what actually failed
            new Alert(Alert.AlertType.ERROR,
                    "Startup error: " + e.getClass().getName() + "\n" + e.getMessage(),
                    ButtonType.OK).showAndWait();
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale",          "1.0");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext",                "true");

        launch(args);
    }
}