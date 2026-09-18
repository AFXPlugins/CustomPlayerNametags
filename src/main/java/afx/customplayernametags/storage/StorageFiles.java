package afx.customplayernametags.storage;

import afx.customplayernametags.CustomPlayerNametags;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared helpers for everything that lives under the plugin's
 * {@code storage/} subfolder — every file the plugin manages
 * <em>except</em> {@code config.yml} itself, which stays directly in the
 * plugin's data folder. This covers both the SQLite-backed stores
 * ({@code player-formats.db}, {@code nametag-presets.db},
 * {@code nametag-toggles.db}) and the remaining YAML files
 * ({@code messages.yml}, {@code gui.yml}, {@code group-formats.yml},
 * {@code editor-placeholders.yml}).
 */
public final class StorageFiles {

    private StorageFiles() {
    }

    /**
     * The {@code storage/} folder inside the plugin's data folder, creating
     * it (and any missing parent directories) if it doesn't exist yet.
     */
    public static File storageFolder(CustomPlayerNametags plugin) {
        File folder = new File(plugin.getDataFolder(), "storage");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    /** A {@code fileName} inside the {@code storage/} folder, creating that folder first if needed. */
    public static File file(CustomPlayerNametags plugin, String fileName) {
        return new File(storageFolder(plugin), fileName);
    }

    /**
     * Opens a new JDBC connection to {@code storage/<fileName>}, creating
     * the storage folder (and, since SQLite creates the database file
     * itself on first connect, the database) if either doesn't exist yet.
     */
    public static Connection connect(CustomPlayerNametags plugin, String fileName) throws SQLException {
        ensureDriverLoaded();
        File db = file(plugin, fileName);
        return DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
    }

    /** Whether {@link #ensureDriverLoaded()} has already run (successfully or not) this session. */
    private static boolean driverLoadAttempted;

    /**
     * Loads the bundled SQLite JDBC driver class explicitly, so
     * {@link DriverManager} can always find it.
     *
     * <p>{@code DriverManager} normally discovers a driver through the
     * {@code META-INF/services/java.sql.Driver} service file on the
     * classpath. That discovery runs against the system classloader, which
     * on a Bukkit server does not see a plugin jar's own service files —
     * and the driver here is additionally shaded and relocated (see the
     * shade plugin config in pom.xml). Referencing the class by name is
     * what reliably registers it in that situation; the string literal
     * below is rewritten to the relocated package by the shade plugin at
     * build time exactly like any other reference to the class, so it stays
     * correct in the shipped jar.
     *
     * <p>Failure is swallowed rather than thrown: if the driver really is
     * missing, the subsequent {@code getConnection} call fails with a
     * {@link SQLException} that each store already handles and reports, so
     * there is no value in surfacing the same problem twice.
     */
    private static synchronized void ensureDriverLoaded() {
        if (driverLoadAttempted) {
            return;
        }
        driverLoadAttempted = true;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException | LinkageError e) {
            // Reported by the caller's own SQLException handling instead.
        }
    }

    /**
     * Moves a pre-existing file that used to live directly in the plugin's
     * data folder (before it or its contents moved under {@code storage/})
     * into {@code storage/<fileName>} — same name, new location — so a
     * server owner upgrading from an older version doesn't lose whatever
     * that file held. No-ops if the legacy file isn't present, or if a file
     * already exists at the destination (already migrated).
     */
    public static void migrateLegacyFile(CustomPlayerNametags plugin, File legacyFile, String fileName) {
        if (!legacyFile.exists()) {
            return;
        }
        File destination = file(plugin, fileName);
        if (destination.exists()) {
            // Already migrated (or a destination file was independently
            // created) — don't clobber it with a possibly-stale legacy copy.
            return;
        }
        if (!legacyFile.renameTo(destination)) {
            plugin.getLogger().warning("Could not migrate " + legacyFile.getName()
                    + " into the storage/ folder; please move it manually to " + destination.getAbsolutePath());
        }
    }

    /**
     * Copies the bundled jar resource {@code fileName} (matched by its name
     * at the jar's root, e.g. {@code messages.yml}) into
     * {@code storage/<fileName>}, creating the storage folder first if
     * needed. This is the {@code storage/}-aware equivalent of
     * {@link org.bukkit.plugin.java.JavaPlugin#saveResource}, which always
     * mirrors a resource's own path from the jar into the data folder — so
     * it can't be used to relocate a root-level bundled resource into the
     * {@code storage/} subfolder, only reproduce it at the same relative
     * path it already has.
     *
     * <p>If {@code replace} is {@code false} and a file already exists at
     * the destination, this does nothing, matching
     * {@code saveResource}'s own semantics. If {@code replace} is
     * {@code true}, the destination is always overwritten with the bundled
     * default.
     */
    public static void saveResource(CustomPlayerNametags plugin, String fileName, boolean replace) {
        File destination = file(plugin, fileName);
        if (destination.exists() && !replace) {
            return;
        }
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                plugin.getLogger().warning("Bundled resource " + fileName + " not found in the plugin jar.");
                return;
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write storage/" + fileName + ": " + e.getMessage());
        }
    }
}
