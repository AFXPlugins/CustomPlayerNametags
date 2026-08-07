package placeholder;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.manager.NametagManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Registers this plugin's PlaceholderAPI expansion, exposing two placeholders
 * that reflect exactly what {@link NametagManager} is currently rendering
 * above a player's head:
 *
 * <ul>
 *   <li>{@code %customplayernametags_format%} - the format currently in
 *       effect for the requesting player: their per-player override set via
 *       {@code /nametags format set} if one exists, otherwise the global
 *       {@code nametag-format} from config.yml. Fully resolved through
 *       PlaceholderAPI with {@code &} colors translated, same as
 *       {@link NametagManager#getEffectiveParsedFormat(Player)}.</li>
 *   <li>{@code %customplayernametags_format_global%} - always the global
 *       {@code nametag-format} from config.yml, regardless of whether the
 *       requesting player has a per-player override, resolved the same way
 *       via {@link NametagManager#getGlobalParsedFormat(Player)}.</li>
 * </ul>
 */
public final class CustomPlayerNametagsExpansion extends PlaceholderExpansion {

    private static final String IDENTIFIER = "customplayernametags";

    private final CustomPlayerNametags plugin;
    private final NametagManager nametagManager;

    public CustomPlayerNametagsExpansion(CustomPlayerNametags plugin, NametagManager nametagManager) {
        this.plugin = plugin;
        this.nametagManager = nametagManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return "AFXPlugins";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * Keeps this expansion registered across a {@code /papi reload}, so the
     * placeholders don't disappear until this plugin itself is reloaded.
     */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params.equalsIgnoreCase("format")) {
            // The per-player/global effective format is meaningless without
            // a specific player to resolve overrides and placeholders for.
            return player == null ? "" : nametagManager.getEffectiveParsedFormat(player);
        }

        if (params.equalsIgnoreCase("format_global")) {
            // Always the global format. A player context is still passed
            // through (when available) so any player-specific placeholders
            // inside it resolve instead of coming back blank.
            return nametagManager.getGlobalParsedFormat(player);
        }

        return null;
    }
}