package com.filewatcherservice.service;

import com.filewatchercommon.model.CredentialMessage;
import com.filewatchercommon.model.NotificationMessage;
import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.service.NotificationService;
import com.filewatchercommon.ws.WsCommands;
import com.filewatchercommon.ws.WsTypes;
import com.filewatcherservice.config.AppConfig;
import com.filewatcherservice.config.ConfigLoader;
import com.google.gson.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded WebSocket server. Host and port are driven by AppConfig
 * (services.json: websocketHost / websocketPort), not hardcoded.
 *
 * CHANGE: job lifecycle commands (START_JOB, STOP_JOB, START_ALL, STOP_ALL,
 * ADD_JOB, UPDATE_JOB, DELETE_JOB) now go through ServiceManager instead of
 * calling FileWatcherService/JobStore directly. ServiceManager handles both
 * the runtime engine call and persistence (save/delete) internally — see
 * ServiceManager.addWatchJob/updateWatchJob/removeWatchJob — so this class
 * no longer needs to know about JobStore at all.
 *
 * FileWatcherService is still injected directly for things ServiceManager
 * deliberately doesn't wrap: credentials, health stats, and listener
 * registration for pushing state/events to the UI (those aren't part of
 * the MonitorService lifecycle contract).
 *
 * Receives commands from UI (cmd field):
 *   GET_JOBS, START_JOB, STOP_JOB, START_ALL, STOP_ALL, ADD_JOB, UPDATE_JOB,
 *   DELETE_JOB, HEALTH, GET_CREDENTIALS, SAVE_CREDENTIAL, DELETE_CREDENTIAL,
 *   TEST_CREDENTIAL, GET_CONFIGURATION, UPDATE_CONFIGURATION
 *
 * Pushes events to UI (type field):
 *   INIT        — full job list on connect
 *   JOB_STATE   — job status changed
 *   EVENT       — file transfer event
 *   NOTIFICATION — error notification
 *   CONFIGURATION — current AppConfig (reply to GET_CONFIGURATION, and
 *                   broadcast to all clients after UPDATE_CONFIGURATION)
 */
public class ServiceWebSocketServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(ServiceWebSocketServer.class.getName());

    private final ServiceManager      serviceManager;
    private final FileWatcherService  watcherService;
    private final NotificationService notificationService;
    private final AppConfig           config;
    private final int                 port;

    private final long serviceStartMs = System.currentTimeMillis();
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(java.time.LocalDateTime.class,
                    (JsonSerializer<java.time.LocalDateTime>)
                            (src, t, ctx) -> new JsonPrimitive(src.toString()))
            .registerTypeAdapter(java.time.LocalDateTime.class,
                    (JsonDeserializer<java.time.LocalDateTime>)
                            (json, t, ctx) -> java.time.LocalDateTime.parse(json.getAsString()))
            .create();

    public ServiceWebSocketServer(String host, int port,
                                  ServiceManager serviceManager,
                                  FileWatcherService watcherService,
                                  NotificationService notificationService,
                                  AppConfig config) {
        super(new InetSocketAddress(host, port));
        this.port                = port;
        this.serviceManager      = serviceManager;
        this.watcherService      = watcherService;
        this.notificationService = notificationService;
        this.config              = config;
        setReuseAddr(true);
        wireListeners();
    }

    /** Convenience overload — binds to localhost, kept for callers that don't pass a host explicitly. */
    public ServiceWebSocketServer(int port,
                                  ServiceManager serviceManager,
                                  FileWatcherService watcherService,
                                  NotificationService notificationService,
                                  AppConfig config) {
        this("localhost", port, serviceManager, watcherService, notificationService, config);
    }

    // ── Wire service listeners → broadcast to all UI clients ─────────────────

    private void wireListeners() {
        // Job state changed → push to all connected UIs
        watcherService.addJobStateListener(job -> {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", WsTypes.JOB_STATE);
            msg.add("job", GSON.toJsonTree(job));
            broadcast(msg.toString());
        });

        // Transfer event → push to all connected UIs
        watcherService.addEventListener(event -> {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", WsTypes.EVENT);
            msg.add("event", GSON.toJsonTree(event));
            broadcast(msg.toString());
        });

        // Notification/error → push to all connected UIs
        notificationService.addListener(notifications -> {
            if (notifications.isEmpty()) return;
            NotificationMessage nm = notifications.get(0);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", WsTypes.NOTIFICATION);
            msg.add("notification", GSON.toJsonTree(nm));
            broadcast(msg.toString());
        });
    }

    // Health Report
    private String buildHealthReply() {
        java.lang.management.MemoryMXBean   memBean =
                java.lang.management.ManagementFactory.getMemoryMXBean();
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();

        long heapUsed  = memBean.getHeapMemoryUsage().getUsed();
        long heapMax   = memBean.getHeapMemoryUsage().getMax();
        long uptimeMs  = System.currentTimeMillis() - serviceStartMs;
        double cpuLoad = osBean.getProcessCpuLoad() * 100.0;

        long activeJobs = serviceManager.getWatchJobs().stream()
                .filter(j -> j.getStatus() == WatchJob.Status.WATCHING
                        || j.getStatus() == WatchJob.Status.TRANSFERRING)
                .count();

        long totalTransfers = serviceManager.getWatchJobs().stream()
                .mapToLong(WatchJob::getFilesTransferred)
                .sum();

        long totalBytes = serviceManager.getWatchJobs().stream()
                .mapToLong(WatchJob::getBytesTransferred)
                .sum();

        String lastError = serviceManager.getWatchJobs().stream()
                .filter(j -> j.getLastError() != null && !j.getLastError().isBlank())
                .map(WatchJob::getLastError)
                .findFirst()
                .orElse(null);

        JsonObject stats = new JsonObject();
        stats.addProperty("uptimeMs",       uptimeMs);
        stats.addProperty("heapUsedBytes",  heapUsed);
        stats.addProperty("heapMaxBytes",   heapMax);
        stats.addProperty("cpuPercent",     Math.max(0, cpuLoad));
        stats.addProperty("activeJobs",     activeJobs);
        stats.addProperty("totalJobs",      serviceManager.getWatchJobs().size());
        stats.addProperty("totalTransfers", totalTransfers);
        stats.addProperty("totalBytes",     totalBytes);
        stats.addProperty("wsPort",         port);
        if (lastError != null)
            stats.addProperty("lastError",  lastError);

        JsonObject reply = new JsonObject();
        reply.addProperty("type", WsTypes.HEALTH);
        reply.add("stats", stats);
        return reply.toString();
    }

    // Configuration — GET_CONFIGURATION reply and UPDATE_CONFIGURATION confirmation push share this shape
    private String buildConfigReply() {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", WsTypes.CONFIGURATION);
        reply.add("config", GSON.toJsonTree(config));
        return reply.toString();
    }

    /**
     * Applies an UPDATE_CONFIGURATION payload to the live AppConfig instance,
     * persists it back to services.json, and hot-applies the fields that can
     * take effect without a restart (currently: log level). Fields that can't
     * be hot-applied (websocketHost/websocketPort, schedulerThreadPoolSize,
     * maxConcurrentTransfers — these are baked into already-constructed
     * objects: the bound server socket, the Scheduler's thread pool, and
     * FileWatcherService's watchPool) are still persisted so they take effect
     * on the next service restart, but are intentionally not mutated on the
     * live objects, since doing so wouldn't actually change running behaviour
     * and would just make the in-memory config lie about what's active.
     *
     * Unknown/missing fields in the payload are left untouched — this mirrors
     * Gson's "partial JSON is safe" behaviour that ConfigLoader.load() already
     * relies on, so a UI form that only sends a subset of fields can't zero
     * out the rest.
     */
    private void applyConfigUpdate(JsonObject patch) {
        if (patch == null) return;

        if (patch.has("defaultIntervalSeconds"))
            config.defaultIntervalSeconds = patch.get("defaultIntervalSeconds").getAsInt();
        if (patch.has("pollingFallbackEnabled"))
            config.pollingFallbackEnabled = patch.get("pollingFallbackEnabled").getAsBoolean();
        if (patch.has("logMaxFileSizeMb"))
            config.logMaxFileSizeMb = patch.get("logMaxFileSizeMb").getAsInt();
        if (patch.has("logMaxFileCount"))
            config.logMaxFileCount = patch.get("logMaxFileCount").getAsInt();
        if (patch.has("heartbeatIntervalSeconds"))
            config.heartbeatIntervalSeconds = patch.get("heartbeatIntervalSeconds").getAsInt();
        if (patch.has("sshConnectTimeoutMs"))
            config.sshConnectTimeoutMs = patch.get("sshConnectTimeoutMs").getAsInt();
        if (patch.has("sftpChannelTimeoutMs"))
            config.sftpChannelTimeoutMs = patch.get("sftpChannelTimeoutMs").getAsInt();

        if (patch.has("logLevel")) {
            String level = patch.get("logLevel").getAsString();
            config.logLevel = level;
            try {
                Logger.getLogger("").setLevel(Level.parse(level.toUpperCase()));
                LOG.info("Log level hot-applied: " + level);
            } catch (IllegalArgumentException e) {
                LOG.warning("UPDATE_CONFIGURATION sent unknown logLevel '" + level + "', ignoring hot-apply");
            }
        }

        // Persisted but not hot-applied — see method javadoc.
        if (patch.has("websocketHost"))
            config.websocketHost = patch.get("websocketHost").getAsString();
        if (patch.has("websocketPort"))
            config.websocketPort = patch.get("websocketPort").getAsInt();
        if (patch.has("schedulerThreadPoolSize"))
            config.schedulerThreadPoolSize = patch.get("schedulerThreadPoolSize").getAsInt();
        if (patch.has("maxConcurrentTransfers"))
            config.maxConcurrentTransfers = patch.get("maxConcurrentTransfers").getAsInt();

        ConfigLoader.save(config);
        LOG.info("Configuration updated via UPDATE_CONFIGURATION: " + config);
    }

    // ── WebSocket lifecycle ───────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info("UI connected: " + conn.getRemoteSocketAddress());

        JsonObject msg = new JsonObject();
        msg.addProperty("type", WsTypes.INIT);
        msg.add("jobs", GSON.toJsonTree(serviceManager.getWatchJobs()));
        msg.add("notifications", GSON.toJsonTree(notificationService.getAll()));
        conn.send(msg.toString());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("UI disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onError(WebSocket conn, Exception e) {
        LOG.warning("WebSocket error: " + e.getMessage());
    }

    @Override
    public void onStart() {
        LOG.info("WebSocket server started on ws://" + getAddress().getHostString() + ":" + port);
    }

    // ── Handle commands from UI ───────────────────────────────────────────────

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String cmd = json.get("cmd").getAsString();

            switch (cmd) {
                case WsCommands.GET_JOBS -> {
                    JsonObject reply = new JsonObject();
                    reply.addProperty("type", WsTypes.INIT);
                    reply.add("jobs", GSON.toJsonTree(serviceManager.getWatchJobs()));
                    conn.send(reply.toString());
                }
                case WsCommands.START_JOB -> {
                    String id = json.get("id").getAsString();
                    serviceManager.start(id);
                    conn.send(okReply());
                }
                case WsCommands.STOP_JOB -> {
                    String id = json.get("id").getAsString();
                    serviceManager.stop(id);
                    conn.send(okReply());
                }
                case WsCommands.START_ALL -> {
                    serviceManager.startAll();
                    conn.send(okReply());
                }
                case WsCommands.STOP_ALL -> {
                    serviceManager.stopAll();
                    conn.send(okReply());
                }
                case WsCommands.ADD_JOB -> {
                    WatchJob job = GSON.fromJson(json.get("job"), WatchJob.class);
                    serviceManager.addWatchJob(job); // also persists
                    conn.send(okReply());
                }
                case WsCommands.DELETE_JOB -> {
                    String id = json.get("id").getAsString();
                    serviceManager.removeWatchJob(id); // also deletes the row
                    conn.send(okReply());
                }
                case WsCommands.UPDATE_JOB -> {
                    WatchJob job = GSON.fromJson(json.get("job"), WatchJob.class);
                    serviceManager.updateWatchJob(job); // also persists
                    conn.send(okReply());
                }
                case WsCommands.HEALTH -> conn.send(buildHealthReply());
                case WsCommands.GET_CONFIGURATION -> conn.send(buildConfigReply());
                case WsCommands.UPDATE_CONFIGURATION -> {
                    applyConfigUpdate(json.getAsJsonObject("config"));
                    broadcast(buildConfigReply());
                    conn.send(okReply());
                }
                case WsCommands.GET_CREDENTIALS -> {
                    List<CredentialMessage> msgs = watcherService.getCredentialStore()
                            .getAll().stream()
                            .map(ServiceWebSocketServer::toCredentialMessage)
                            .collect(java.util.stream.Collectors.toList());
                    JsonObject reply = new JsonObject();
                    reply.addProperty("type", WsTypes.CREDENTIALS);
                    reply.add("credentials", GSON.toJsonTree(msgs));
                    conn.send(reply.toString());
                }
                case WsCommands.SAVE_CREDENTIAL -> {
                    CredentialStore.Credential cred = GSON.fromJson(
                            json.get("credential"), CredentialStore.Credential.class);
                    watcherService.getCredentialStore().save(cred);
                    conn.send(okReply());
                }
                case WsCommands.DELETE_CREDENTIAL -> {
                    String id = json.get("id").getAsString();
                    watcherService.getCredentialStore().delete(id);
                    conn.send(okReply());
                }
                case WsCommands.TEST_CREDENTIAL -> {
                    String credId = json.get("id").getAsString();
                    CredentialStore.Credential cred =
                            watcherService.getCredentialStore().findById(credId).orElse(null);
                    JsonObject reply = new JsonObject();
                    reply.addProperty("type",   WsTypes.TEST_RESULT);
                    reply.addProperty("credId", credId);
                    if (cred == null) {
                        reply.addProperty("error", "Credential not found");
                    } else {
                        String result = watcherService.testCredential(cred);
                        if (result != null) reply.addProperty("error", result);
                    }
                    conn.send(reply.toString());
                }

                default -> conn.send(errorReply("Unknown command: " + cmd));
            }

        } catch (Exception e) {
            LOG.warning("Failed to handle message: " + e.getMessage());
            conn.send(errorReply(e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String okReply() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        return r.toString();
    }

    private String errorReply(String msg) {
        JsonObject r = new JsonObject();
        r.addProperty("ok",    false);
        r.addProperty("error", msg);
        return r.toString();
    }

    private static CredentialMessage toCredentialMessage(CredentialStore.Credential c) {
        CredentialMessage msg = new CredentialMessage();
        msg.setId(c.getId());
        msg.setHost(c.getHost());
        msg.setPort(c.getPort());
        msg.setUsername(c.getUsername());
        msg.setPassword(c.getPassword());
        msg.setProtocol(c.getProtocol());
        msg.setLastUsed(c.getLastUsed());
        msg.setUsedByJobIds(new ArrayList<>(c.getUsedByJobIds()));
        return msg;
    }
}