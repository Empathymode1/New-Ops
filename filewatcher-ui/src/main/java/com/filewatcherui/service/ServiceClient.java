package com.filewatcherui.service;

import com.filewatchercommon.model.*;
import com.filewatchercommon.ws.WsTypes;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * WebSocket client for the UI process.
 * Connects to ServiceWebSocketServer on ws://localhost:9876.
 *
 * Sends commands to service, receives pushed events back.
 * UI panels register listeners here instead of directly on FileWatcherService.
 */
public class ServiceClient extends WebSocketClient {

    private static final Logger LOG = Logger.getLogger(ServiceClient.class.getName());

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(java.time.LocalDateTime.class,
                    (JsonSerializer<java.time.LocalDateTime>)
                            (src, t, ctx) -> new JsonPrimitive(src.toString()))
            .registerTypeAdapter(java.time.LocalDateTime.class,
                    (JsonDeserializer<java.time.LocalDateTime>)
                            (json, t, ctx) -> java.time.LocalDateTime.parse(json.getAsString()))
            .create();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    // ── Listeners registered by UI panels ─────────────────────────────────────
    private final List<Consumer<List<WatchJob>>>                jobListListeners    = new CopyOnWriteArrayList<>();
    private final List<Consumer<WatchJob>>                      jobStateListeners   = new CopyOnWriteArrayList<>();
    private final List<Consumer<TransferEvent>>                 eventListeners      = new CopyOnWriteArrayList<>();
    private final List<Consumer<NotificationMessage>> notifListeners   = new CopyOnWriteArrayList<>();
    private final List<Runnable>                                connectListeners    = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<JsonObject>>                    healthListeners     = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<CredentialMessage>>>  credentialListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, String>> testCredentialListeners = new CopyOnWriteArrayList<>();
    private final List<RemoteDirListener> remoteDirListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<LogEntryMessage>>> logsListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> logsExportListeners = new CopyOnWriteArrayList<>();


    private static final int RECONNECT_DELAY_MS = 5_000;
    public ServiceClient() throws Exception {
        super(new URI("ws://localhost:9876"));
    }
    public static interface RemoteDirListener {
        void onResult(String path, List<String> entries, String error);
    }

    // ── Listener registration ─────────────────────────────────────────────────

    /** Called once on connect with full job list (INIT message) */
    public void addJobListListener(Consumer<List<WatchJob>> l)              { jobListListeners.add(l); }
    /** Called whenever a single job state changes (JOB_STATE push) */
    public void addJobStateListener(Consumer<WatchJob> l)                   { jobStateListeners.add(l); }
    /** Called whenever a transfer event occurs (EVENT push) */
    public void addEventListener(Consumer<TransferEvent> l)                 { eventListeners.add(l); }
    /** Called whenever an error notification arrives (NOTIFICATION push) */
    public void addNotificationListener(Consumer<NotificationMessage> l) { notifListeners.add(l); }
    /** Called when WebSocket connection is established */
    public void addConnectListener(Runnable l)                              { connectListeners.add(l); }

    public void addHealthListener(Consumer<JsonObject> l)                   { healthListeners.add(l); }
    public void addCredentialListener(Consumer<List<CredentialMessage>> l)    { credentialListeners.add(l); }
    public void addTestCredentialListener(BiConsumer<String, String> l) {
        testCredentialListeners.add(l);
    }
    public void addDisconnectListener(Runnable l) { disconnectListeners.add(l); }
    public void addRemoteDirListener(RemoteDirListener listener) {
        remoteDirListeners.add(listener);
    }
    public void addLogsListener(Consumer<List<LogEntryMessage>> l) { logsListeners.add(l); }
    public void addLogsExportListener(Consumer<String> l) { logsExportListeners.add(l); }
    // ── WebSocket lifecycle ───────────────────────────────────────────────────

    @Override
    public void onOpen(ServerHandshake handshake) {
        LOG.info("Connected to service at ws://localhost:9876");
        connectListeners.forEach(Runnable::run);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOG.warning("Disconnected from service: " + reason);
        disconnectListeners.forEach(Runnable::run);
        scheduleReconnect();
    }

    @Override
    public void onError(Exception e) {
        LOG.warning("ServiceClient error: " + e.getMessage());
    }

    private void scheduleReconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
                LOG.info("Attempting reconnect to ws://localhost:9876...");
                reconnect();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "ws-reconnect").start();
    }

    // ── Handle pushed messages from service ───────────────────────────────────

    @Override
    public void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();

            if (!json.has("type")) return; // reply to a command, not a push

            switch (json.get("type").getAsString()) {
                case WsTypes.INIT -> {
                    // Full job list on connect
                    Type type = TypeToken.getParameterized(List.class, WatchJob.class).getType();
                    List<WatchJob> jobs = GSON.fromJson(json.get("jobs"), type);
                    jobListListeners.forEach(l -> l.accept(jobs));
                }
                case WsTypes.JOB_STATE -> {
                    WatchJob job = GSON.fromJson(json.get("job"), WatchJob.class);
                    jobStateListeners.forEach(l -> l.accept(job));
                }
                case WsTypes.EVENT -> {
                    TransferEvent event = GSON.fromJson(json.get("event"), TransferEvent.class);
                    eventListeners.forEach(l -> l.accept(event));
                }
                case WsTypes.NOTIFICATION -> {
                    NotificationMessage n = GSON.fromJson(
                            json.get("notification"),
                            NotificationMessage.class);
                    notifListeners.forEach(l -> l.accept(n));
                }
                case WsTypes.HEALTH -> {                                          // ← new
                    JsonObject stats = json.getAsJsonObject("stats");
                    healthListeners.forEach(l -> l.accept(stats));
                }
                case WsTypes.CREDENTIALS -> {
                    Type type = TypeToken.getParameterized(
                            List.class, CredentialMessage.class).getType();
                    List<CredentialMessage> creds = GSON.fromJson(
                            json.get("credentials"), type);
                    credentialListeners.forEach(l -> l.accept(creds));
                }
                case WsTypes.TEST_RESULT -> {
                    String credId = json.get("credId").getAsString();
                    String error  = json.has("error")
                            ? json.get("error").getAsString() : null;
                    testCredentialListeners.forEach(l -> l.accept(credId, error));
                }
                case WsTypes.LIST_REMOTE_DIR_RESULT -> {
                    String replyPath = json.get("path").getAsString();
                    String error     = json.has("error") ? json.get("error").getAsString() : null;
                    List<String> entries = new ArrayList<>();
                    if (error == null && json.has("entries")) {
                        json.getAsJsonArray("entries")
                                .forEach(e -> entries.add(e.getAsString()));
                    }
                    // Fire and remove one-shot listeners
                    remoteDirListeners.removeIf(l -> {
                        l.onResult(replyPath, entries, error);
                        return true; // remove after firing
                    });

                }
                case WsTypes.LOGS -> {
                    Type type = TypeToken.getParameterized(List.class, LogEntryMessage.class).getType();
                    List<LogEntryMessage> logs = GSON.fromJson(json.get("logs"), type);
                    logsListeners.forEach(l -> l.accept(logs));
                }
                case WsTypes.LOGS_EXPORT -> {
                    String csv = json.has("csv") ? json.get("csv").getAsString() : "";
                    logsExportListeners.forEach(l -> l.accept(csv));
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to handle pushed message: " + e.getMessage());
        }
    }

    private void sendIfConnected(JsonObject cmd) {
        if (isOpen()) {
            send(cmd.toString());
        } else {
            LOG.warning("Not connected — dropped: " + cmd.get("cmd").getAsString());
        }
    }

    // ── Commands UI sends to service ──────────────────────────────────────────

    public void startJob(String id) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "START_JOB");
        cmd.addProperty("id",  id);
        sendIfConnected(cmd);
    }

    public void stopJob(String id) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "STOP_JOB");
        cmd.addProperty("id",  id);
        sendIfConnected(cmd);
    }
    public void startAll() {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "START_ALL");
        sendIfConnected(cmd);
    }

    public void stopAll() {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "STOP_ALL");
        sendIfConnected(cmd);
    }

    public void addJob(WatchJob job) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "ADD_JOB");
        cmd.add("job", GSON.toJsonTree(job));
        sendIfConnected(cmd);
    }

    public void updateJob(WatchJob job) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "UPDATE_JOB");
        cmd.add("job", GSON.toJsonTree(job));
        sendIfConnected(cmd);
    }

    public void deleteJob(String id) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "DELETE_JOB");
        cmd.addProperty("id",  id);
        sendIfConnected(cmd);
    }



    // ── Credential commands ─────────────────────────────���─────────────────────
    public void saveCredential(CredentialMessage cred) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SAVE_CREDENTIAL");
        cmd.add("credential",  GSON.toJsonTree(cred));
        sendIfConnected(cmd);
    }

    public void deleteCredential(String credId) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "DELETE_CREDENTIAL");
        cmd.addProperty("id",  credId);
        sendIfConnected(cmd);
    }

    public void getCredentials() {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "GET_CREDENTIALS");
        sendIfConnected(cmd);
    }

    // ── Health check ─────────────────��────────────────────────────────────────
    public void requestHealth() {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "HEALTH");
        sendIfConnected(cmd);
    }

    // Test Connection
    public void testCredential(String credId) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd",    "TEST_CREDENTIAL");
        cmd.addProperty("id",     credId);
        sendIfConnected(cmd);
    }

    public void listRemoteDirectory(WatchJob.Protocol protocol,
                                    String host, int port,
                                    String user, String password,
                                    String path,
                                    Consumer<List<String>> onSuccess,
                                    Consumer<String> onError) {

        java.util.concurrent.ScheduledFuture<?> timeout =
                scheduler.schedule(() ->
                                SwingUtilities.invokeLater(() ->
                                        onError.accept("Connection timed out after 8 seconds")),
                        8, java.util.concurrent.TimeUnit.SECONDS);

        JsonObject cmd = new JsonObject();
        cmd.addProperty("type",     "LIST_REMOTE_DIR");
        cmd.addProperty("protocol", protocol.name());
        cmd.addProperty("host",     host);
        cmd.addProperty("port",     port);
        cmd.addProperty("user",     user);
        cmd.addProperty("password", password);
        cmd.addProperty("path",     path);
        sendIfConnected(cmd);

        addRemoteDirListener((replyPath, entries, error) -> {
            if (!replyPath.equals(path)) return;
            timeout.cancel(false);
            if (error != null) {
                SwingUtilities.invokeLater(() -> onError.accept(error));
            } else {
                SwingUtilities.invokeLater(() -> onSuccess.accept(entries));
            }
        });
    }
}