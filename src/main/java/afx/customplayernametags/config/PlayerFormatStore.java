package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists per-player {@code nametag-format} overrides (set via
 * {@code /nametags format set <player> "<format>"}) to a
 * {@code player-formats.yml} file in the plugin's data folder, so they
 * survive server restarts instead of only living in memory for the current
 * run.
 *
 * <p>File layout:
 * <pre>
 * formats:
 *   &lt;player-uuid&gt;: '&amp;6VIP &amp;f%player_name%'
 *   &lt;player-uuid&gt;: '%luckperms_prefix%%player_name%'
 * </pre>
 * Keyed by UUID (not username) so overrides survive name changes, matching
 * how the rest of the plugin already tracks players.
 */
public final class PlayerFormatStore {

    private final CustomPlayerNametags plugin;
    private final File file;
    private final Map<UUID, String> formats = new ConcurrentHashMap<>();

    public PlayerFormatStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "player-formats.yml");
    }

    /**
     * Loads {@code player-formats.yml} from disk into memory, replacing
     * whatever was previously held. Safe to call if the file doesn't exist
     * yet (nothing is loaded, in-memory map ends up empty) — the file is
     * only created the first time an override is actually saved.
     */
    public void load() {
        formats.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("formats");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                // Skip a malformed/hand-edited key rather than failing the
                // whole load over one bad entry.
                plugin.getLogger().warning("Skipping invalid UUID in player-formats.yml: " + key);
                continue;
            }
            String format = section.getString(key);
            if (format != null && !format.isEmpty()) {
                formats.put(uuid, format);
            }
        }
    }

    /** The stored override for {@code uuid}, or {@code null} if they don't have one. */
    public String get(UUID uuid) {
        return formats.get(uuid);
    }

    /**
     * Sets (or, if {@code format} is {@code null} or blank, clears) the
     * stored override for {@code uuid} and immediately writes the whole
     * store back to {@code player-formats.yml}.
     *
     * <p>This save is synchronous — fine for a file this small and for how
     * infrequently {@code /nametags format set}/{@code reset} run (an admin
     * command, not a hot path), so it isn't worth the complexity of an async
     * write.
     */
    public void set(UUID uuid, String format) {
        if (format == null || format.isEmpty()) {
            formats.remove(uuid);
        } else {
            formats.put(uuid, format);
        }
        save();
    }

    /** Clears the stored override for {@code uuid}, if any, and saves. Equivalent to {@code set(uuid, null)}. */
    public void remove(UUID uuid) {
        set(uuid, null);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : formats.entrySet()) {
            yaml.set("formats." + entry.getKey(), entry.getValue());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save player-formats.yml: " + e.getMessage());
        }
    }
}
