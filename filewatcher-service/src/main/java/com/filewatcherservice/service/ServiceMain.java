package com.filewatcherservice.service;

import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.service.NotificationService;
import com.filewatcherservice.config.AppConfig;
import com.filewatcherservice.config.ConfigLoader;
import com.filewatcherservice.database.DatabaseService;
import com.filewatcherservice.scheduler.Scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Entry point for --service mode.
 *
 * Startup sequence follows architecture doc §15:
 *   Load Configuration → Initialize SQLite → Create Tables → Load Services
 *   → Start Scheduler → Start File Watchers → Start Socket Services
 *   → Start WebSocket Server → UI Connects → Dashboard Ready
 *
 * Shutdown sequence follows architecture doc §16:
 *   Stop Scheduler → Stop File Watchers → Close Socket Connections
 *   → Flush Pending DB Writes → Close SQLite → Shutdown
 *
 * All previously hardcoded values (port, timeouts, pool sizes, log rotation)
 * are now read from AppConfig, loaded from services.json next to the exe.
 */
public class ServiceMain {

    private static final Logger LOG = Logger.getLogger(ServiceMain.class.getName());

    public static void start() {

        // ── Phase 1: Load Configuration ──────────────────────────────────────
        // Logging is configured from AppConfig, so config must load first.
        // ConfigLoader never throws — missing/malformed file falls back to defaults.
        AppConfig config = ConfigLoader.load();
        configureLogging(config);

        LOG.info("FileWatcher service starting...");
        LOG.info("Active config: " + config);

        // ── Phase 2 & 3: Initialize SQLite + Create Tables ───────────────────
        LOG.info("[Phase 2/3] Initializing SQLite and creating tables...");
        DatabaseService db = new DatabaseService();

        // ── Phase 4: Load Services ────────────────────────────────────────────
        LOG.info("[Phase 4] Loading persisted jobs...");
        JobStore            jobStore            = new JobStore(db);
        NotificationService notificationService = new NotificationService();
        Scheduler           scheduler           = new Scheduler(config.schedulerThreadPoolSize);
        FileWatcherService  watcherService      = new FileWatcherService(db, scheduler, config);
        ServiceManager      serviceManager      = new ServiceManager(watcherService, jobStore);

        watcherService.setNotificationService(notificationService);

        List<WatchJob> savedJobs = jobStore.load();
        LOG.info("[Phase 4] " + savedJobs.size() + " job(s) loaded from database");

        // ── Phase 5: Start Scheduler ──────────────────────────────────────────
        // Scheduler is live after construction. Heartbeat task registered here
        // if configured (heartbeatIntervalSeconds = 0 disables it).
        LOG.info("[Phase 5] Scheduler ready (pool size: " + config.schedulerThreadPoolSize + ")");
        if (config.heartbeatIntervalSeconds > 0) {
            scheduler.scheduleRepeating("heartbeat",
                    () -> LOG.fine("Heartbeat — service alive"),
                    config.heartbeatIntervalSeconds);
            LOG.info("[Phase 5] Heartbeat scheduled every " + config.heartbeatIntervalSeconds + "s");
        }

        // ── Phase 6: Start File Watchers ──────────────────────────────────────
        LOG.info("[Phase 6] Starting file watchers...");
        for (WatchJob job : savedJobs) {
            watcherService.addJob(job);
            serviceManager.register(new WatchJobService(job, watcherService));
            serviceManager.start(job.getId());
            LOG.info("[Phase 6] Restored and started job: " + job.getName());
        }

        // ── Phase 7: Start Socket Services ────────────────────────────────────
        // TODO: start SocketService instances once implemented.
        LOG.info("[Phase 7] Socket services — not yet implemented, skipping");

        // ── Phase 8: Start WebSocket Server ───────────────────────────────────
        LOG.info("[Phase 8] Starting WebSocket server on port " + config.websocketPort + "...");
        ServiceWebSocketServer wsServer = new ServiceWebSocketServer(
                config.websocketHost, config.websocketPort, serviceManager, watcherService, notificationService);
        wsServer.start();

        // ── Phase 9 & 10: UI Connects / Dashboard Ready ───────────────────────
        LOG.info("[Phase 9/10] WebSocket server ready — waiting for UI on ws://"
                + config.websocketHost + ":" + config.websocketPort);
        LOG.info("FileWatcher service started successfully");

        // ── Shutdown hook — follows §16 sequence ──────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("FileWatcher service shutting down...");

            // §16 Step 1: Stop Scheduler
            LOG.info("[Shutdown 1] Stopping scheduler...");
            scheduler.shutdown();

            // §16 Step 2: Stop File Watchers
            LOG.info("[Shutdown 2] Stopping file watchers...");
            serviceManager.stopAll();
            watcherService.shutdown();

            // §16 Step 3: Close Socket Connections
            // TODO: disconnect SocketService instances when implemented
            LOG.info("[Shutdown 3] Socket services — not yet implemented, skipping");

            // §16 Step 4: Flush Pending DB Writes
            LOG.info("[Shutdown 4] Flushing pending DB writes...");
            jobStore.save(watcherService.getJobs());

            // §16 Step 5: Close SQLite
            LOG.info("[Shutdown 5] Closing SQLite...");
            db.close();

            // §16 Step 6: Stop WebSocket Server
            LOG.info("[Shutdown 6] Stopping WebSocket server...");
            try { wsServer.stop(); } catch (Exception ignored) {}

            LOG.info("FileWatcher service stopped");
        }));
    }

    // ── Logging configuration ─────────────────────────────────────────────────

    /**
     * Configure the root logger from AppConfig values.
     * Called after config loads but before any other startup code runs.
     *
     * Values driven by AppConfig:
     *   logLevel         — e.g. INFO, FINE, WARNING
     *   logMaxFileSizeMb — size threshold per rotating file
     *   logMaxFileCount  — number of rotating files to keep
     */
    private static void configureLogging(AppConfig config) {
        try {
            Files.createDirectories(Paths.get("logs"));

            FileHandler fileHandler = new FileHandler(
                    "logs/application.log",
                    (long) config.logMaxFileSizeMb * 1024 * 1024,
                    config.logMaxFileCount,
                    true  // append on restart
            );
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.ALL);

            Level level = parseLevel(config.logLevel);

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(fileHandler);
            rootLogger.setLevel(level);

        } catch (IOException e) {
            System.err.println("Failed to configure file logging: " + e.getMessage());
        }
    }

    private static Level parseLevel(String levelName) {
        try {
            return Level.parse(levelName.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown log level '" + levelName + "' in services.json — defaulting to INFO");
            return Level.INFO;
        }
    }
}