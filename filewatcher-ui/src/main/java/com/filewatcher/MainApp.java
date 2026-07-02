package com.filewatcher;

import com.filewatcher.service.MockServiceClient;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.theme.ThemeManager;
import com.filewatcher.service.EventDispatcher;
import com.filewatcher.ui.components.ToastNotification;
import com.filewatcher.ui.shell.MainShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        AppState state = AppState.seedDemoData();

        // --- Backend integration point -------------------------------------
        // Swap this one line for `new WebSocketServiceClientSkeleton()` (fleshed
        // out with your real endpoint + JSON parsing) once the Monitoring
        // Service is available. Nothing else in the app needs to change,
        // because every view is written against the ServiceClient interface.
        ServiceClient client = new MockServiceClient(state);
        // ---------------------------------------------------------------------

        EventDispatcher dispatcher = new EventDispatcher(state, client);
        dispatcher.setToastHandler(ToastNotification::show);

        ThemeManager themeManager = new ThemeManager();
        MainShell shell = new MainShell(state, client, themeManager);

        Scene scene = new Scene(shell, 1280, 800);
        themeManager.attach(scene);
        shell.registerShortcuts(scene);

        stage.setTitle("Relay — Transfer Monitoring Console");
        stage.setScene(scene);
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.show();

        client.connect().thenRun(() -> client.requestInitialSnapshot());
    }

    @Override
    public void stop() {
        // client.disconnect() would go here if MainApp held a field reference;
        // left as a start() local for brevity in this scaffold.
    }

    public static void main(String[] args) {
        launch(args);
    }
}
