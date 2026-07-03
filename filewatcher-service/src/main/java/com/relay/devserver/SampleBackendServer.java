package com.relay.devserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference implementation of the "Relay ↔ Monitoring Service" WebSocket
 * contract (see docs/relay-monitoring-ws-contract.md). Plain JSON over a
 * single WebSocket connection, language-agnostic wire format, driven here
 * by the Java-WebSocket library.
 *
 * Speaks:
 *   Server -> Client : SNAPSHOT, EVENT
 *   Client -> Server : SNAPSHOT_REQUEST, COMMAND
 *
 * Run it standalone (`java -cp ... com.relay.devserver.SampleBackendServer`,
 * or `mvn -pl filewatcher-service exec:java
 *     -Dexec.mainClass=com.relay.devserver.SampleBackendServer`) and point
 * the JavaFX app at it (default ws://localhost:8765/ws, or set
 * RELAY_BACKEND_URL) to exercise the full real-time path end to end before
 * a production backend exists. Emits the same demo cadence as the UI's
 * MockServiceClient so the two are interchangeable in front of the app.
 */
public class SampleBackendServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(SampleBackendServer.class.getName());
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Gson gson = new GsonBuilder().create();
    // Touched from WS I/O threads (onMessage) and the scheduler thread (emitRandomEvent /
    // emitHeartbeats), so wrap it rather than use a plain LinkedHashMap.
    private final Map<String, JobRecord> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sample-backend-events");
        t.setDaemon(true);
        return t;
    });

    public SampleBackendServer(int port) {
        super(new InetSocketAddress(port));
        seedDemoJobs();
    }

    public static void main(String[] args) {
        int port = 8765;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                // fall back to default
            }
        }
        SampleBackendServer server = new SampleBackendServer(port);
        server.setReuseAddr(true);
        server.start();
        LOG.info(() -> "SampleBackendServer listening on ws://localhost:" + server.getPort() + "/ws");
    }

    // ------------------------------------------------------------------
    // Demo data (mirrors the JavaFX app's MockServiceClient / AppState
    // seed data, so behavior matches whichever client you're pointed at)
    // ------------------------------------------------------------------

    private void seedDemoJobs() {
        addJob("job-1", "PAX-Manifest-Sync", "SFTP Watch", "/export/pax/manifests", "sftp://dcs01/inbound",
                "15s", "dcs-svc-account", "RUNNING", 342, "12s ago", "Polling source directory");
        addJob("job-2", "Baggage-EDI-Feed", "SFTP Watch", "/export/bhs/edi", "sftp://bags-hub/in",
                "30s", "bhs-svc-account", "RUNNING", 118, "44s ago", "Polling source directory");
        addJob("job-3", "Cargo-Docs-Relay", "FTP Watch", "/cargo/outbound", "ftp://cargo-edge/docs",
                "60s", "cargo-ftp-user", "RESTARTING", 76, "3m ago", "Reconnecting to host…");
        addJob("job-4", "Crew-Roster-Push", "SFTP Watch", "/ops/crew/rosters", "sftp://crewnet/rosters",
                "5m", "crewnet-svc", "RUNNING", 29, "6m ago", "Polling source directory");
        addJob("job-5", "Weather-METAR-Pull", "HTTP Poll", "https://wx.feed/metar", "/data/wx/metar",
                "2m", "none", "STARTING", 0, "—", "Establishing connection…");
        addJob("job-6", "Fuel-Ticket-Archive", "Local Watch", "/fuel/tickets/new", "/fuel/tickets/archive",
                "10s", "none", "STOPPED", 0, "2h ago", "Idle");
        addJob("job-7", "Legacy-NOTAM-Sync", "SFTP Watch", "/notam/legacy", "sftp://notam-old/in",
                "5m", "notam-legacy", "DISABLED", 0, "—", "Disabled by operator");
    }

    private void addJob(String id, String name, String type, String sourcePath, String destPath,
                         String pollingInterval, String credential, String status, int filesToday,
                         String lastTransfer, String currentActivity) {
        jobs.put(id, new JobRecord(id, name, type, sourcePath, destPath, pollingInterval, credential,
                status, filesToday, lastTransfer, currentActivity));
    }

    // ------------------------------------------------------------------
    // WebSocketServer lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onStart() {
        // Real-time event cadence — matches MockServiceClient's 4.2s tick.
        scheduler.scheduleAtFixedRate(this::emitRandomEvent, 4, 4, TimeUnit.SECONDS);
        // Slow heartbeat sweep so "Last Heartbeat" keeps moving for running jobs.
        scheduler.scheduleAtFixedRate(this::emitHeartbeats, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info(() -> "Client connected: " + conn.getRemoteSocketAddress());
        sendSnapshot(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info(() -> "Client disconnected: " + conn.getRemoteSocketAddress() + " (" + reason + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Malformed message, ignoring: " + message, e);
            return;
        }
        String type = obj.has("type") ? obj.get("type").getAsString() : null;
        if (type == null) return;

        switch (type) {
            case "SNAPSHOT_REQUEST" -> sendSnapshot(conn);
            case "COMMAND" -> handleCommand(obj);
            default -> LOG.warning(() -> "Unknown message type from client: " + type);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.log(Level.WARNING, "WebSocket error", ex);
    }

    // ------------------------------------------------------------------
    // Outbound messages
    // ------------------------------------------------------------------

    private void sendSnapshot(WebSocket conn) {
        conn.send(snapshotPayload());
    }

    private void broadcastSnapshot() {
        String payload = snapshotPayload();
        for (WebSocket c : getConnections()) {
            c.send(payload);
        }
    }

    private String snapshotPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "SNAPSHOT");
        List<JobRecord> snapshot;
        synchronized (jobs) {
            snapshot = List.copyOf(jobs.values());
        }
        root.add("jobs", gson.toJsonTree(snapshot));
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
        String payload = gson.toJson(root);
        for (WebSocket c : getConnections()) {
            c.send(payload);
        }
    }

    // ------------------------------------------------------------------
    // Command handling (Start / Stop / Restart / Delete / Test Connection)
    // ------------------------------------------------------------------

    private void handleCommand(JsonObject obj) {
        String jobId = obj.has("jobId") ? obj.get("jobId").getAsString() : null;
        String command = obj.has("command") ? obj.get("command").getAsString() : null;
        if (jobId == null || command == null) return;

        JobRecord job = jobs.get(jobId);
        if (job == null) return;

        switch (command) {
            case "START" -> {
                job.status = "RUNNING";
                job.currentActivity = "Polling source directory";
                broadcastEvent("SERVICE_STATUS_CHANGED", job.id, job.name, null, null, "RUNNING");
            }
            case "STOP" -> {
                job.status = "STOPPED";
                job.currentActivity = "Idle";
                broadcastEvent("SERVICE_STATUS_CHANGED", job.id, job.name, null, null, "STOPPED");
            }
            case "RESTART" -> {
                job.status = "RESTARTING";
                job.currentActivity = "Reconnecting to host…";
                broadcastEvent("SERVICE_STATUS_CHANGED", job.id, job.name, null, null, "RESTARTING");
                scheduler.schedule(() -> {
                    job.status = "RUNNING";
                    job.currentActivity = "Polling source directory";
                    broadcastEvent("SERVICE_STATUS_CHANGED", job.id, job.name, null, null, "RUNNING");
                }, 2, TimeUnit.SECONDS);
            }
            case "DELETE" -> {
                jobs.remove(jobId);
                broadcastSnapshot();
            }
            case "TEST_CONNECTION" -> {
                boolean ok = random.nextDouble() > 0.2;
                if (ok) {
                    broadcastEvent("TRANSFER_COMPLETED", job.id, job.name, "connection_test.tmp", null, null);
                } else {
                    broadcastEvent("TRANSFER_FAILED", job.id, job.name, "connection_test.tmp",
                            "Connection test failed: timed out", null);
                }
            }
            default -> LOG.warning(() -> "Unknown command: " + command);
        }
    }

    // ------------------------------------------------------------------
    // Background demo event generation
    // ------------------------------------------------------------------

    private void emitRandomEvent() {
        if (getConnections().isEmpty()) return;
        List<JobRecord> runningJobs;
        synchronized (jobs) {
            runningJobs = jobs.values().stream()
                    .filter(j -> "RUNNING".equals(j.status))
                    .toList();
        }
        if (runningJobs.isEmpty()) return;

        JobRecord job = runningJobs.get(random.nextInt(runningJobs.size()));
        String filename = "manifest_" + (1000 + random.nextInt(9000)) + ".xml";

        if (random.nextDouble() < 0.8) {
            job.filesToday++;
            job.lastTransfer = "just now";
            broadcastEvent("TRANSFER_COMPLETED", job.id, job.name, filename, "Completed successfully", null);
        } else {
            broadcastEvent("TRANSFER_FAILED", job.id, job.name, filename, "Connection reset by peer", null);
        }
    }

    private void emitHeartbeats() {
        if (getConnections().isEmpty()) return;
        List<JobRecord> runningJobs;
        synchronized (jobs) {
            runningJobs = jobs.values().stream()
                    .filter(j -> "RUNNING".equals(j.status))
                    .toList();
        }
        for (JobRecord job : runningJobs) {
            job.lastTransfer = "just now";
            broadcastEvent("HEARTBEAT", job.id, job.name, null, null, null);
        }
    }

    // ------------------------------------------------------------------
    // Wire model for a job row — field names match the contract exactly
    // ------------------------------------------------------------------

    private static final class JobRecord {
        final String id;
        String name;
        String type;
        String sourcePath;
        String destPath;
        String pollingInterval;
        String credential;
        String status;
        int filesToday;
        String lastTransfer;
        String currentActivity;

        JobRecord(String id, String name, String type, String sourcePath, String destPath,
                  String pollingInterval, String credential, String status, int filesToday,
                  String lastTransfer, String currentActivity) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.sourcePath = sourcePath;
            this.destPath = destPath;
            this.pollingInterval = pollingInterval;
            this.credential = credential;
            this.status = status;
            this.filesToday = filesToday;
            this.lastTransfer = lastTransfer;
            this.currentActivity = currentActivity;
        }
    }
}
