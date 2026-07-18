package com.filewatcherservice.service;

import com.filewatchercommon.model.TransferEvent;
import com.filewatchercommon.model.WatchJob;
import com.filewatcherservice.database.DatabaseService;
import com.filewatcherservice.database.TransferRepository;
import com.filewatcherservice.scheduler.Scheduler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production WebSocket server speaking the "Relay ↔ Monitoring Service"
 * contract (docs/relay-monitoring-ws-contract.md) — the plain-JSON
 * SNAPSHOT / EVENT / SNAPSHOT_REQUEST / COMMAND protocol consumed by the
 * JavaFX app's {@code WebSocketServiceClient}.
 *
 * This drives real {@link WatchJob}s through {@link ServiceManager} /
 * {@link FileWatcherService} — there is no mock/demo data path here.
 *
 * Mapping notes (backend model → contract shape):
 *   - WatchJob.Status {@code WATCHING, TRANSFERRING} → {@code RUNNING};
 *     {@code IDLE, ERROR} → {@code STOPPED}; {@code PAUSED} → {@code DISABLED}.
 *     The contract has no dedicated "error" badge — a failed transfer is
 *     surfaced as its own {@code TRANSFER_FAILED} EVENT (toast/log entry)
 *     rather than flipping the status badge, exactly as the JavaFX
 *     EventDispatcher already expects.
 *   - {@code STARTING}/{@code RESTARTING} are emitted optimistically the
 *     moment a START/RESTART command is accepted, ahead of the real
 *     WATCHING transition that {@link FileWatcherService}'s job-state
 *     listener will report a moment later — this mirrors the contract's
 *     "the client does not assume success just because the command was
 *     sent" guidance while keeping the UI responsive.
 *   - {@code type} is derived from WatchJob.Protocol ("SFTP Watch", "FTP
 *     Watch", ...); {@code destPath}/{@code sourcePath} are rendered as
 *     {@code protocol://host/path} when a remote host is set, or the raw
 *     path for the local side of the transfer.
 *   - {@code credential} has no dedicated field on WatchJob (credentials
 *     aren't referenced by id there — see CredentialStore for the separate
 *     saved-credential list) so the job's own source/dest username is used
 *     as a stand-in.
 *   - {@code filesToday} is WatchJob's cumulative filesTransferred counter
 *     (there's no daily-reset counter in the engine yet).
 *
 * TEST_CONNECTION builds a throwaway {@link CredentialStore.Credential}
 * from the job's own source host/port/user/password and runs the same SSH
 * probe {@link FileWatcherService#testCredential} uses for the Credentials
 * panel, then reports the result as TRANSFER_COMPLETED/TRANSFER_FAILED.
 *
 * Also periodically broadcasts HEALTH (contract §1.4) for the dashboard's
 * Health Overview panel — see {@link #healthPayload()} for what each field
 * actually checks.
 */
public class RelayWebSocketServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(RelayWebSocketServer.class.getName());
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ServiceManager serviceManager;
    private final FileWatcherService watcherService;
    private final DatabaseService databaseService;
    private final Scheduler scheduler;
    private final CredentialStore credentialStore;
    private final Gson gson = new GsonBuilder().create();
    private final ScheduledExecutorService restartExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "relay-ws-restart");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService healthExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "relay-ws-health");
                t.setDaemon(true);
                return t;
            });

    public RelayWebSocketServer(String host, int port,
                                 ServiceManager serviceManager,
                                 FileWatcherService watcherService,
                                 DatabaseService databaseService,
                                 Scheduler scheduler) {
        super(new InetSocketAddress(host, port));
        this.serviceManager = serviceManager;
        this.watcherService = watcherService;
        this.databaseService = databaseService;
        this.scheduler = scheduler;
        // Reuses FileWatcherService's own CredentialStore rather than
        // constructing a second one — two independent in-memory caches over
        // the same table would drift out of sync with each other.
        this.credentialStore = watcherService.getCredentialStore();
        setReuseAddr(true);
        wireListeners();
    }

    /** Convenience overload — binds to localhost. */
    public RelayWebSocketServer(int port, ServiceManager serviceManager, FileWatcherService watcherService,
                                 DatabaseService databaseService, Scheduler scheduler) {
        this("localhost", port, serviceManager, watcherService, databaseService, scheduler);
    }

    public void shutdown() {
        restartExecutor.shutdownNow();
        healthExecutor.shutdownNow();
        try {
            stop();
        } catch (Exception ignored) {
        }
    }

    // ── Wire engine listeners → broadcast as contract EVENTs ─────────────────

    private void wireListeners() {
        watcherService.addJobStateListener(job ->
                broadcastEvent("SERVICE_STATUS_CHANGED", job.getId(), job.getName(), null, null, mapStatus(job)));

        watcherService.addEventListener(this::forwardTransferEvent);
    }

    private void forwardTransferEvent(TransferEvent event) {
        switch (event.getType()) {
            case TRANSFERRED -> broadcastEvent("TRANSFER_COMPLETED",
                    event.getJobId(), event.getJobName(), event.getFileName(), event.getMessage(), null);
            case ERROR -> broadcastEvent("TRANSFER_FAILED",
                    event.getJobId(), event.getJobName(), event.getFileName(), event.getMessage(), null);
            case STARTED -> broadcastEvent("SERVICE_STARTED",
                    event.getJobId(), event.getJobName(), null, null, null);
            case STOPPED -> broadcastEvent("SERVICE_STOPPED",
                    event.getJobId(), event.getJobName(), null, null, null);
            case CONNECTED -> broadcastEvent("HEARTBEAT",
                    event.getJobId(), event.getJobName(), null, null, null);
            case DETECTED, SKIPPED, DISCONNECTED -> {
                // No 1:1 contract event — still logged internally via NotificationService,
                // just not pushed as a distinct UI-facing EVENT to avoid noise.
            }
        }
    }

    // ── WebSocket lifecycle ────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info(() -> "Relay client connected: " + conn.getRemoteSocketAddress());
        conn.send(snapshotPayload());
        conn.send(healthPayload());
        conn.send(credentialsSnapshotPayload());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info(() -> "Relay client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.log(Level.WARNING, "Relay WebSocket error", ex);
    }

    @Override
    public void onStart() {
        LOG.info(() -> "Relay WebSocket server (contract) listening on ws://"
                + getAddress().getHostString() + ":" + getAddress().getPort() + "/ws");
        healthExecutor.scheduleAtFixedRate(this::broadcastHealth, 2, 15, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            LOG.warning(() -> "Malformed message, ignoring: " + message);
            return;
        }
        String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : null;
        if (type == null) return;

        switch (type) {
            case "SNAPSHOT_REQUEST" -> conn.send(snapshotPayload());
            case "COMMAND" -> handleCommand(obj);
            case "ADD_JOB" -> handleAddJob(conn, obj);
            case "UPDATE_JOB" -> handleUpdateJob(conn, obj);
            case "CREDENTIALS_REQUEST" -> conn.send(credentialsSnapshotPayload());
            case "ADD_CREDENTIAL" -> handleAddCredential(conn, obj);
            case "UPDATE_CREDENTIAL" -> handleUpdateCredential(conn, obj);
            case "DELETE_CREDENTIAL" -> handleDeleteCredential(obj);
            case "LOGS_REQUEST" -> handleLogsRequest(conn, obj);
            case "TEST_RAW_CONNECTION" -> handleTestRawConnection(conn, obj);
            case "BROWSE_REMOTE" -> handleBrowseRemote(conn, obj);
            default -> LOG.warning(() -> "Unknown message type from client: " + type);
        }
    }

    // ── Command handling ──────────────────────────────────────────────────

    private void handleCommand(JsonObject obj) {
        String jobId = obj.has("jobId") && !obj.get("jobId").isJsonNull() ? obj.get("jobId").getAsString() : null;
        String command = obj.has("command") && !obj.get("command").isJsonNull() ? obj.get("command").getAsString() : null;
        if (jobId == null || command == null) return;

        WatchJob job = serviceManager.getWatchJob(jobId);
        if (job == null) {
            LOG.warning(() -> "COMMAND '" + command + "' for unknown job id " + jobId);
            return;
        }

        switch (command) {
            case "START" -> {
                broadcastEvent("SERVICE_STATUS_CHANGED", job.getId(), job.getName(), null, null, "STARTING");
                serviceManager.start(jobId); // real outcome broadcast by the job-state listener
            }
            case "STOP" -> serviceManager.stop(jobId); // job-state listener broadcasts STOPPED
            case "RESTART" -> {
                broadcastEvent("SERVICE_STATUS_CHANGED", job.getId(), job.getName(), null, null, "RESTARTING");
                serviceManager.stop(jobId);
                restartExecutor.schedule(() -> serviceManager.start(jobId), 1, TimeUnit.SECONDS);
            }
            case "DELETE" -> {
                serviceManager.removeWatchJob(jobId);
                broadcast(snapshotPayload()); // no dedicated delete EVENT in the contract — refresh everyone
            }
            case "TEST_CONNECTION" -> testConnection(job);
            default -> LOG.warning(() -> "Unknown command: " + command);
        }
    }

    /**
     * BUG FIX: this used to always probe job.getSourceHost()/etc regardless
     * of direction -- correct for INBOUND (source is the remote side there)
     * but wrong for OUTBOUND, where dest is the remote side (same underlying
     * issue as the openSshSession bug fixed in FileWatcherService — see its
     * javadoc). LOCAL_TO_LOCAL has no remote side at all to test.
     */
    private void testConnection(WatchJob job) {
        if (job.getDirection() == WatchJob.Direction.LOCAL_TO_LOCAL) {
            broadcastEvent("TRANSFER_COMPLETED", job.getId(), job.getName(), null,
                    "Local-to-local job — no remote connection to test", null);
            return;
        }
        boolean testSource = job.getDirection() == WatchJob.Direction.INBOUND;

        CredentialStore.Credential probe = new CredentialStore.Credential();
        probe.setHost(testSource ? job.getSourceHost() : job.getDestHost());
        probe.setPort(testSource ? job.getSourcePort() : job.getDestPort());
        probe.setUsername(testSource ? job.getSourceUser() : job.getDestUser());
        probe.setPassword(testSource ? job.getSourcePassword() : job.getDestPassword());

        String error = watcherService.testCredential(probe);
        if (error == null) {
            broadcastEvent("TRANSFER_COMPLETED", job.getId(), job.getName(), null,
                    "Connection test succeeded", null);
        } else {
            broadcastEvent("TRANSFER_FAILED", job.getId(), job.getName(), null,
                    "Connection test failed: " + error, null);
        }
    }

    // ── Job CRUD (ADD_JOB / UPDATE_JOB — contract §2.3/2.4) ─────────────────

    private void handleAddJob(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        JsonObject jobJson = obj.has("job") && obj.get("job").isJsonObject() ? obj.getAsJsonObject("job") : null;
        if (jobJson == null) {
            sendJobSaveFailed(conn, requestId, "Missing \"job\" object");
            return;
        }
        try {
            WatchJob job = new WatchJob(); // fresh id, sensible defaults (see WatchJob())
            applyEditableFields(job, jobJson, false);
            serviceManager.addWatchJob(job);
            serviceManager.start(job.getId()); // new jobs start watching immediately, like restored ones on boot
            sendJobSaved(conn, requestId, job.getId(), job.getName());
            broadcast(snapshotPayload());
        } catch (ValidationException ve) {
            sendJobSaveFailed(conn, requestId, ve.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ADD_JOB failed", e);
            sendJobSaveFailed(conn, requestId, "Unexpected error: " + e.getMessage());
        }
    }

    private void handleUpdateJob(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String jobId = optString(obj, "jobId", null);
        JsonObject jobJson = obj.has("job") && obj.get("job").isJsonObject() ? obj.getAsJsonObject("job") : null;
        if (jobId == null || jobJson == null) {
            sendJobSaveFailed(conn, requestId, "Missing \"jobId\" or \"job\" object");
            return;
        }
        WatchJob job = serviceManager.getWatchJob(jobId);
        if (job == null) {
            sendJobSaveFailed(conn, requestId, "Job not found: " + jobId);
            return;
        }
        try {
            applyEditableFields(job, jobJson, true); // mutated in place — preserves stats/history
            serviceManager.updateWatchJob(job);
            sendJobSaved(conn, requestId, job.getId(), job.getName());
            broadcast(snapshotPayload());
        } catch (ValidationException ve) {
            sendJobSaveFailed(conn, requestId, ve.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "UPDATE_JOB failed", e);
            sendJobSaveFailed(conn, requestId, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Applies client-supplied fields onto {@code job} (either a brand-new
     * WatchJob for ADD_JOB, or the existing one for UPDATE_JOB — either way,
     * mutated in place). Throws {@link ValidationException} with a
     * user-facing message on bad input.
     *
     * Password handling matches contract §2.4: on an update, a blank/missing
     * password means "leave whatever's already stored alone"; on an add,
     * blank just means "no password".
     */
    private void applyEditableFields(WatchJob job, JsonObject j, boolean isUpdate) {
        String name = optString(j, "name", null);
        if (name == null || name.isBlank()) throw new ValidationException("Name is required");
        job.setName(name.trim());

        job.setProtocol(parseEnum(WatchJob.Protocol.class, optString(j, "protocol", null), WatchJob.Protocol.SFTP));
        job.setDirection(parseEnum(WatchJob.Direction.class, optString(j, "direction", null), WatchJob.Direction.INBOUND));
        WatchJob.TransferMode mode = parseEnum(WatchJob.TransferMode.class, optString(j, "transferMode", null), WatchJob.TransferMode.ENTIRE_FOLDER);
        job.setTransferMode(mode);

        String sourcePath = optString(j, "sourcePath", null);
        if (sourcePath == null || sourcePath.isBlank()) throw new ValidationException("Source path is required");
        job.setSourcePath(sourcePath.trim());

        String destPath = optString(j, "destPath", null);
        if (destPath == null || destPath.isBlank()) throw new ValidationException("Destination path is required");
        job.setDestPath(destPath.trim());

        job.setSourceHost(optString(j, "sourceHost", null));
        job.setSourcePort(optInt(j, "sourcePort", 22));
        job.setSourceUser(optString(j, "sourceUser", null));

        job.setDestHost(optString(j, "destHost", null));
        job.setDestPort(optInt(j, "destPort", 22));
        job.setDestUser(optString(j, "destUser", null));

        String sourcePassword = optString(j, "sourcePassword", null);
        if (!isUpdate || (sourcePassword != null && !sourcePassword.isBlank())) {
            job.setSourcePassword(sourcePassword);
        }
        String destPassword = optString(j, "destPassword", null);
        if (!isUpdate || (destPassword != null && !destPassword.isBlank())) {
            job.setDestPassword(destPassword);
        }

        if (mode == WatchJob.TransferMode.SPECIFIC) {
            String pattern = optString(j, "specificPattern", null);
            if (pattern == null || pattern.isBlank()) {
                throw new ValidationException("A file pattern is required when transfer mode is SPECIFIC");
            }
            job.setSpecificPattern(pattern.trim());
        } else {
            job.setSpecificPattern(null);
        }

        // intervalSeconds is add-only (contract §2.4 note): changing it on a
        // running job wouldn't take effect anyway (the polling loop reads it
        // once at start), so UPDATE_JOB leaves whatever's already there alone
        // rather than accepting a value that silently wouldn't apply.
        if (!isUpdate) {
            int intervalSeconds = optInt(j, "intervalSeconds", 30);
            if (intervalSeconds <= 0) throw new ValidationException("Polling interval must be greater than 0 seconds");
            job.setIntervalSeconds(intervalSeconds);
        }

        int watchDepth = optInt(j, "watchDepth", 1);
        if (watchDepth < 1) watchDepth = 1;
        job.setWatchDepth(watchDepth);

        // remoteOs (only meaningful for INBOUND -- see FileWatcherService
        // .runInboundWatcher's switch) was never read here before, so it was
        // always null on jobs created through ADD_JOB -- the actual root
        // cause of runInboundWatcher's switch(remoteOs) NPE (fixed
        // separately with a null-guard there, but this is the real fix:
        // actually setting the value). null/unset here is fine and
        // intentional for OUTBOUND/LOCAL_TO_LOCAL, where it's simply unused.
        String remoteOsStr = optString(j, "remoteOs", null);
        job.setRemoteOs(remoteOsStr == null || remoteOsStr.isBlank()
                ? null : parseEnum(com.filewatchercommon.util.OsType.class, remoteOsStr, null));
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

    private static String optString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    /** Thrown for user-facing validation failures in {@link #applyEditableFields}; message goes straight into JOB_SAVE_FAILED. */
    private static final class ValidationException extends RuntimeException {
        ValidationException(String message) {
            super(message);
        }
    }

    private void sendJobSaved(WebSocket conn, String requestId, String jobId, String jobName) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "JOB_SAVED");
        root.addProperty("requestId", requestId);
        root.addProperty("jobId", jobId);
        root.addProperty("jobName", jobName);
        conn.send(gson.toJson(root));
    }

    private void sendJobSaveFailed(WebSocket conn, String requestId, String error) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "JOB_SAVE_FAILED");
        root.addProperty("requestId", requestId);
        root.addProperty("error", error);
        conn.send(gson.toJson(root));
    }

    // ── Credential CRUD (ADD_CREDENTIAL / UPDATE_CREDENTIAL / DELETE_CREDENTIAL — contract §2.6/2.7/2.8) ──

    private void handleAddCredential(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        JsonObject credJson = obj.has("credential") && obj.get("credential").isJsonObject()
                ? obj.getAsJsonObject("credential") : null;
        if (credJson == null) {
            sendCredentialSaveFailed(conn, requestId, "Missing \"credential\" object");
            return;
        }
        try {
            CredentialStore.Credential cred = new CredentialStore.Credential();
            applyCredentialFields(cred, credJson, false);
            credentialStore.save(cred); // assigns an id since none was set
            sendCredentialSaved(conn, requestId, cred.getId());
            broadcast(credentialsSnapshotPayload());
        } catch (ValidationException ve) {
            sendCredentialSaveFailed(conn, requestId, ve.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ADD_CREDENTIAL failed", e);
            sendCredentialSaveFailed(conn, requestId, "Unexpected error: " + e.getMessage());
        }
    }

    private void handleUpdateCredential(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String credentialId = optString(obj, "credentialId", null);
        JsonObject credJson = obj.has("credential") && obj.get("credential").isJsonObject()
                ? obj.getAsJsonObject("credential") : null;
        if (credentialId == null || credJson == null) {
            sendCredentialSaveFailed(conn, requestId, "Missing \"credentialId\" or \"credential\" object");
            return;
        }
        CredentialStore.Credential cred = credentialStore.findById(credentialId).orElse(null);
        if (cred == null) {
            sendCredentialSaveFailed(conn, requestId, "Credential not found: " + credentialId);
            return;
        }
        try {
            applyCredentialFields(cred, credJson, true); // mutated in place — preserves lastUsed/usedByJobIds
            credentialStore.save(cred);
            sendCredentialSaved(conn, requestId, cred.getId());
            broadcast(credentialsSnapshotPayload());
        } catch (ValidationException ve) {
            sendCredentialSaveFailed(conn, requestId, ve.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "UPDATE_CREDENTIAL failed", e);
            sendCredentialSaveFailed(conn, requestId, "Unexpected error: " + e.getMessage());
        }
    }

    private void handleDeleteCredential(JsonObject obj) {
        String credentialId = optString(obj, "credentialId", null);
        if (credentialId == null) return;
        credentialStore.delete(credentialId); // contract §2.8 — allowed even if still referenced by jobs
        broadcast(credentialsSnapshotPayload());
    }

    // ── Historical transfer logs (LOGS_REQUEST / LOGS_RESPONSE — contract §2.9/§1.7) ────

    private void handleLogsRequest(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        String jobId = optString(obj, "jobId", null);
        String eventType = optString(obj, "eventType", null);
        String search = optString(obj, "search", null);
        int limit = optInt(obj, "limit", 200);

        LocalDateTime since = null;
        if (obj.has("sinceEpochSeconds") && !obj.get("sinceEpochSeconds").isJsonNull()) {
            try {
                long epochSeconds = obj.get("sinceEpochSeconds").getAsLong();
                since = java.time.Instant.ofEpochSecond(epochSeconds)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
            } catch (Exception ignored) {
                // malformed -- just don't filter by date rather than fail the whole request
            }
        }

        List<TransferRepository.LogEntry> entries = watcherService.getTransferRepository()
                .queryFiltered(jobId, eventType, search, since, limit);

        JsonObject root = new JsonObject();
        root.addProperty("type", "LOGS_RESPONSE");
        root.addProperty("requestId", requestId);
        JsonArray entriesArray = new JsonArray();
        for (TransferRepository.LogEntry entry : entries) {
            JsonObject e = new JsonObject();
            e.addProperty("id", entry.id());
            e.addProperty("jobId", entry.jobId());
            e.addProperty("jobName", entry.jobName());
            e.addProperty("eventType", entry.eventType());
            e.addProperty("message", entry.message());
            e.addProperty("filename", entry.filename());
            e.addProperty("sizeBytes", entry.sizeBytes());
            e.addProperty("occurredAt", entry.occurredAt() != null ? entry.occurredAt().format(TS_FMT) : null);
            entriesArray.add(e);
        }
        root.add("entries", entriesArray);
        conn.send(gson.toJson(root));
    }

    // ── Raw connection testing / remote browsing (contract §2.10/§1.8, §2.11/§1.9) ────
    // Both need a full host/port/username/password before any job or credential
    // exists to hang the request off of, so they build a throwaway
    // CredentialStore.Credential from the raw request fields — same approach
    // testConnection(WatchJob) already uses for an *existing* job's own creds.

    private void handleTestRawConnection(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        CredentialStore.Credential probe = credentialFromRawFields(obj);
        boolean detectOs = obj.has("detectOs") && !obj.get("detectOs").isJsonNull() && obj.get("detectOs").getAsBoolean();

        String error = watcherService.testCredential(probe);
        JsonObject root = new JsonObject();
        root.addProperty("type", "TEST_RAW_CONNECTION_RESULT");
        root.addProperty("requestId", requestId);
        root.addProperty("success", error == null);
        root.addProperty("error", error);
        // Only worth the extra SSH round-trip when the caller actually wants it
        // (contract §2.10's optional "detectOs") -- e.g. the job form's Remote
        // OS auto-fill, but not every plain credential test.
        if (error == null && detectOs) {
            root.addProperty("detectedOs", watcherService.detectRemoteOs(probe).name());
        }
        conn.send(gson.toJson(root));
    }

    private void handleBrowseRemote(WebSocket conn, JsonObject obj) {
        String requestId = optString(obj, "requestId", null);
        CredentialStore.Credential probe = credentialFromRawFields(obj);
        String path = optString(obj, "path", null);

        RemoteListing listing = watcherService.listRemoteDirectory(probe, path);

        JsonObject root = new JsonObject();
        root.addProperty("type", "BROWSE_REMOTE_RESPONSE");
        root.addProperty("requestId", requestId);
        root.addProperty("path", listing.path());
        root.addProperty("error", listing.error());
        JsonArray entriesArray = new JsonArray();
        for (RemoteEntry entry : listing.entries()) {
            JsonObject e = new JsonObject();
            e.addProperty("name", entry.name());
            e.addProperty("directory", entry.directory());
            entriesArray.add(e);
        }
        root.add("entries", entriesArray);
        conn.send(gson.toJson(root));
    }

    private CredentialStore.Credential credentialFromRawFields(JsonObject obj) {
        CredentialStore.Credential probe = new CredentialStore.Credential();
        probe.setHost(optString(obj, "host", null));
        probe.setPort(optInt(obj, "port", 22));
        probe.setUsername(optString(obj, "username", null));
        probe.setPassword(optString(obj, "password", null));
        return probe;
    }

    /**
     * Applies client-supplied fields onto {@code cred} (in place, either a
     * fresh Credential for ADD or the existing one for UPDATE). Same
     * blank-password-means-unchanged convention as job passwords (contract
     * §2.4/§2.7).
     */
    private void applyCredentialFields(CredentialStore.Credential cred, JsonObject c, boolean isUpdate) {
        String host = optString(c, "host", null);
        if (host == null || host.isBlank()) throw new ValidationException("Host is required");
        cred.setHost(host.trim());

        String username = optString(c, "username", null);
        if (username == null || username.isBlank()) throw new ValidationException("Username is required");
        cred.setUsername(username.trim());

        cred.setPort(optInt(c, "port", 22));
        cred.setProtocol(parseEnum(WatchJob.Protocol.class, optString(c, "protocol", null), WatchJob.Protocol.SFTP).name());

        String password = optString(c, "password", null);
        if (!isUpdate || (password != null && !password.isBlank())) {
            cred.setPassword(password);
        }
    }

    private void sendCredentialSaved(WebSocket conn, String requestId, String credentialId) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "CREDENTIAL_SAVED");
        root.addProperty("requestId", requestId);
        root.addProperty("credentialId", credentialId);
        conn.send(gson.toJson(root));
    }

    private void sendCredentialSaveFailed(WebSocket conn, String requestId, String error) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "CREDENTIAL_SAVE_FAILED");
        root.addProperty("requestId", requestId);
        root.addProperty("error", error);
        conn.send(gson.toJson(root));
    }

    private String credentialsSnapshotPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "CREDENTIALS_SNAPSHOT");
        JsonArray credsArray = new JsonArray();
        for (CredentialStore.Credential cred : credentialStore.getAll()) {
            credsArray.add(gson.toJsonTree(toContractCredential(cred)));
        }
        root.add("credentials", credsArray);
        return gson.toJson(root);
    }

    /** Wire shape for a credential row (contract §1.5) — deliberately excludes the password. */
    /**
     * Sends the credential's actual stored password back to the client.
     *
     * DEVIATION FROM EARLIER DESIGN: every other password-bearing message in
     * this contract deliberately omits passwords (SNAPSHOT's job config,
     * the original CREDENTIALS_SNAPSHOT) so an edit form starts blank and a
     * blank value on update means "leave it alone" -- see contract §2.4/§2.7.
     * That's still the safer default for a broadcast message every connected
     * client receives. This was changed on explicit request so the Edit
     * Credential form can show/retrieve the actual password instead of
     * always starting blank. Worth knowing: this means the plaintext
     * password now goes out over the wire to every connected client on
     * every CREDENTIALS_SNAPSHOT, not just the one editing it.
     */
    private ContractCredential toContractCredential(CredentialStore.Credential cred) {
        return new ContractCredential(
                cred.getId(),
                cred.getHost(),
                cred.getPort(),
                cred.getUsername(),
                cred.getPassword(),
                cred.getProtocol(),
                cred.getLastUsed() != null ? cred.getLastUsed().format(TS_FMT) : null,
                cred.getUsedByJobIds()
        );
    }

    private record ContractCredential(
            String id,
            String host,
            int port,
            String username,
            String password,
            String protocol,
            String lastUsed,
            java.util.List<String> usedByJobIds
    ) {
    }

    // ── Outbound message building ─────────────────────────────────────────

    private void broadcastHealth() {
        String payload = healthPayload();
        for (WebSocket c : getConnections()) {
            c.send(payload);
        }
    }

    /**
     * Builds the HEALTH payload (contract §1.4). "Monitoring Service" is a
     * liveness tautology — if this code is running at all, the process
     * answering is by definition up — but it's still a real signal rather
     * than a hardcoded UI value, and gives a place to plug in a deeper
     * check later (e.g. "at least one job successfully polled recently").
     * "Socket Service" is always DISABLED: it's a documented, not-yet-
     * implemented stub (see ServiceMain Phase 7) — that's the accurate
     * answer today, not a guess.
     */
    private String healthPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "HEALTH");
        root.addProperty("database", checkDatabaseHealthy() ? "HEALTHY" : "UNHEALTHY");
        root.addProperty("scheduler", scheduler.isRunning() ? "HEALTHY" : "UNHEALTHY");
        root.addProperty("monitoringService", "HEALTHY");
        root.addProperty("socketService", "DISABLED");
        return gson.toJson(root);
    }

    private boolean checkDatabaseHealthy() {
        try {
            return databaseService.getConnection().isValid(2); // 2s timeout — JDBC standard liveness check
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Database health check failed", e);
            return false;
        }
    }

    /**
     * Best-effort local hostname/IP detection, cached (it can't change while
     * the process is running, and a DNS-backed lookup on every SNAPSHOT would
     * be wasteful). Powers the job form's auto-filled "this machine" side —
     * see contract §1.1's "localHost" field.
     */
    private final String localHost = detectLocalHost();

    private static String detectLocalHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            LOG.warning(() -> "Could not auto-detect local hostname, falling back to \"localhost\": " + e.getMessage());
            return "localhost";
        }
    }

    private String snapshotPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "SNAPSHOT");
        root.addProperty("localHost", localHost);
        JsonArray jobsArray = new JsonArray();
        for (WatchJob job : serviceManager.getWatchJobs()) {
            jobsArray.add(gson.toJsonTree(toContractJob(job)));
        }
        root.add("jobs", jobsArray);
        return gson.toJson(root);
    }

    private void broadcastEvent(String eventType, String jobId, String jobName, String filename,
                                 String message, String newStatus) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "EVENT");
        root.addProperty("eventType", eventType);
        root.addProperty("jobId", jobId);
        root.addProperty("jobName", jobName);
        root.addProperty("filename", filename);
        root.addProperty("message", message);
        root.addProperty("newStatus", newStatus);
        root.addProperty("timestamp", LocalDateTime.now().format(TS_FMT));
        broadcast(gson.toJson(root));
    }

    // ── WatchJob → contract job shape ─────────────────────────────────────

    private ContractJob toContractJob(WatchJob job) {
        return new ContractJob(
                job.getId(),
                job.getName(),
                describeType(job.getProtocol()),
                describeSide(job.getSourceHost(), job.getSourcePath(), job.getProtocol()),
                describeSide(job.getDestHost(), job.getDestPath(), job.getProtocol()),
                describeInterval(job.getIntervalSeconds()),
                describeCredential(job),
                mapStatus(job),
                (int) job.getFilesTransferred(),
                describeRelativeTime(job.getLastTransfer()),
                describeActivity(job),
                toJobConfig(job)
        );
    }

    /**
     * The raw, unformatted editable fields for a job — nested under
     * SNAPSHOT's {@code config} key (contract §1.1), in exactly the shape a
     * client sends back in ADD_JOB/UPDATE_JOB's {@code job} object (§2.3/
     * §2.4). Kept as a separate nested object (rather than flattened
     * alongside the display fields) specifically so its raw sourcePath/
     * destPath never collides with the *formatted* sourcePath/destPath used
     * for display — see {@link #describeSide} for why those can differ
     * (protocol://host prefix).
     */
    private JobConfig toJobConfig(WatchJob job) {
        return new JobConfig(
                job.getName(),
                job.getProtocol() == null ? null : job.getProtocol().name(),
                job.getDirection() == null ? null : job.getDirection().name(),
                job.getTransferMode() == null ? null : job.getTransferMode().name(),
                job.getSourceHost(),
                job.getSourcePort(),
                job.getSourceUser(),
                job.getSourcePath(),
                job.getDestHost(),
                job.getDestPort(),
                job.getDestUser(),
                job.getDestPath(),
                job.getSpecificPattern(),
                job.getIntervalSeconds(),
                job.getWatchDepth(),
                job.getRemoteOs() == null ? null : job.getRemoteOs().name()
        );
    }

    private static String describeType(WatchJob.Protocol protocol) {
        if (protocol == null) return "Watch";
        return switch (protocol) {
            case SFTP -> "SFTP Watch";
            case FTP -> "FTP Watch";
            case SCP -> "SCP Watch";
            case LOCAL -> "Local Watch";
        };
    }

    private static String describeSide(String host, String path, WatchJob.Protocol protocol) {
        String p = path == null ? "" : path;
        if (host == null || host.isBlank() || protocol == WatchJob.Protocol.LOCAL) {
            return p;
        }
        String scheme = protocol.name().toLowerCase();
        return scheme + "://" + host + (p.startsWith("/") ? p : "/" + p);
    }

    private static String describeInterval(int seconds) {
        if (seconds <= 0) return "—";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static String describeCredential(WatchJob job) {
        if (job.getSourceUser() != null && !job.getSourceUser().isBlank()) return job.getSourceUser();
        if (job.getDestUser() != null && !job.getDestUser().isBlank()) return job.getDestUser();
        return "none";
    }

    private static String describeActivity(WatchJob job) {
        if (job.getStatus() == WatchJob.Status.ERROR && job.getLastError() != null && !job.getLastError().isBlank()) {
            return job.getLastError();
        }
        return switch (job.getStatus()) {
            case WATCHING -> "Polling source directory";
            case TRANSFERRING -> "Transferring file…";
            case PAUSED -> "Paused by operator";
            case ERROR -> "Error";
            case IDLE -> "Idle";
        };
    }

    /** WatchJob's 5 statuses collapse onto the contract's 5 status badges — see class javadoc for the mapping rationale. */
    private static String mapStatus(WatchJob job) {
        return switch (job.getStatus()) {
            case WATCHING, TRANSFERRING -> "RUNNING";
            case PAUSED -> "DISABLED";
            case IDLE, ERROR -> "STOPPED";
        };
    }

    private static String describeRelativeTime(LocalDateTime timestamp) {
        if (timestamp == null) return "—";
        long seconds = Duration.between(timestamp, LocalDateTime.now()).getSeconds();
        if (seconds < 0) seconds = 0;
        if (seconds < 5) return "just now";
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    /** Wire shape for a job row — field names/order match the contract's SNAPSHOT example exactly. */
    private record ContractJob(
            String id,
            String name,
            String type,
            String sourcePath,
            String destPath,
            String pollingInterval,
            String credential,
            String status,
            int filesToday,
            String lastTransfer,
            String currentActivity,
            JobConfig config
    ) {
    }

    /**
     * Raw editable config, nested under ContractJob.config (contract §1.1)
     * and identical in shape to what ADD_JOB/UPDATE_JOB's "job" object
     * carries (§2.3/§2.4) — deliberately excludes passwords.
     */
    private record JobConfig(
            String name,
            String protocol,
            String direction,
            String transferMode,
            String sourceHost,
            int sourcePort,
            String sourceUser,
            String sourcePath,
            String destHost,
            int destPort,
            String destUser,
            String destPath,
            String specificPattern,
            int intervalSeconds,
            int watchDepth,
            String remoteOs
    ) {
    }
}
