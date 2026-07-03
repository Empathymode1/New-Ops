package com.filewatcher;

import com.filewatcher.service.MockServiceClient;
import com.filewatcher.service.ServiceClient;
import com.filewatcher.service.WebSocketServiceClient;
import com.filewatcher.state.AppState;
import com.filewatcher.theme.ThemeManager;
import com.filewatcher.service.EventDispatcher;
import com.filewatcher.ui.components.ToastNotification;
import com.filewatcher.ui.shell.MainShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    private ServiceClient client;

    @Override
    public void start(Stage stage) {
        AppState state = AppState.seedDemoData();

        // --- Backend integration point -------------------------------------
        // Speaks the "Relay <-> Monitoring Service" WebSocket contract
        // (SNAPSHOT / EVENT / SNAPSHOT_REQUEST / COMMAND). Endpoint defaults
        // to ws://localhost:8765/ws -- override with the RELAY_BACKEND_URL
        // env var, or point it at the SampleBackendServer reference
        // implementation for local dev/demo. Set -DuseMockBackend=true (or
        // env USE_MOCK_BACKEND=true) to fall back to the in-memory
        // MockServiceClient with no backend running at all.
        client = useMockBackend()
                ? new MockServiceClient(state)
                : new WebSocketServiceClient(state);
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
        if (client != null) {
            client.disconnect();
        }
    }

    private static boolean useMockBackend() {
        String prop = System.getProperty("useMockBackend");
        if (prop != null) return Boolean.parseBoolean(prop);
        String env = System.getenv("USE_MOCK_BACKEND");
        return Boolean.parseBoolean(env);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
