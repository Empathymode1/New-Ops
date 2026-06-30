package com.filewatcherservice.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Repository for the {@code settings} table — simple key/value storage
 * for application configuration the UI's Settings tab can read/write
 * (section 4: "Settings — supports application configuration and preferences").
 */
public class SettingsRepository {

    private static final Logger LOG = Logger.getLogger(SettingsRepository.class.getName());

    private final DatabaseService db;

    public SettingsRepository(DatabaseService db) {
        this.db = db;
    }

    public synchronized void set(String key, String value) {
        String sql = """
            INSERT INTO settings (key, value) VALUES (?,?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("SettingsRepository: set failed for key=" + key + " — " + e.getMessage());
        }
    }

    public synchronized String get(String key, String defaultValue) {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            LOG.warning("SettingsRepository: get failed for key=" + key + " — " + e.getMessage());
        }
        return defaultValue;
    }

    public synchronized void delete(String key) {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("SettingsRepository: delete failed for key=" + key + " — " + e.getMessage());
        }
    }
}