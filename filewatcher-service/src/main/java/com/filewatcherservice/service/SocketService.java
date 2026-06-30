package com.filewatcherservice.service;

import com.filewatcherservice.database.DatabaseService;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Stub implementation of a socket-based monitoring service, per architecture
 * doc section 8:
 *
 *   Each socket service manages: Socket Connection, Reconnection Logic,
 *   Heartbeat, Incoming Messages, Connection State.
 *
 * This is intentionally minimal — it exists to prove ServiceManager can host
 * a second MonitorService type without any change to ServiceManager itself,
 * per section 19's "Future Enhancements" / "Plugin-based Service Architecture".
 * The real implementation (actual socket connect/reconnect/heartbeat logic)
 * is future work; start()/stop() here just flip a connection-state flag and
 * log to socket_logs so the shape is in place.
 */
public class SocketService implements MonitorService {

    private static final Logger LOG = Logger.getLogger(SocketService.class.getName());

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private final String id;
    private final String name;
    private final String host;
    private final int port;
    private final DatabaseService db;

    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    public SocketService(String id, String name, String host, int port, DatabaseService db) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.db = db;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getName() { return name; }

    @Override
    public String getType() { return "Socket"; }

    @Override
    public void start() {
        // Stub: real implementation would open the socket, start a
        // reconnect-on-failure loop, and a heartbeat ping on a schedule
        // (see Scheduler — section 6: "Socket Health Check → Every 30 seconds").
        state.set(ConnectionState.CONNECTING);
        logEvent("CONNECTING", "Socket service starting: " + host + ":" + port);

        // Placeholder "connect" — flips straight to CONNECTED since there's
        // no real socket logic yet.
        state.set(ConnectionState.CONNECTED);
        logEvent("CONNECTED", "Socket service connected: " + host + ":" + port);
    }

    @Override
    public void stop() {
        state.set(ConnectionState.DISCONNECTED);
        logEvent("DISCONNECTED", "Socket service stopped: " + host + ":" + port);
    }

    @Override
    public String getStatus() {
        return state.get().name();
    }

    private void logEvent(String eventType, String message) {
        String sql = """
            INSERT INTO socket_logs (service_id, event_type, message, occurred_at)
            VALUES (?,?,?,?)
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, eventType);
            ps.setString(3, message);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("SocketService: failed to log event — " + e.getMessage());
        }
    }
}