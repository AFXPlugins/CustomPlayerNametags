package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.storage.StorageFiles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists which players have used {@code /nametag toggle} to hide other
 * players' nametags from themselves, to a SQLite database at
 * {@code storage/nametag-toggles.db} in the plugin's data folder, so the
 * setting survives server restarts.
 *
 * <p>A UUID present in the {@code hidden} table means that player currently
 * has other players' nametags hidden from their own view. Absence means the
 * default (visible).
 *
 */
public final class NametagVisibilityStore {

    private static final String DB_FILE_NAME = "nametag-toggles.db";

    private final CustomPlayerNametags plugin;
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private Connection connection;

    public NametagVisibilityStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re-)opens {@code storage/nametag-toggles.db}, creating the table if
     * needed, then loads every hidden UUID into memory, replacing whatever
     * was previously held.
     */
    public void load() {
        hidden.clear();
        closeQuietly();
        try {
            connection = StorageFiles.connect(plugin, DB_FILE_NAME);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS hidden (uuid TEXT PRIMARY KEY)");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT uuid FROM hidden")) {
                while (rs.next()) {
                    addIfValidUuid(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load storage/" + DB_FILE_NAME + ": " + e.getMessage());
        }
    }

    private void addIfValidUuid(String rawUuid) {
        try {
            hidden.add(UUID.fromString(rawUuid));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipping invalid UUID in " + DB_FILE_NAME + ": " + rawUuid);
        }
    }

    /** Whether {@code uuid} currently has other players' nametags hidden from their view. */
    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    /**
     * Flips {@code uuid}'s hidden state, persists it, and returns the new
     * state (true = now hidden). Backs {@code /nametag toggle}.
     */
    public boolean toggle(UUID uuid) {
        boolean nowHidden = !hidden.remove(uuid);
        if (nowHidden) {
            hidden.add(uuid);
        }
        if (connection == null) {
            // The database never opened (see load()) — the toggle still
            // applies for this session, it just won't survive a restart.
            return nowHidden;
        }
        if (nowHidden) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO hidden (uuid) VALUES (?)")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save storage/" + DB_FILE_NAME + ": " + e.getMessage());
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM hidden WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save storage/" + DB_FILE_NAME + ": " + e.getMessage());
            }
        }
        return nowHidden;
    }

    /** Closes the underlying database connection. Safe to call even if {@link #load()} was never called. */
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Nothing useful to do about a failed close on shutdown/reload.
        }
    }
}
