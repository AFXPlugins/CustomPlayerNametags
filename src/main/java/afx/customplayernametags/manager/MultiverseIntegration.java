package afx.customplayernametags.manager;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Multiverse-Core is only a soft dependency (see plugin.yml) — this plugin
 * never compiles against its classes. Instead of that, the one thing this
 * integration needs ({@code teleport.passenger-mode} from Multiverse-Core's
 * own {@code config.yml}) is read straight off disk as plain YAML, exactly
 * like {@link afx.customplayernametags.config.MessageManager} reads this
 * plugin's own files. Mirrors {@link LuckPermsIntegration} and
 * {@link BedrockDetector} in never throwing if the plugin is absent —
 * every method here simply returns a safe default instead.
 */
public final class MultiverseIntegration {

    private static final String PLUGIN_NAME = "Multiverse-Core";
    private static final String PORTALS_PLUGIN_NAME = "Multiverse-Portals";
    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final String PASSENGER_MODE_PATH = "teleport.passenger-mode";
    private static final String DEFAULT_PASSENGER_MODE = "default";

    private MultiverseIntegration() {
    }

    /** Whether the Multiverse-Core plugin is present and enabled. */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    /**
     * Whether the Multiverse-Portals plugin is present and enabled. The
     * passenger-mode join notice ({@code PlayerConnectionListener#notifyOfMultiversePassengerMode})
     * only actually matters to a server running Multiverse-Portals — Multiverse-Core's
     * {@code teleport.passenger-mode} setting being left at {@code default} is otherwise
     * not something this plugin needs to bother an admin about, since it's Multiverse-Portals'
     * own teleports that get silently blocked by it.
     */
    public static boolean isPortalsAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled(PORTALS_PLUGIN_NAME);
    }

    /**
     * Whether Multiverse-Core's {@code teleport.passenger-mode} config
     * value is still at its out-of-the-box {@code default} setting — the
     * setting under which Multiverse lets the server handle passengers and
     * vehicles on teleport itself, which is exactly what can let a
     * nametag's passenger entity get dragged along (or block) a player's
     * teleport instead of this plugin's own dismount handling taking care
     * of it first. Reads Multiverse-Core's config.yml fresh off disk every
     * call rather than caching it, since this is only ever checked once per
     * admin join, and the value can change at any time (including via the
     * clickable {@code /mv config} command this notice itself offers).
     *
     * <p>Returns {@code false} if Multiverse-Core isn't installed, its
     * config file can't be found or read, or the value has already been
     * changed away from {@code "default"}.
     */
    public static boolean isPassengerModeDefault() {
        if (!isAvailable()) {
            return false;
        }
        try {
            Plugin multiverse = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (multiverse == null) {
                return false;
            }
            File configFile = new File(multiverse.getDataFolder(), CONFIG_FILE_NAME);
            if (!configFile.isFile()) {
                return false;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            String mode = config.getString(PASSENGER_MODE_PATH);
            return mode != null && mode.trim().equalsIgnoreCase(DEFAULT_PASSENGER_MODE);
        } catch (Throwable t) {
            // Malformed/unreadable config, unexpected plugin internals, etc.
            // — never let a best-effort join notice break the join itself.
            return false;
        }
    }
}
