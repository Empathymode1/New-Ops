package com.filewatcher.service;

import com.filewatcher.model.CredentialConfig;
import com.filewatcher.model.CredentialInfo;
import com.filewatcher.model.Direction;
import com.filewatcher.model.Job;
import com.filewatcher.model.JobStatus;
import com.filewatcher.model.Protocol;
import com.filewatcher.model.RemoteOs;
import com.filewatcher.model.TransferMode;
import com.filewatcher.model.WatchJobConfig;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, CompletableFuture<JobSaveResult>> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<com.filewatcher.model.TransferLogEntry>>> pendingLogRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RemoteBrowseResult>> pendingBrowseRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ConnectionTestResult>> pendingConnectionTests = new ConcurrentHashMap<>();

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
        return "ws://localhost:9876/ws";
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
                    notifyListeners(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
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

    @Override
    public CompletableFuture<JobSaveResult> addJob(WatchJobConfig config) {
        return sendJobSave("ADD_JOB", null, config);
    }

    @Override
    public CompletableFuture<JobSaveResult> updateJob(String jobId, WatchJobConfig config) {
        return sendJobSave("UPDATE_JOB", jobId, config);
    }

    /** Shared implementation for addJob/updateJob (contract §2.3/§2.4) — see docs/relay-monitoring-ws-contract.md. */
    private CompletableFuture<JobSaveResult> sendJobSave(String type, String jobId, WatchJobConfig config) {
        if (webSocket == null || !connected) {
            return CompletableFuture.completedFuture(JobSaveResult.failed("Not connected to backend"));
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JobSaveResult> future = new CompletableFuture<>();
        pendingSaves.put(requestId, future);

        JsonObject jobJson = new JsonObject();
        jobJson.addProperty("name", config.name);
        jobJson.addProperty("protocol", config.protocol.name());
        jobJson.addProperty("direction", config.direction.name());
        jobJson.addProperty("transferMode", config.transferMode.name());
        jobJson.addProperty("sourceHost", config.sourceHost);
        jobJson.addProperty("sourcePort", config.sourcePort);
        jobJson.addProperty("sourceUser", config.sourceUser);
        jobJson.addProperty("sourcePassword", config.sourcePassword);
        jobJson.addProperty("sourcePath", config.sourcePath);
        jobJson.addProperty("destHost", config.destHost);
        jobJson.addProperty("destPort", config.destPort);
        jobJson.addProperty("destUser", config.destUser);
        jobJson.addProperty("destPassword", config.destPassword);
        jobJson.addProperty("destPath", config.destPath);
        jobJson.addProperty("specificPattern", config.specificPattern);
        jobJson.addProperty("intervalSeconds", config.intervalSeconds);
        jobJson.addProperty("watchDepth", config.watchDepth);
        jobJson.addProperty("remoteOs", config.remoteOs == null ? null : config.remoteOs.name());

        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("requestId", requestId);
        if (jobId != null) payload.addProperty("jobId", jobId);
        payload.add("job", jobJson);

        webSocket.sendText(payload.toString(), true).exceptionally(ex -> {
            CompletableFuture<JobSaveResult> pending = pendingSaves.remove(requestId);
            if (pending != null) pending.complete(JobSaveResult.failed("Failed to send request: " + ex.getMessage()));
            return null;
        });

        return future;
    }

    @Override
    public CompletableFuture<Void> requestCredentials() {
        if (webSocket == null || !connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "CREDENTIALS_REQUEST");
        return webSocket.sendText(payload.toString(), true).thenApply(ws -> null);
    }

    @Override
    public CompletableFuture<JobSaveResult> addCredential(CredentialConfig config) {
        return sendCredentialSave("ADD_CREDENTIAL", null, config);
    }

    @Override
    public CompletableFuture<JobSaveResult> updateCredential(String credentialId, CredentialConfig config) {
        return sendCredentialSave("UPDATE_CREDENTIAL", credentialId, config);
    }

    @Override
    public void deleteCredential(String credentialId) {
        if (webSocket == null || !connected) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "DELETE_CREDENTIAL");
        payload.addProperty("credentialId", credentialId);
        webSocket.sendText(payload.toString(), true);
    }

    @Override
    public CompletableFuture<List<com.filewatcher.model.TransferLogEntry>> requestLogs(LogsQuery query) {
        if (webSocket == null || !connected) {
            return CompletableFuture.completedFuture(List.of());
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<List<com.filewatcher.model.TransferLogEntry>> future = new CompletableFuture<>();
        pendingLogRequests.put(requestId, future);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "LOGS_REQUEST");
        payload.addProperty("requestId", requestId);
        payload.addProperty("jobId", query.jobId());
        payload.addProperty("eventType", query.eventType());
        payload.addProperty("search", query.search());
        payload.addProperty("sinceEpochSeconds", query.sinceEpochSeconds());
        payload.addProperty("limit", query.limit());

        webSocket.sendText(payload.toString(), true).exceptionally(ex -> {
            CompletableFuture<List<com.filewatcher.model.TransferLogEntry>> pending = pendingLogRequests.remove(requestId);
            if (pending != null) pending.complete(List.of());
            return null;
        });

        return future;
    }

    @Override
    public CompletableFuture<ConnectionTestResult> testRawConnection(String host, int port, String username, String password, boolean detectOs) {
        if (webSocket == null || !connected) {
            return CompletableFuture.completedFuture(ConnectionTestResult.failed("Not connected to backend"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ConnectionTestResult> future = new CompletableFuture<>();
        pendingConnectionTests.put(requestId, future);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "TEST_RAW_CONNECTION");
        payload.addProperty("requestId", requestId);
        payload.addProperty("host", host);
        payload.addProperty("port", port);
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("detectOs", detectOs);

        webSocket.sendText(payload.toString(), true).exceptionally(ex -> {
            CompletableFuture<ConnectionTestResult> pending = pendingConnectionTests.remove(requestId);
            if (pending != null) pending.complete(ConnectionTestResult.failed("Failed to send request: " + ex.getMessage()));
            return null;
        });
        return future;
    }

    @Override
    public CompletableFuture<RemoteBrowseResult> browseRemote(String host, int port, String username, String password, String path) {
        if (webSocket == null || !connected) {
            return CompletableFuture.completedFuture(new RemoteBrowseResult(path, List.of(), "Not connected to backend"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<RemoteBrowseResult> future = new CompletableFuture<>();
        pendingBrowseRequests.put(requestId, future);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "BROWSE_REMOTE");
        payload.addProperty("requestId", requestId);
        payload.addProperty("host", host);
        payload.addProperty("port", port);
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("path", path);

        webSocket.sendText(payload.toString(), true).exceptionally(ex -> {
            CompletableFuture<RemoteBrowseResult> pending = pendingBrowseRequests.remove(requestId);
            if (pending != null) pending.complete(new RemoteBrowseResult(path, List.of(), "Failed to send request: " + ex.getMessage()));
            return null;
        });
        return future;
    }
     /* (§2.6/§2.7). Reuses {@code pendingSaves} — its keys are random UUIDs
     * regardless of whether they belong to a job or credential save, so
     * there's no collision risk sharing one map for both, and it saves
     * having a second near-identical map/handler pair.
     */
    private CompletableFuture<JobSaveResult> sendCredentialSave(String type, String credentialId, CredentialConfig config) {
        if (webSocket == null || !connected) {
            return CompletableFuture.completedFuture(JobSaveResult.failed("Not connected to backend"));
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JobSaveResult> future = new CompletableFuture<>();
        pendingSaves.put(requestId, future);

        JsonObject credJson = new JsonObject();
        credJson.addProperty("host", config.host);
        credJson.addProperty("port", config.port);
        credJson.addProperty("username", config.username);
        credJson.addProperty("password", config.password);
        credJson.addProperty("protocol", config.protocol.name());

        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("requestId", requestId);
        if (credentialId != null) payload.addProperty("credentialId", credentialId);
        payload.add("credential", credJson);

        webSocket.sendText(payload.toString(), true).exceptionally(ex -> {
            CompletableFuture<JobSaveResult> pending = pendingSaves.remove(requestId);
            if (pending != null) pending.complete(JobSaveResult.failed("Failed to send request: " + ex.getMessage()));
            return null;
        });

        return future;
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
                    notifyListeners(new ServiceEvent(ServiceEventType.CONNECTION_RESTORED, null, null, null, null, null, LocalTime.now()));
                    // Per contract §3: state may have changed while disconnected — refresh.
                    requestInitialSnapshot();
                    requestCredentials();
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
            case "JOB_SAVED" -> applyJobSaved(obj);
            case "JOB_SAVE_FAILED" -> applyJobSaveFailed(obj);
            case "HEALTH" -> applyHealth(obj);
            case "CREDENTIALS_SNAPSHOT" -> applyCredentialsSnapshot(obj);
            case "CREDENTIAL_SAVED" -> applyCredentialSaved(obj);
            case "CREDENTIAL_SAVE_FAILED" -> applyCredentialSaveFailed(obj);
            case "LOGS_RESPONSE" -> applyLogsResponse(obj);
            case "TEST_RAW_CONNECTION_RESULT" -> applyTestRawConnectionResult(obj);
            case "BROWSE_REMOTE_RESPONSE" -> applyBrowseRemoteResponse(obj);
            default -> { /* unknown message type — ignore */ }
        }
    }

    private void applyTestRawConnectionResult(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        CompletableFuture<ConnectionTestResult> pending = requestId != null ? pendingConnectionTests.remove(requestId) : null;
        if (pending == null) return;
        boolean success = obj.has("success") && !obj.get("success").isJsonNull() && obj.get("success").getAsBoolean();
        String error = optString(obj, "error", success ? null : "Connection failed");
        String detectedOs = optString(obj, "detectedOs", null); // only present when the request set detectOs:true (§2.10) and it succeeded
        pending.complete(success ? ConnectionTestResult.ok(detectedOs) : ConnectionTestResult.failed(error));
    }

    private void applyBrowseRemoteResponse(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        CompletableFuture<RemoteBrowseResult> pending = requestId != null ? pendingBrowseRequests.remove(requestId) : null;
        if (pending == null) return;

        String path = optString(obj, "path", null);
        String error = optString(obj, "error", null);
        List<com.filewatcher.model.RemoteEntryInfo> entries = new ArrayList<>();
        if (obj.has("entries") && obj.get("entries").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("entries")) {
                JsonObject e = el.getAsJsonObject();
                entries.add(new com.filewatcher.model.RemoteEntryInfo(
                        optString(e, "name", ""),
                        e.has("directory") && !e.get("directory").isJsonNull() && e.get("directory").getAsBoolean()
                ));
            }
        }
        pending.complete(new RemoteBrowseResult(path, entries, error));
    }

    private void applyLogsResponse(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        CompletableFuture<List<com.filewatcher.model.TransferLogEntry>> pending =
                requestId != null ? pendingLogRequests.remove(requestId) : null;
        if (pending == null) return;

        List<com.filewatcher.model.TransferLogEntry> entries = new ArrayList<>();
        if (obj.has("entries") && obj.get("entries").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("entries")) {
                JsonObject e = el.getAsJsonObject();
                entries.add(new com.filewatcher.model.TransferLogEntry(
                        e.has("id") && !e.get("id").isJsonNull() ? e.get("id").getAsLong() : 0L,
                        optString(e, "jobId", null),
                        optString(e, "jobName", null),
                        optString(e, "eventType", null),
                        optString(e, "message", null),
                        optString(e, "filename", null),
                        e.has("sizeBytes") && !e.get("sizeBytes").isJsonNull() ? e.get("sizeBytes").getAsLong() : 0L,
                        optString(e, "occurredAt", null)
                ));
            }
        }
        pending.complete(entries);
    }

    /** Applies CREDENTIALS_SNAPSHOT (contract §1.5) onto AppState's credential list. */
    private void applyCredentialsSnapshot(JsonObject obj) {
        JsonArray credsArray = obj.has("credentials") && !obj.get("credentials").isJsonNull()
                ? obj.getAsJsonArray("credentials") : new JsonArray();

        List<CredentialInfo> parsed = new ArrayList<>();
        for (JsonElement el : credsArray) {
            JsonObject c = el.getAsJsonObject();
            String id = optString(c, "id", null);
            if (id == null) continue;
            List<String> usedBy = new ArrayList<>();
            if (c.has("usedByJobIds") && c.get("usedByJobIds").isJsonArray()) {
                for (JsonElement idEl : c.getAsJsonArray("usedByJobIds")) {
                    usedBy.add(idEl.getAsString());
                }
            }
            parsed.add(new CredentialInfo(
                    id,
                    optString(c, "host", ""),
                    optInt(c, "port", 22),
                    optString(c, "username", ""),
                    optString(c, "password", ""),
                    optString(c, "protocol", "SFTP"),
                    optString(c, "lastUsed", null),
                    usedBy
            ));
        }

        Platform.runLater(() -> {
            state.getCredentials().setAll(parsed); // simple full replace — no bound-selection concern like Job has
        });
    }

    private void applyCredentialSaved(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String credentialId = optString(obj, "credentialId", null);
        CompletableFuture<JobSaveResult> pending = requestId != null ? pendingSaves.remove(requestId) : null;
        if (pending != null) pending.complete(JobSaveResult.ok(credentialId));
    }

    private void applyCredentialSaveFailed(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String error = optString(obj, "error", "Unknown error");
        CompletableFuture<JobSaveResult> pending = requestId != null ? pendingSaves.remove(requestId) : null;
        if (pending != null) pending.complete(JobSaveResult.failed(error));
    }

    /** Applies a HEALTH broadcast (contract §1.4) onto AppState's dashboard stats. */
    private void applyHealth(JsonObject obj) {
        String database = optString(obj, "database", null);
        String scheduler = optString(obj, "scheduler", null);
        String monitoringService = optString(obj, "monitoringService", null);
        String socketService = optString(obj, "socketService", null);

        Platform.runLater(() -> {
            if (database != null) state.getStats().databaseHealthProperty().set(database);
            if (scheduler != null) state.getStats().schedulerHealthProperty().set(scheduler);
            if (monitoringService != null) state.getStats().monitoringServiceHealthProperty().set(monitoringService);
            if (socketService != null) state.getStats().socketServiceHealthProperty().set(socketService);
        });
    }

    private void applyJobSaved(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String jobId = optString(obj, "jobId", null);
        CompletableFuture<JobSaveResult> pending = requestId != null ? pendingSaves.remove(requestId) : null;
        if (pending != null) pending.complete(JobSaveResult.ok(jobId));
    }

    private void applyJobSaveFailed(JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String error = optString(obj, "error", "Unknown error");
        CompletableFuture<JobSaveResult> pending = requestId != null ? pendingSaves.remove(requestId) : null;
        if (pending != null) pending.complete(JobSaveResult.failed(error));
    }

    private void applySnapshot(JsonObject obj) {
        String localHost = optString(obj, "localHost", null);
        JsonArray jobsArray = obj.has("jobs") && !obj.get("jobs").isJsonNull()
                ? obj.getAsJsonArray("jobs") : new JsonArray();

        List<Job> parsed = new ArrayList<>();
        for (JsonElement el : jobsArray) {
            JsonObject j = el.getAsJsonObject();
            String id = optString(j, "id", null);
            if (id == null) continue;
            Job job = new Job(
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
            );
            if (j.has("config") && j.get("config").isJsonObject()) {
                job.setRawConfig(parseRawConfig(j.getAsJsonObject("config")));
            }
            parsed.add(job);
        }

        Platform.runLater(() -> {
            if (localHost != null) state.localHostProperty().set(localHost);
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
                    existing.setRawConfig(incoming.getRawConfig());
                }
            }
            state.getJobs().removeIf(j -> !incomingIds.contains(j.getId()));
            state.recomputeRunningStoppedCounts();
        });
    }

    /** Parses SNAPSHOT's nested "config" object (contract §1.1) into the DTO the Edit dialog pre-fills from. */
    private WatchJobConfig parseRawConfig(JsonObject c) {
        WatchJobConfig config = new WatchJobConfig();
        config.name = optString(c, "name", "");
        config.protocol = parseEnum(Protocol.class, optString(c, "protocol", null), Protocol.SFTP);
        config.direction = parseEnum(Direction.class, optString(c, "direction", null), Direction.INBOUND);
        config.transferMode = parseEnum(TransferMode.class, optString(c, "transferMode", null), TransferMode.ENTIRE_FOLDER);
        config.sourceHost = optString(c, "sourceHost", "");
        config.sourcePort = optInt(c, "sourcePort", 22);
        config.sourceUser = optString(c, "sourceUser", "");
        config.sourcePassword = ""; // never sent by the backend — see contract §1.1/§2.4
        config.sourcePath = optString(c, "sourcePath", "");
        config.destHost = optString(c, "destHost", "");
        config.destPort = optInt(c, "destPort", 22);
        config.destUser = optString(c, "destUser", "");
        config.destPassword = "";
        config.destPath = optString(c, "destPath", "");
        config.specificPattern = optString(c, "specificPattern", "");
        config.intervalSeconds = optInt(c, "intervalSeconds", 30);
        config.watchDepth = optInt(c, "watchDepth", 1);
        String remoteOsStr = optString(c, "remoteOs", null);
        config.remoteOs = remoteOsStr == null || remoteOsStr.isBlank() ? null : parseEnum(RemoteOs.class, remoteOsStr, null);
        return config;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
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

        notifyListeners(new ServiceEvent(eventType, jobId, jobName, filename, message, newStatus, timestamp));
    }

    private void notifyListeners(ServiceEvent event) {
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
                notifyListeners(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            boolean wasConnected = connected;
            connected = false;
            if (wasConnected) {
                notifyListeners(new ServiceEvent(ServiceEventType.CONNECTION_LOST, null, null, null, null, null, LocalTime.now()));
            }
            scheduleReconnect();
        }
    }
}
