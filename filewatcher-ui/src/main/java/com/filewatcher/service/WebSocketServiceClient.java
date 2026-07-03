package com.filewatcher.service;

import com.filewatcher.model.Job;
import com.filewatcher.model.JobStatus;
import com.filewatcher.state.AppState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Real implementation of {@link ServiceClient}, talking the plain-JSON
 * WebSocket contract described in "Relay ↔ Monitoring Service — WebSocket
 * Contract":
 *
 *   Server → Client : SNAPSHOT, EVENT
 *   Client → Server : SNAPSHOT_REQUEST, COMMAND
 *
 * Endpoint defaults to ws://localhost:8765/ws, overridable via the
 * RELAY_BACKEND_URL environment variable (or -DrelayBackendUrl=... system
 * property, handy for `mvn javafx:run`).
 *
 * SNAPSHOT messages are applied directly onto {@link AppState} (this client
 * is constructed with the same AppState instance the rest of the app
 * observes, mirroring {@link MockServiceClient}'s pattern). EVENT messages
 * are converted into {@link ServiceEvent} and handed to the EventDispatcher
 * via the normal listener mechanism, so no other part of the app needs to
 * change.
 */
public class WebSocketServiceClient implements ServiceClient {

    private static final URI WS_URI = URI.create(resolveBackendUrl());
    private static final long[] BACKOFF_SECONDS = {2, 4, 8, 16, 30};

    private final AppState state;
    private final List<Consumer<ServiceEvent>> listeners = new ArrayList<>();
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "relay-ws-reconnect");
                t.setDaemon(true);
                return t;
            });
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    private volatile boolean manuallyClosed = false;

    public WebSocketServiceClient(AppState state) {
        this.state = state;
    }

    private static String resolveBackendUrl() {
        String fromEnv = System.getenv("RELAY_BACKEND_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        String fromProp = System.getProperty("relayBackendUrl");
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return "ws://localhost:8765/ws";
    }

    @Override
    public CompletableFuture<Void> connect() {
        manuallyClosed = false;
        HttpClient httpClient = HttpClient.newHttpClient();
        return httpClient.newWebSocketBuilder()
                .buildAsync(WS_URI, new Listener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    this.connected = true;
                    reconnectAttempt.set(0);
                })
                .exceptionally(ex -> {
                    connected = false;
                    notify(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
                    scheduleReconnect();
                    return null;
                });
    }

    @Override
    public void disconnect() {
        manuallyClosed = true;
        connected = false;
        reconnectExecutor.shutdownNow();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void addListener(Consumer<ServiceEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<ServiceEvent> listener) {
        listeners.remove(listener);
    }

    @Override
    public CompletableFuture<Void> sendCommand(String jobId, JobCommand command) {
        if (webSocket == null || !connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "COMMAND");
        payload.addProperty("jobId", jobId);
        payload.addProperty("command", command.name());
        return webSocket.sendText(payload.toString(), true).thenApply(ws -> null);
    }

    @Override
    public CompletableFuture<Void> requestInitialSnapshot() {
        if (webSocket == null || !connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "SNAPSHOT_REQUEST");
        return webSocket.sendText(payload.toString(), true).thenApply(ws -> null);
    }

    // ------------------------------------------------------------------
    // Reconnect with exponential backoff (2s, 4s, 8s, 16s, capped at 30s)
    // ------------------------------------------------------------------

    private void scheduleReconnect() {
        if (manuallyClosed || reconnectExecutor.isShutdown()) return;
        int attempt = reconnectAttempt.getAndIncrement();
        long delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
        reconnectExecutor.schedule(() -> {
            if (manuallyClosed) return;
            connect().thenRun(() -> {
                if (connected) {
                    notify(new ServiceEvent(ServiceEventType.CONNECTION_RESTORED, null, null, null, null, null, LocalTime.now()));
                    // Per contract §3: state may have changed while disconnected — refresh.
                    requestInitialSnapshot();
                }
            });
        }, delay, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Inbound message handling
    // ------------------------------------------------------------------

    private void handleMessage(String raw) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return; // malformed frame — ignore rather than crash the socket
        }
        String type = optString(obj, "type", null);
        if (type == null) return;

        switch (type) {
            case "SNAPSHOT" -> applySnapshot(obj);
            case "EVENT" -> applyEvent(obj);
            default -> { /* unknown message type — ignore */ }
        }
    }

    private void applySnapshot(JsonObject obj) {
        JsonArray jobsArray = obj.has("jobs") && !obj.get("jobs").isJsonNull()
                ? obj.getAsJsonArray("jobs") : new JsonArray();

        List<Job> parsed = new ArrayList<>();
        for (JsonElement el : jobsArray) {
            JsonObject j = el.getAsJsonObject();
            String id = optString(j, "id", null);
            if (id == null) continue;
            parsed.add(new Job(
                    id,
                    optString(j, "name", ""),
                    optString(j, "type", ""),
                    optString(j, "sourcePath", ""),
                    optString(j, "destPath", ""),
                    optString(j, "pollingInterval", ""),
                    optString(j, "credential", ""),
                    parseStatus(optString(j, "status", "STOPPED")),
                    j.has("filesToday") && !j.get("filesToday").isJsonNull() ? j.get("filesToday").getAsInt() : 0,
                    optString(j, "lastTransfer", "—"),
                    optString(j, "currentActivity", "")
            ));
        }

        Platform.runLater(() -> {
            Set<String> incomingIds = new HashSet<>();
            for (Job incoming : parsed) {
                incomingIds.add(incoming.getId());
                Job existing = state.findJob(incoming.getId());
                if (existing == null) {
                    state.getJobs().add(incoming);
                } else {
                    // Mutate in place so any UI bound to the existing Job instance
                    // (e.g. the selected job in Service Management) stays valid.
                    existing.setStatus(incoming.getStatus());
                    existing.setCurrentActivity(incoming.currentActivityProperty().get());
                    existing.setLastTransfer(incoming.lastTransferProperty().get());
                    existing.filesTodayProperty().set(incoming.filesTodayProperty().get());
                    existing.nameProperty().set(incoming.getName());
                    existing.typeProperty().set(incoming.typeProperty().get());
                    existing.sourcePathProperty().set(incoming.sourcePathProperty().get());
                    existing.destPathProperty().set(incoming.destPathProperty().get());
                    existing.pollingIntervalProperty().set(incoming.pollingIntervalProperty().get());
                    existing.credentialProperty().set(incoming.credentialProperty().get());
                }
            }
            state.getJobs().removeIf(j -> !incomingIds.contains(j.getId()));
            state.recomputeRunningStoppedCounts();
        });
    }

    private void applyEvent(JsonObject obj) {
        String eventTypeStr = optString(obj, "eventType", null);
        if (eventTypeStr == null) return;

        ServiceEventType eventType;
        try {
            eventType = ServiceEventType.valueOf(eventTypeStr);
        } catch (IllegalArgumentException e) {
            return; // unknown eventType — ignore rather than crash
        }

        String jobId = optString(obj, "jobId", null);
        String jobName = optString(obj, "jobName", null);
        String filename = optString(obj, "filename", null);
        String message = optString(obj, "message", null);
        String newStatusStr = optString(obj, "newStatus", null);
        JobStatus newStatus = newStatusStr != null ? parseStatus(newStatusStr) : null;
        LocalTime timestamp = parseTimestamp(optString(obj, "timestamp", null));

        notify(new ServiceEvent(eventType, jobId, jobName, filename, message, newStatus, timestamp));
    }

    private void notify(ServiceEvent event) {
        for (Consumer<ServiceEvent> l : listeners) {
            l.accept(event);
        }
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    private static JobStatus parseStatus(String s) {
        try {
            return JobStatus.valueOf(s);
        } catch (Exception e) {
            return JobStatus.STOPPED;
        }
    }

    private static LocalTime parseTimestamp(String s) {
        if (s == null) return LocalTime.now();
        try {
            return LocalDateTime.parse(s).toLocalTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalTime.parse(s);
            } catch (DateTimeParseException e2) {
                return LocalTime.now();
            }
        }
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener
    // ------------------------------------------------------------------

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            boolean wasConnected = connected;
            connected = false;
            if (wasConnected) {
                notify(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            boolean wasConnected = connected;
            connected = false;
            if (wasConnected) {
                notify(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
            }
            scheduleReconnect();
        }
    }
}
