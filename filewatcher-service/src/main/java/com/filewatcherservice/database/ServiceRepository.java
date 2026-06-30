package com.filewatcherservice.database;

import com.filewatchercommon.model.WatchJob;
import com.filewatchercommon.util.OsType;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Repository for the {@code services} table (watch jobs).
 * Replaces the old XML-based JobStore.
 *
 * WatchJob.id is final and assigned in its constructor (random UUID), so
 * rows are reconstructed by building a fresh WatchJob() and then overwriting
 * its id via reflection — same approach the old JobStore used.
 */
public class ServiceRepository {

    private static final Logger LOG = Logger.getLogger(ServiceRepository.class.getName());

    private final DatabaseService db;

    public ServiceRepository(DatabaseService db) {
        this.db = db;
    }

    // ── Write ─────────────────────────────────────────────────────────────

    /** Inserts or updates (by id) a single job. */
    public synchronized void save(WatchJob job) {
        String sql = """
            INSERT INTO services (
                id, name, status, direction, transfer_mode, protocol,
                source_host, source_port, source_user, source_password, source_path,
                dest_host, dest_port, dest_user, dest_password, dest_path,
                remote_os, specific_pattern, interval_seconds, watch_depth,
                files_transferred, bytes_transferred, last_error, last_transfer, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                name=excluded.name, status=excluded.status, direction=excluded.direction,
                transfer_mode=excluded.transfer_mode, protocol=excluded.protocol,
                source_host=excluded.source_host, source_port=excluded.source_port,
                source_user=excluded.source_user, source_password=excluded.source_password,
                source_path=excluded.source_path,
                dest_host=excluded.dest_host, dest_port=excluded.dest_port,
                dest_user=excluded.dest_user, dest_password=excluded.dest_password,
                dest_path=excluded.dest_path,
                remote_os=excluded.remote_os, specific_pattern=excluded.specific_pattern,
                interval_seconds=excluded.interval_seconds, watch_depth=excluded.watch_depth,
                files_transferred=excluded.files_transferred,
                bytes_transferred=excluded.bytes_transferred,
                last_error=excluded.last_error, last_transfer=excluded.last_transfer
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, job.getId());
            ps.setString(i++, job.getName());
            ps.setString(i++, enumName(job.getStatus()));
            ps.setString(i++, enumName(job.getDirection()));
            ps.setString(i++, enumName(job.getTransferMode()));
            ps.setString(i++, enumName(job.getProtocol()));
            ps.setString(i++, job.getSourceHost());
            ps.setInt(i++, job.getSourcePort());
            ps.setString(i++, job.getSourceUser());
            ps.setString(i++, job.getSourcePassword());
            ps.setString(i++, job.getSourcePath());
            ps.setString(i++, job.getDestHost());
            ps.setInt(i++, job.getDestPort());
            ps.setString(i++, job.getDestUser());
            ps.setString(i++, job.getDestPassword());
            ps.setString(i++, job.getDestPath());
            ps.setString(i++, job.getRemoteOs() != null ? job.getRemoteOs().name() : null);
            ps.setString(i++, job.getSpecificPattern());
            ps.setInt(i++, job.getIntervalSeconds());
            ps.setInt(i++, job.getWatchDepth());
            ps.setLong(i++, job.getFilesTransferred());
            ps.setLong(i++, job.getBytesTransferred());
            ps.setString(i++, job.getLastError());
            ps.setString(i++, job.getLastTransfer() != null ? job.getLastTransfer().toString() : null);
            ps.setString(i++, job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("ServiceRepository: save failed for job " + job.getId() + " — " + e.getMessage());
        }
    }

    /** Bulk save — used at shutdown or for batch sync; same semantics as old JobStore.save(Collection). */
    public synchronized void saveAll(java.util.Collection<WatchJob> jobs) {
        for (WatchJob j : jobs) save(j);
    }

    public synchronized void delete(String jobId) {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM services WHERE id = ?")) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("ServiceRepository: delete failed for job " + jobId + " — " + e.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────

    public synchronized List<WatchJob> findAll() {
        List<WatchJob> jobs = new ArrayList<>();
        String sql = "SELECT * FROM services";
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) jobs.add(mapRow(rs));
        } catch (SQLException e) {
            LOG.warning("ServiceRepository: findAll failed — " + e.getMessage());
        }
        return jobs;
    }

    public synchronized WatchJob findById(String id) {
        String sql = "SELECT * FROM services WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.warning("ServiceRepository: findById failed for " + id + " — " + e.getMessage());
        }
        return null;
    }

    // ── Row mapping ───────────────────────────────────────────────────────

    private WatchJob mapRow(ResultSet rs) throws SQLException {
        WatchJob j = new WatchJob();
        setId(j, rs.getString("id"));

        j.setName(rs.getString("name"));

        String status = rs.getString("status");
        if (status != null && !status.isBlank()) j.setStatus(WatchJob.Status.valueOf(status));

        String direction = rs.getString("direction");
        if (direction != null && !direction.isBlank()) j.setDirection(WatchJob.Direction.valueOf(direction));

        String mode = rs.getString("transfer_mode");
        if (mode != null && !mode.isBlank()) j.setTransferMode(WatchJob.TransferMode.valueOf(mode));

        String protocol = rs.getString("protocol");
        if (protocol != null && !protocol.isBlank()) j.setProtocol(WatchJob.Protocol.valueOf(protocol));

        j.setSourceHost(rs.getString("source_host"));
        j.setSourcePort(rs.getInt("source_port"));
        j.setSourceUser(rs.getString("source_user"));
        j.setSourcePassword(rs.getString("source_password"));
        j.setSourcePath(rs.getString("source_path"));

        j.setDestHost(rs.getString("dest_host"));
        j.setDestPort(rs.getInt("dest_port"));
        j.setDestUser(rs.getString("dest_user"));
        j.setDestPassword(rs.getString("dest_password"));
        j.setDestPath(rs.getString("dest_path"));

        String remoteOs = rs.getString("remote_os");
        if (remoteOs != null && !remoteOs.isBlank()) j.setRemoteOs(OsType.valueOf(remoteOs));

        j.setSpecificPattern(rs.getString("specific_pattern"));
        j.setIntervalSeconds(rs.getInt("interval_seconds"));
        j.setWatchDepth(rs.getInt("watch_depth"));
        j.setFilesTransferred(rs.getLong("files_transferred"));
        j.setBytesTransferred(rs.getLong("bytes_transferred"));
        j.setLastError(rs.getString("last_error"));

        String lastTransfer = rs.getString("last_transfer");
        if (lastTransfer != null && !lastTransfer.isBlank())
            j.setLastTransfer(LocalDateTime.parse(lastTransfer));

        String createdAt = rs.getString("created_at");
        if (createdAt != null && !createdAt.isBlank())
            setCreatedAt(j, LocalDateTime.parse(createdAt));

        return j;
    }

    // ── Reflection helpers (id and createdAt are read-only after construction) ──

    private static void setId(WatchJob job, String id) {
        if (id == null || id.isBlank()) return;
        try {
            Field f = WatchJob.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, id);
        } catch (Exception e) {
            LOG.warning("ServiceRepository: could not restore job id — " + e.getMessage());
        }
    }

    private static void setCreatedAt(WatchJob job, LocalDateTime createdAt) {
        try {
            Field f = WatchJob.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(job, createdAt);
        } catch (Exception e) {
            LOG.warning("ServiceRepository: could not restore createdAt — " + e.getMessage());
        }
    }

    private static String enumName(Enum<?> e) {
        return e != null ? e.name() : null;
    }
}
