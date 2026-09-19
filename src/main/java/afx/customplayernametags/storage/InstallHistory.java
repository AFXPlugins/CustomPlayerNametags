package afx.customplayernametags.storage;

import afx.customplayernametags.CustomPlayerNametags;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * Remembers, per plugin version, whether this server got the running version
 * by <em>updating</em> from an earlier one or by installing the plugin
 * <em>fresh</em> — used only for the {@code updated_from_previous_version}
 * bStats chart, so adoption of an update (servers that saw the update
 * notice and upgraded) can be told apart from brand-new installs.
 *
 * <p>Stored in {@code storage/install-info.yml}:
 * <pre>
 * version: 1.2.0
 * updated-from-previous-version: true
 * </pre>
 *
 * <p>Resolved once, at construction, in this order:
 * <ol>
 *   <li>No {@code install-info.yml} yet (or it has no readable version):
 *       an already-existing {@code config.yml} means an earlier version was
 *       installed before this one, so it's an update; no {@code config.yml}
 *       means a fresh install.</li>
 *   <li>The recorded version equals the running version: this is just a
 *       restart, so the answer recorded on the first run of this version is
 *       kept — a fresh install stays "fresh" across restarts.</li>
 *   <li>The recorded version differs from the running version: the plugin
 *       jar changed since the last run, so it's an update (a downgrade is
 *       counted the same way — either way it isn't a fresh install).</li>
 * </ol>
 *
 * <p><b>Must be constructed before</b> {@code saveDefaultConfig()} and
 * {@code ConfigMigrator.update(...)} run in {@code onEnable()}, because
 * step 1 relies on whether {@code config.yml} already existed when the
 * server started.
 */
public final class InstallHistory {

    private static final String FILE_NAME = "install-info.yml";
    private static final String KEY_VERSION = "version";
    private static final String KEY_UPDATED = "updated-from-previous-version";

    private final boolean updatedFromPreviousVersion;

    public InstallHistory(CustomPlayerNametags plugin) {
        String currentVersion = plugin.getDescription().getVersion();
        boolean configExisted = new File(plugin.getDataFolder(), "config.yml").exists();

        File file = StorageFiles.file(plugin, FILE_NAME);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String recordedVersion = yaml.getString(KEY_VERSION);

        boolean updated;
        if (recordedVersion == null) {
            updated = configExisted;
        } else if (recordedVersion.equals(currentVersion)) {
            updated = yaml.getBoolean(KEY_UPDATED, configExisted);
        } else {
            updated = true;
        }
        this.updatedFromPreviousVersion = updated;

        yaml.set(KEY_VERSION, currentVersion);
        yaml.set(KEY_UPDATED, updated);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write storage/" + FILE_NAME + ": " + e.getMessage());
        }
    }

    /**
     * Whether the running plugin version was reached by updating from an
     * earlier version ({@code true}) rather than being a fresh install
     * ({@code false}).
     */
    public boolean wasUpdatedFromPreviousVersion() {
        return updatedFromPreviousVersion;
    }
}