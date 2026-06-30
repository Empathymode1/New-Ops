package com.filewatcherservice.database;

import com.filewatcherservice.service.CredentialStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Repository for the {@code credentials} and {@code credential_job_refs} tables.
 * Replaces CredentialStore's XML persistence.
 *
 * Not listed by name in the architecture doc's repository examples (section 12
 * only calls out Service/Transfer/Socket/Settings), but credentials clearly need
 * their own persistence and table per section 10, so this fills that gap.
 */
public class CredentialRepository {

    private static final Logger LOG = Logger.getLogger(CredentialRepository.class.getName());

    private final DatabaseService db;

    public CredentialRepository(DatabaseService db) {
        this.db = db;
    }

    // ── Write ─────────────────────────────────────────────────────────────

    public synchronized void save(CredentialStore.Credential cred) {
        String sql = """
            INSERT INTO credentials (id, host, port, username, password, protocol, last_used)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                host=excluded.host, port=excluded.port, username=excluded.username,
                password=excluded.password, protocol=excluded.protocol, last_used=excluded.last_used
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, cred.getId());
            ps.setString(2, cred.getHost());
            ps.setInt(3, cred.getPort());
            ps.setString(4, cred.getUsername());
            ps.setString(5, cred.getPassword());
            ps.setString(6, cred.getProtocol());
            ps.setString(7, cred.getLastUsed() != null ? cred.getLastUsed().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: save failed for " + cred.getId() + " — " + e.getMessage());
            return;
        }
        replaceJobRefs(cred.getId(), cred.getUsedByJobIds());
    }

    public synchronized void delete(String id) {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM credentials WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: delete failed for " + id + " — " + e.getMessage());
        }
    }

    public synchronized void touchLastUsed(String credId, String jobId) {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("UPDATE credentials SET last_used = ? WHERE id = ?")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, credId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: touchLastUsed failed — " + e.getMessage());
            return;
        }
        addJobRef(credId, jobId);
    }

    /** Removes a job reference from every credential (call on job delete). */
    public synchronized void removeJobRef(String jobId) {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM credential_job_refs WHERE job_id = ?")) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: removeJobRef failed — " + e.getMessage());
        }
    }

    private void addJobRef(String credId, String jobId) {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "INSERT OR IGNORE INTO credential_job_refs (credential_id, job_id) VALUES (?,?)")) {
            ps.setString(1, credId);
            ps.setString(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: addJobRef failed — " + e.getMessage());
        }
    }

    private void replaceJobRefs(String credId, List<String> jobIds) {
        try (PreparedStatement del = db.getConnection().prepareStatement(
                "DELETE FROM credential_job_refs WHERE credential_id = ?")) {
            del.setString(1, credId);
            del.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: replaceJobRefs delete failed — " + e.getMessage());
            return;
        }
        if (jobIds == null) return;
        for (String jobId : jobIds) addJobRef(credId, jobId);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    public synchronized List<CredentialStore.Credential> findAll() {
        Map<String, CredentialStore.Credential> byId = new LinkedHashMap<>();
        String sql = "SELECT * FROM credentials";
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                CredentialStore.Credential c = mapRow(rs);
                byId.put(c.getId(), c);
            }
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: findAll failed — " + e.getMessage());
            return new ArrayList<>();
        }
        attachJobRefs(byId);
        return new ArrayList<>(byId.values());
    }

    public synchronized CredentialStore.Credential findById(String id) {
        String sql = "SELECT * FROM credentials WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                CredentialStore.Credential c = mapRow(rs);
                Map<String, CredentialStore.Credential> single = new LinkedHashMap<>();
                single.put(c.getId(), c);
                attachJobRefs(single);
                return c;
            }
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: findById failed for " + id + " — " + e.getMessage());
            return null;
        }
    }

    private void attachJobRefs(Map<String, CredentialStore.Credential> byId) {
        if (byId.isEmpty()) return;
        String placeholders = String.join(",", byId.keySet().stream().map(k -> "?").toList());
        String sql = "SELECT credential_id, job_id FROM credential_job_refs WHERE credential_id IN (" + placeholders + ")";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            int i = 1;
            for (String id : byId.keySet()) ps.setString(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String credId = rs.getString("credential_id");
                    String jobId = rs.getString("job_id");
                    CredentialStore.Credential c = byId.get(credId);
                    if (c != null) c.getUsedByJobIds().add(jobId);
                }
            }
        } catch (SQLException e) {
            LOG.warning("CredentialRepository: attachJobRefs failed — " + e.getMessage());
        }
    }

    private CredentialStore.Credential mapRow(ResultSet rs) throws SQLException {
        CredentialStore.Credential c = new CredentialStore.Credential();
        c.setId(rs.getString("id"));
        c.setHost(rs.getString("host"));
        c.setPort(rs.getInt("port"));
        c.setUsername(rs.getString("username"));
        c.setPassword(rs.getString("password"));
        c.setProtocol(rs.getString("protocol"));
        String lastUsed = rs.getString("last_used");
        if (lastUsed != null && !lastUsed.isBlank()) c.setLastUsed(LocalDateTime.parse(lastUsed));
        return c;
    }
}
