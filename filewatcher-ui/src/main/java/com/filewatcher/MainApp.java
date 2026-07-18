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
import javafx.stage.StageStyle;

public class MainApp extends Application {

    private ServiceClient client;

    @Override
    public void start(Stage stage) {
        boolean mock = useMockBackend();

        // AppState.seedDemoData() pre-populates the same 7 fake jobs the mock
        // uses. Only seed it for the mock client -- for a real backend we want
        // an honestly empty state until its first SNAPSHOT arrives, otherwise
        // this canned data would sit on screen indefinitely (indistinguishable
        // from the mock) if the backend is slow to connect or isn't running.
        AppState state = mock ? AppState.seedDemoData() : new AppState();

        // --- Backend integration point -------------------------------------
        // Speaks the "Relay <-> Monitoring Service" WebSocket contract
        // (SNAPSHOT / EVENT / SNAPSHOT_REQUEST / COMMAND). Endpoint defaults
        // to ws://localhost:8765/ws -- override with the RELAY_BACKEND_URL
        // env var to point it at the real filewatcher-service backend
        // (services.json's websocketHost/websocketPort, default port 9876).
        // Set -DuseMockBackend=true (or env USE_MOCK_BACKEND=true) to fall
        // back to the in-memory MockServiceClient with no backend running
        // at all.
        client = mock
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

        stage.initStyle(StageStyle.DECORATED); // explicit, not relied-on-by-default: guarantees the
                                                // OS's native minimize/maximize/close window chrome.
        stage.setTitle("Relay — Transfer Monitoring Console");
        stage.setScene(scene);
        stage.setResizable(true); // required for the native maximize button to do anything
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
