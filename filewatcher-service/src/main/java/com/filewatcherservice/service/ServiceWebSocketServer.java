package com.filewatcherservice.service;

import com.filewatchercommon.model.CredentialMessage;
import com.filewatchercommon.model.NotificationMessage;
import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.service.NotificationService;
import com.filewatchercommon.ws.WsCommands;
import com.filewatchercommon.ws.WsTypes;
import com.google.gson.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
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
 *   TEST_CREDENTIAL
 *
 * Pushes events to UI (type field):
 *   INIT        — full job list on connect
 *   JOB_STATE   — job status changed
 *   EVENT       — file transfer event
 *   NOTIFICATION — error notification
 */
public class ServiceWebSocketServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(ServiceWebSocketServer.class.getName());

    private final ServiceManager      serviceManager;
    private final FileWatcherService  watcherService;
    private final NotificationService notificationService;
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
                                  NotificationService notificationService) {
        super(new InetSocketAddress(host, port));
        this.port                = port;
        this.serviceManager      = serviceManager;
        this.watcherService      = watcherService;
        this.notificationService = notificationService;
        setReuseAddr(true);
        wireListeners();
    }

    /** Convenience overload — binds to localhost, kept for callers that don't pass a host explicitly. */
    public ServiceWebSocketServer(int port,
                                  ServiceManager serviceManager,
                                  FileWatcherService watcherService,
                                  NotificationService notificationService) {
        this("localhost", port, serviceManager, watcherService, notificationService);
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