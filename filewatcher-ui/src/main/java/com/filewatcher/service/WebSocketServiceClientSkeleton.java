package com.filewatcher.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * STARTING POINT for real backend integration — not wired up by default.
 *
 * To go live:
 *   1. Point WS_URI at your Monitoring Service's WebSocket endpoint.
 *   2. Parse the JSON payload in onText(...) into a ServiceEvent
 *      (e.g. with Jackson/Gson) instead of the TODO below.
 *   3. Implement sendCommand(...) to POST/emit the equivalent backend
 *      command (REST call, or a JSON message over the same socket).
 *   4. In MainApp, replace `new MockServiceClient(state)` with
 *      `new WebSocketServiceClientSkeleton(state)`.
 *
 * Everything else in the app — EventDispatcher, AppState, all views —
 * is written against the ServiceClient interface and requires ZERO changes.
 */
public class WebSocketServiceClientSkeleton implements ServiceClient {

    private static final URI WS_URI = URI.create("wss://your-monitoring-service.example/ws");

    private final List<Consumer<ServiceEvent>> listeners = new ArrayList<>();
    private WebSocket webSocket;
    private volatile boolean connected = false;

    @Override
    public CompletableFuture<Void> connect() {
        HttpClient httpClient = HttpClient.newHttpClient();
        return httpClient.newWebSocketBuilder()
                .buildAsync(WS_URI, new Listener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    this.connected = true;
                })
                .exceptionally(ex -> {
                    connected = false;
                    // TODO: notify listeners of CONNECTION_LOST, schedule reconnect with backoff
                    return null;
                });
    }

    @Override
    public void disconnect() {
        connected = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
        }
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void addListener(Consumer<ServiceEvent> listener) { listeners.add(listener); }

    @Override
    public void removeListener(Consumer<ServiceEvent> listener) { listeners.remove(listener); }

    @Override
    public CompletableFuture<Void> sendCommand(String jobId, JobCommand command) {
        if (webSocket == null) return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        // TODO: replace with real JSON envelope your backend expects, e.g.:
        // {"type":"COMMAND","jobId":"job-1","command":"START"}
        String payload = "{\"type\":\"COMMAND\",\"jobId\":\"" + jobId + "\",\"command\":\"" + command + "\"}";
        return webSocket.sendText(payload, true).thenApply(ws -> null);
    }

    @Override
    public CompletableFuture<Void> requestInitialSnapshot() {
        if (webSocket == null) return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        String payload = "{\"type\":\"SNAPSHOT_REQUEST\"}";
        return webSocket.sendText(payload, true).thenApply(ws -> null);
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                // TODO: deserialize `message` (JSON) into a ServiceEvent and dispatch:
                // ServiceEvent event = jsonMapper.readValue(message, ServiceEvent.class);
                // for (var l : listeners) l.accept(event);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false;
            // TODO: notify listeners of CONNECTION_LOST, attempt reconnect
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            // TODO: log + notify listeners of CONNECTION_LOST
        }
    }
}
