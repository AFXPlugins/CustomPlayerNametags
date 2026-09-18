package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.storage.StorageFiles;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists per-player Widget fill-ins collected through the player-facing
 * "Edit Nametag" flow (the {@code Target.PLAYER_EDIT} session in
 * {@link afx.customplayernametags.manager.NametagEditorManager}) — the
 * content a player typed into a Widget slot embedded in whichever
 * global/group format currently applies to them.
 *
 * <p>These are kept completely separate from {@link PlayerFormatStore}'s
 * explicit per-player {@code nametag-format} overrides (set via
 * {@code /nametags format player set}), keyed instead by (player UUID,
 * widget id — see {@link afx.customplayernametags.format.TemplateMarkers.Widget#id()}
 * and {@link afx.customplayernametags.format.TemplateMarkers#extractWidgetContents}).
 * Before this store existed, the only way the plugin had to remember a
 * player's widget fill was to fork their whole effective format into
 * {@link PlayerFormatStore} as if it were a fully custom override — which
 * meant {@code /nametags format player disable} (which only ever meant to
 * clear an admin-set override) wiped out a player's widget customization
 * right along with it, and a player who'd filled a widget silently stopped
 * seeing later edits to the global/group template. Splitting this out is
 * what lets
 * {@link afx.customplayernametags.manager.NametagManager#getEffectiveRawFormat}
 * overlay a player's fills back onto whichever template currently applies
 * (see {@link afx.customplayernametags.format.TemplateMarkers#overlayWidgetFills})
 * instead of ever writing a merged copy into {@code PlayerFormatStore}, and
 * lets {@code resetFormatOverride} clear an explicit override without
 * touching this store at all.
 *
 * <p>Keying by widget id is what lets a fill survive an admin reordering,
 * adding, or removing other widgets in the template — a fill only ever gets
 * orphaned by that same widget being removed, not by an unrelated one moving
 * around it. See
 * {@link afx.customplayernametags.manager.NametagManager#pruneOrphanedWidgetFills}
 * and {@link #pruneOrphans} for the housekeeping that clears out a fill
 * once its widget really is gone for good.
 *
 * <p>Persisted to {@code storage/player-widget-fills.db} (SQLite), keyed by
 * UUID (not username) so entries survive name changes, matching every
 * other per-player store in this plugin.
 */
public final class PlayerWidgetFillStore {

    private static final String DB_FILE_NAME = "player-widget-fills.db";

    private final CustomPlayerNametags plugin;
    /** uuid -> (widget id -> filled content), held in memory. */
    private final Map<UUID, Map<String, String>> fills = new ConcurrentHashMap<>();
    private Connection connection;

    public PlayerWidgetFillStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re-)opens {@code storage/player-widget-fills.db}, creating the table
     * if needed, then loads every stored fill into memory, replacing whatever was
     * previously held.
     */
    public void load() {
        fills.clear();
        closeQuietly();
        try {
            connection = StorageFiles.connect(plugin, DB_FILE_NAME);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS widget_fills ("
                        + "uuid TEXT NOT NULL, widget_id TEXT NOT NULL, content TEXT NOT NULL, "
                        + "PRIMARY KEY (uuid, widget_id))");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT uuid, widget_id, content FROM widget_fills")) {
                while (rs.next()) {
                    putIfValidUuid(rs.getString("uuid"), rs.getString("widget_id"), rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load storage/" + DB_FILE_NAME + ": " + e.getMessage());
        }
    }

    private void putIfValidUuid(String rawUuid, String widgetId, String content) {
        try {
            UUID uuid = UUID.fromString(rawUuid);
            fills.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(widgetId, content);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipping invalid UUID in " + DB_FILE_NAME + ": " + rawUuid);
        }
    }

    /**
     * {@code uuid}'s stored widget fills: widget id (see
     * {@link afx.customplayernametags.format.TemplateMarkers.Widget#id()})
     * to the raw content they filled that widget with. Empty if they've
     * never filled a widget. A defensive copy — safe for the caller to hold
     * onto or mutate without affecting this store.
     */
    public Map<String, String> getAll(UUID uuid) {
        Map<String, String> existing = fills.get(uuid);
        return existing == null || existing.isEmpty() ? Collections.emptyMap() : new HashMap<>(existing);
    }

    /**
     * Replaces {@code uuid}'s entire stored widget-fill set with exactly
     * {@code newFills} (widget id -> content) and immediately persists the
     * change — a full replace rather than a merge, so a widget that got
     * removed from the template doesn't leave a stale fill behind under its
     * old id for some unrelated future widget to inherit. {@code null} or
     * empty clears every stored fill for {@code uuid}.
     */
    public void setAll(UUID uuid, Map<String, String> newFills) {
        if (newFills == null || newFills.isEmpty()) {
            fills.remove(uuid);
        } else {
            fills.put(uuid, new ConcurrentHashMap<>(newFills));
        }
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM widget_fills WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            if (newFills != null && !newFills.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO widget_fills (uuid, widget_id, content) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, String> entry : newFills.entrySet()) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, entry.getKey());
                        insert.setString(3, entry.getValue() == null ? "" : entry.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save storage/" + DB_FILE_NAME + ": " + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Nothing further we can do if even the rollback fails.
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Non-fatal — the connection is still otherwise usable.
            }
        }
    }

    /** Clears every stored widget fill for {@code uuid}. Equivalent to {@code setAll(uuid, null)}. */
    public void clearAll(UUID uuid) {
        setAll(uuid, null);
    }

    /**
     * Housekeeping GC: for every player with at least one stored fill,
     * drops whichever of their fills key a widget id that isn't in
     * {@code liveIds} — i.e. a widget that no longer exists in any format
     * currently reachable by anyone (global, Bedrock-global, every group
     * format, every player override) — and persists the trimmed set for
     * anyone actually affected. A no-op for a player whose fills are all
     * still live. Driven by
     * {@link afx.customplayernametags.manager.NametagManager#pruneOrphanedWidgetFills},
     * which is the one place that can assemble the full set of
     * currently-reachable widget ids across every format tier.
     */
    public void pruneOrphans(Set<String> liveIds) {
        Set<String> live = liveIds == null ? Collections.emptySet() : liveIds;
        for (Map.Entry<UUID, Map<String, String>> entry : fills.entrySet()) {
            Map<String, String> current = entry.getValue();
            if (current == null || current.isEmpty()) {
                continue;
            }
            Map<String, String> trimmed = new HashMap<>(current);
            if (trimmed.keySet().retainAll(live)) {
                setAll(entry.getKey(), trimmed);
            }
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
