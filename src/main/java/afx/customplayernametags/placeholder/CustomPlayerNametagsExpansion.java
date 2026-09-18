package afx.customplayernametags.placeholder;

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
 *
 * <p>Both go through {@link NametagManager#parseFormat}, which strips every
 * {@code {widget ...}...{/widget}} marker down to just that widget's own
 * (possibly player-filled) inner content spliced in at the widget's
 * position — see {@link afx.customplayernametags.format.TemplateMarkers#strip} —
 * so neither placeholder ever leaks raw widget syntax; what a placeholder
 * consumer sees is exactly the text a widget resolves to, in place.
 */
public final class CustomPlayerNametagsExpansion extends PlaceholderExpansion {

    private static final String IDENTIFIER = "customplayernametags";

    /**
     * Guards against infinite recursion when one of this expansion's own
     * placeholders is put inside a nametag format: resolving the format
     * calls PlaceholderAPI, which calls back into here, which resolves the
     * format again... until the thread's stack overflows and the nametag
     * refresh task dies. A server owner can reasonably write
     * {@code %customplayernametags_format%} into {@code nametag-format} by
     * mistake, so the re-entrant call is simply resolved as empty text
     * instead of being allowed to recurse.
     */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
        boolean known = params.equalsIgnoreCase("format") || params.equalsIgnoreCase("format_global");
        if (!known) {
            return null;
        }
        if (Boolean.TRUE.equals(RESOLVING.get())) {
            // Already resolving a format further up this same call stack —
            // see RESOLVING. Resolve as empty rather than recursing.
            return "";
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            if (params.equalsIgnoreCase("format")) {
                // The per-player/global effective format is meaningless without
                // a specific player to resolve overrides and placeholders for.
                return player == null ? "" : nametagManager.getEffectiveParsedFormat(player);
            }
            // Always the global format. A player context is still passed
            // through (when available) so any player-specific placeholders
            // inside it resolve instead of coming back blank.
            return nametagManager.getGlobalParsedFormat(player);
        } finally {
            RESOLVING.set(Boolean.FALSE);
        }
    }
}