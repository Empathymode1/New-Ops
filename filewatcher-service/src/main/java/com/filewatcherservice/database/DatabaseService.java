package com.filewatcherservice.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Owns the single SQLite connection and schema lifecycle for the service.
 *
 * SQLite is file-based and works fine with a single shared connection as
 * long as writes are serialized — which they are here, since every command
 * coming off the WebSocket server is handled on its own callback thread but
 * repositories synchronize on this connection for writes. For this app's
 * write volume (job edits, transfer log inserts), that's more than enough.
 *
 * DB file: {@code <STORE_DIR>/monitor.db} — same directory the old
 * JobStore/CredentialStore XML files used (D:\Data), per migration decision.
 */
public class DatabaseService {

    private static final Logger LOG = Logger.getLogger(DatabaseService.class.getName());

    // Overridable via -Dfilewatcher.dataDir=... for testing/dev on non-Windows
    // machines; defaults to the same path the old XML stores used in production.
    public static final File STORE_DIR =
            new File(System.getProperty("filewatcher.dataDir", "D:\\Data"));
    private static final File DB_FILE = new File(STORE_DIR, "monitor.db");

    private final Connection connection;

    public DatabaseService() {
        try {
            if (!STORE_DIR.exists() && !STORE_DIR.mkdirs()) {
                throw new IllegalStateException("Could not create data directory: " + STORE_DIR);
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            createTables();
            LOG.info("DatabaseService: connected to " + DB_FILE.getAbsolutePath());
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite database", e);
        }
    }

    /**
     * Test constructor — accepts an explicit JDBC URL so tests can pass
     * "jdbc:sqlite::memory:" without touching the filesystem.
     */
    public DatabaseService(String jdbcUrl) {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            createTables();
            LOG.info("DatabaseService: connected to " + jdbcUrl);
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite database", e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {

            // ── services (watch jobs) ────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS services (
                    id                 TEXT PRIMARY KEY,
                    name               TEXT NOT NULL,
                    status             TEXT,
                    direction          TEXT,
                    transfer_mode      TEXT,
                    protocol           TEXT,
                    source_host        TEXT,
                    source_port        INTEGER,
                    source_user        TEXT,
                    source_password    TEXT,
                    source_path        TEXT,
                    dest_host          TEXT,
                    dest_port          INTEGER,
                    dest_user          TEXT,
                    dest_password      TEXT,
                    dest_path          TEXT,
                    remote_os          TEXT,
                    specific_pattern   TEXT,
                    interval_seconds   INTEGER DEFAULT 30,
                    watch_depth        INTEGER DEFAULT 1,
                    files_transferred  INTEGER DEFAULT 0,
                    bytes_transferred  INTEGER DEFAULT 0,
                    last_error         TEXT,
                    last_transfer      TEXT,
                    created_at         TEXT
                )
            """);

            // ── transfer_logs ─────────────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS transfer_logs (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id             TEXT NOT NULL,
                    job_name           TEXT,
                    event_type         TEXT NOT NULL,
                    message            TEXT,
                    filename           TEXT,
                    size_bytes         INTEGER DEFAULT 0,
                    occurred_at        TEXT NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfer_logs_job_id ON transfer_logs(job_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfer_logs_occurred_at ON transfer_logs(occurred_at)");

            // ── socket_logs (reserved for future SocketService — section 8/19) ──
            st.execute("""
                CREATE TABLE IF NOT EXISTS socket_logs (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    service_id         TEXT NOT NULL,
                    event_type         TEXT NOT NULL,
                    message            TEXT,
                    occurred_at        TEXT NOT NULL
                )
            """);

            // ── credentials ───────────────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS credentials (
                    id                 TEXT PRIMARY KEY,
                    host               TEXT,
                    port               INTEGER,
                    username           TEXT,
                    password           TEXT,
                    protocol           TEXT,
                    last_used          TEXT
                )
            """);

            // ── credential_job_refs (used-by-jobs join table) ───────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS credential_job_refs (
                    credential_id      TEXT NOT NULL,
                    job_id             TEXT NOT NULL,
                    PRIMARY KEY (credential_id, job_id),
                    FOREIGN KEY (credential_id) REFERENCES credentials(id) ON DELETE CASCADE
                )
            """);

            // ── settings ──────────────────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key                TEXT PRIMARY KEY,
                    value              TEXT
                )
            """);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                LOG.info("DatabaseService: connection closed");
            }
        } catch (SQLException e) {
            LOG.warning("DatabaseService: close failed — " + e.getMessage());
        }
    }
}