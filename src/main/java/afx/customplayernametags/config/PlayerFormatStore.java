package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.format.TemplateMarkers;
import afx.customplayernametags.storage.StorageFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists per-player {@code nametag-format} overrides (set via
 * {@code /nametags format player set <player> "<format>"}) to a SQLite
 * database at {@code storage/player-formats.db} in the plugin's data
 * folder, so they survive server restarts instead of only living in memory
 * for the current run.
 *
 * <p>Keyed by UUID (not username) so overrides survive name changes,
 * matching how the rest of the plugin already tracks players.
 *
 * <p>A pre-existing {@code player-formats.yml} — the format this store used
 * before it moved to a database — is imported automatically the first time
 * this loads on an upgraded install, then deleted; see
 * {@link #migrateLegacyYaml(Connection)}.
 */
public final class PlayerFormatStore {

    private static final String DB_FILE_NAME = "player-formats.db";

    private final CustomPlayerNametags plugin;
    private final File legacyYamlFile;
    private final Map<UUID, String> formats = new ConcurrentHashMap<>();
    private Connection connection;

    public PlayerFormatStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.legacyYamlFile = new File(plugin.getDataFolder(), "player-formats.yml");
    }

    /**
     * (Re-)opens {@code storage/player-formats.db}, creating the table if
     * needed, importing a legacy {@code player-formats.yml} if one is still
     * around, then loads every stored override into memory, replacing
     * whatever was previously held.
     */
    public void load() {
        formats.clear();
        closeQuietly();
        try {
            connection = StorageFiles.connect(plugin, DB_FILE_NAME);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS formats (uuid TEXT PRIMARY KEY, format TEXT NOT NULL)");
            }
            migrateLegacyYaml(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT uuid, format FROM formats")) {
                while (rs.next()) {
                    putIfValidUuid(rs.getString("uuid"), rs.getString("format"));
                }
            }
            migrateWidgetIds();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load storage/" + DB_FILE_NAME + ": " + e.getMessage());
        }
    }

    /**
     * One-time upgrade path for overrides that have a widget missing the
     * {@code id=} attribute the widget-fill system now keys on
     * (see {@link TemplateMarkers#needsIdAssignment}) — a hand-typed
     * {@code {widget...}} tag, or one written by a version of the plugin
     * that predates ids: rewrites each affected row with every widget carrying
     * an id, so every widget
     * has something {@link afx.customplayernametags.config.PlayerWidgetFillStore}
     * can key a fill against. No-ops on every load after the first one
     * following an upgrade, and on a store that never had a widget in it.
     */
    private void migrateWidgetIds() {
        for (Map.Entry<UUID, String> entry : formats.entrySet()) {
            String stored = entry.getValue();
            if (TemplateMarkers.needsIdAssignment(stored)) {
                set(entry.getKey(), TemplateMarkers.ensureWidgetIds(stored));
            }
        }
    }

    private void putIfValidUuid(String rawUuid, String format) {
        try {
            formats.put(UUID.fromString(rawUuid), format);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipping invalid UUID in " + DB_FILE_NAME + ": " + rawUuid);
        }
    }

    /**
     * One-time import of the old {@code player-formats.yml} (used before
     * this store moved to SQLite) into the {@code formats} table, then
     * deletes the file so it isn't re-imported — or mistaken for still
     * being authoritative — on a later load. No-ops if that file isn't
     * present, e.g. a fresh install or one already migrated.
     */
    private void migrateLegacyYaml(Connection connection) {
        if (!legacyYamlFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacyYamlFile);
        ConfigurationSection section = yaml.getConfigurationSection("formats");
        if (section != null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR REPLACE INTO formats (uuid, format) VALUES (?, ?)")) {
                for (String key : section.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(key);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Skipping invalid UUID while migrating player-formats.yml: " + key);
                        continue;
                    }
                    String format = section.getString(key);
                    if (format != null && !format.isEmpty()) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, format);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to migrate player-formats.yml into storage/" + DB_FILE_NAME + ": " + e.getMessage());
                return;
            }
        }
        if (!legacyYamlFile.delete()) {
            plugin.getLogger().warning("Migrated player-formats.yml but could not delete the old file; please remove it manually.");
        } else {
            plugin.getLogger().info("Migrated player-formats.yml into storage/" + DB_FILE_NAME + ".");
        }
    }

    /** The stored override for {@code uuid}, or {@code null} if they don't have one. */
    public String get(UUID uuid) {
        return formats.get(uuid);
    }

    /**
     * A snapshot of every currently-active per-player format override (exactly
     * as stored, widget tags included — same as {@link #get}), for callers that need to scan all
     * of them rather than look one player up, e.g. bStats reporting whether any
     * format currently in use contains a Widget item. A disabled override isn't
     * in {@link #formats} in the first place (see {@link #remove}), so nothing
     * further needs to be filtered out here.
     */
    public java.util.Collection<String> getAllFormats() {
        return new java.util.ArrayList<>(formats.values());
    }

    /**
     * Sets (or, if {@code format} is {@code null} or blank, clears) the
     * stored override for {@code uuid} and immediately persists the change
     * to {@code storage/player-formats.db}.
     *
     * <p>This write is synchronous — fine for how infrequently
     * {@code /nametags format player set}/{@code disable} run (an admin
     * command, not a hot path), so it isn't worth the complexity of an
     * async write.
     */
    public void set(UUID uuid, String format) {
        if (format == null || format.isEmpty()) {
            remove(uuid);
            return;
        }
        // The GUI editor's serialized form and a format an admin typed directly are one and the
        // same {widget ...}...{/widget} tag text, so this stores what it was given, assigning
        // an id to any widget that doesn't already have one (a hand-typed
        // {widget...} tag, most likely).
        format = TemplateMarkers.ensureWidgetIds(format);
        formats.put(uuid, format);
        if (connection == null) {
            // The database never opened (see load()) — the override still
            // applies for this session, it just can't be persisted. Already
            // reported once at load time; don't NPE on top of it here.
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO formats (uuid, format) VALUES (?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, format);
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save storage/" + DB_FILE_NAME + ": " + e.getMessage());
        }
    }

    /** Clears the stored override for {@code uuid}, if any, and saves. Equivalent to {@code set(uuid, null)}. */
    public void remove(UUID uuid) {
        formats.remove(uuid);
        if (connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM formats WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save storage/" + DB_FILE_NAME + ": " + e.getMessage());
        }
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
