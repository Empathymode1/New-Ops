package com.filewatcherservice.database;

import com.filewatchercommon.model.TransferEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository for the {@code transfer_logs} table.
 *
 * This is new persistence the old XML-based stores didn't have — JobStore
 * only kept aggregate counters (filesTransferred/bytesTransferred) on the
 * job itself, with no per-event history. This repo gives the UI's "Logs"
 * tab (section 4: search, filter, export, view transfer details) something
 * to query against.
 */
public class TransferRepository {

    private static final Logger LOG = Logger.getLogger(TransferRepository.class.getName());

    private final DatabaseService db;

    public TransferRepository(DatabaseService db) {
        this.db = db;
    }

    /** Record of a single transfer/event row, as read back from the DB. */
    public record LogEntry(
            long id, String jobId, String jobName, String eventType,
            String message, String filename, long sizeBytes, LocalDateTime occurredAt
    ) {}

    // ── Write ─────────────────────────────────────────────────────────────

    public synchronized void save(TransferEvent event) {
        String sql = """
            INSERT INTO transfer_logs (job_id, job_name, event_type, message, filename, size_bytes, occurred_at)
            VALUES (?,?,?,?,?,?,?)
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, event.getJobId());
            ps.setString(2, event.getJobName());
            ps.setString(3, event.getType().name());
            ps.setString(4, event.getMessage());
            ps.setString(5, event.getFileName());
            ps.setLong(6, event.getFileSize());
            ps.setString(7, event.getTimestamp().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("TransferRepository: save failed — " + e.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────

    /** All logs for a given job, most recent first. */
    public synchronized List<LogEntry> findByJobId(String jobId, int limit) {
        String sql = "SELECT * FROM transfer_logs WHERE job_id = ? ORDER BY occurred_at DESC LIMIT ?";
        return query(sql, ps -> {
            ps.setString(1, jobId);
            ps.setInt(2, limit);
        });
    }

    /** Search across job name, filename, and message text. */
    public synchronized List<LogEntry> search(String text, int limit) {
        String sql = """
            SELECT * FROM transfer_logs
            WHERE job_name LIKE ? OR filename LIKE ? OR message LIKE ?
            ORDER BY occurred_at DESC LIMIT ?
        """;
        String like = "%" + text + "%";
        return query(sql, ps -> {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setInt(4, limit);
        });
    }

    /** Filter by event type (e.g. ERROR, TRANSFERRED), most recent first. */
    public synchronized List<LogEntry> findByEventType(String eventType, int limit) {
        String sql = "SELECT * FROM transfer_logs WHERE event_type = ? ORDER BY occurred_at DESC LIMIT ?";
        return query(sql, ps -> {
            ps.setString(1, eventType);
            ps.setInt(2, limit);
        });
    }

    /** All logs, most recent first — used for export. */
    public synchronized List<LogEntry> findAll(int limit) {
        String sql = "SELECT * FROM transfer_logs ORDER BY occurred_at DESC LIMIT ?";
        return query(sql, ps -> ps.setInt(1, limit));
    }

    /**
     * Combined filter query backing the contract's LOGS_REQUEST (§2.9) —
     * every parameter is optional (null = don't filter on that dimension)
     * and whichever are present get ANDed together, unlike the fixed
     * single-purpose queries above (findByJobId/search/findByEventType),
     * which each only filter on one thing.
     */
    public synchronized List<LogEntry> queryFiltered(String jobId, String eventType, String searchText,
                                                       LocalDateTime since, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transfer_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (jobId != null && !jobId.isBlank()) {
            sql.append(" AND job_id = ?");
            params.add(jobId);
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
        if (searchText != null && !searchText.isBlank()) {
            sql.append(" AND (job_name LIKE ? OR filename LIKE ? OR message LIKE ?)");
            String like = "%" + searchText + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (since != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(since.toString());
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(limit);

        return query(sql.toString(), ps -> {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
        });
    }

    private List<LogEntry> query(String sql, SqlBinder binder) {
        List<LogEntry> results = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.warning("TransferRepository: query failed — " + e.getMessage());
        }
        return results;
    }

    private LogEntry mapRow(ResultSet rs) throws SQLException {
        return new LogEntry(
                rs.getLong("id"),
                rs.getString("job_id"),
                rs.getString("job_name"),
                rs.getString("event_type"),
                rs.getString("message"),
                rs.getString("filename"),
                rs.getLong("size_bytes"),
                LocalDateTime.parse(rs.getString("occurred_at"))
        );
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
