package com.filewatcher;

/**
 * Plain entry point (some IDEs / packaging tools want a non-Application
 * main class on the classpath, since some launchers/OS packagers behave
 * oddly when the class with main() also extends Application).
 * Delegates to Application.launch(), which boots the JavaFX toolkit and
 * then calls MainApp.start(Stage) for us — start() must never be called
 * directly.
 */
public class Main {
    public static void main(String[] args) {
        javafx.application.Application.launch(MainApp.class, args);
    }
}
