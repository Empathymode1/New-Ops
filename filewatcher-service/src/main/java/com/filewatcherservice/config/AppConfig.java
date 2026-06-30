package com.filewatcherservice.config;

/**
 * Application-level configuration loaded from services.json.
 *
 * These are settings that belong to the runtime environment, not to
 * individual jobs (job config lives in SQLite). Fields map 1-to-1 with
 * JSON keys; Gson populates them by name during deserialization.
 *
 * Defaults are set on the fields so that a missing or partial services.json
 * still produces a fully usable config — ConfigLoader never returns null
 * and never throws for missing keys.
 */
public class AppConfig {

    // ── WebSocket ─────────────────────────────────────────────────────────────
    /** Port the WebSocket server binds to. UI must match. */
    public int websocketPort = 9876;

    /** Interface the WebSocket server binds to. */
    public String websocketHost = "localhost";

    // ── Polling ───────────────────────────────────────────────────────────────
    /**
     * Fallback polling interval in seconds, used when a job does not specify
     * its own intervalSeconds (i.e. the field is 0 or absent).
     */
    public int defaultIntervalSeconds = 120;

    /**
     * When false, the polling fallback is disabled globally — jobs that cannot
     * use NIO or remote exec will be marked ERROR instead of falling back to
     * polling. Useful in environments where polling is too expensive.
     */
    public boolean pollingFallbackEnabled = true;

    // ── Logging ───────────────────────────────────────────────────────────────
    /** java.util.logging level name: SEVERE, WARNING, INFO, FINE, FINEST. */
    public String logLevel = "INFO";

    /** Maximum size of each rotating log file in megabytes. */
    public int logMaxFileSizeMb = 10;

    /** Number of rotating log files to keep. */
    public int logMaxFileCount = 5;

    // ── Scheduler ─────────────────────────────────────────────────────────────
    /** Core thread pool size for the shared ScheduledExecutorService. */
    public int schedulerThreadPoolSize = 4;

    /** How often (seconds) the heartbeat task fires. 0 = disabled. */
    public int heartbeatIntervalSeconds = 60;

    // ── SSH / SFTP ────────────────────────────────────────────────────────────
    /** Timeout in milliseconds for opening an SSH session. */
    public int sshConnectTimeoutMs = 10_000;

    /** Timeout in milliseconds for opening an SFTP channel on an existing session. */
    public int sftpChannelTimeoutMs = 5_000;

    // ── Transfer ──────────────────────────────────────────────────────────────
    /**
     * Maximum number of concurrent file transfers across all jobs.
     * Maps to the thread pool size of FileWatcherService's watchPool.
     * 0 = unbounded (original behaviour, newCachedThreadPool).
     */
    public int maxConcurrentTransfers = 0;

    @Override
    public String toString() {
        return "AppConfig{"
                + "websocketPort=" + websocketPort
                + ", websocketHost='" + websocketHost + '\''
                + ", defaultIntervalSeconds=" + defaultIntervalSeconds
                + ", pollingFallbackEnabled=" + pollingFallbackEnabled
                + ", logLevel='" + logLevel + '\''
                + ", logMaxFileSizeMb=" + logMaxFileSizeMb
                + ", logMaxFileCount=" + logMaxFileCount
                + ", schedulerThreadPoolSize=" + schedulerThreadPoolSize
                + ", heartbeatIntervalSeconds=" + heartbeatIntervalSeconds
                + ", sshConnectTimeoutMs=" + sshConnectTimeoutMs
                + ", sftpChannelTimeoutMs=" + sftpChannelTimeoutMs
                + ", maxConcurrentTransfers=" + maxConcurrentTransfers
                + '}';
    }
}
