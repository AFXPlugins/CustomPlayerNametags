package afx.customplayernametags.format;

import afx.customplayernametags.config.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.List;

/**
 * Shared character-limit logic for anything that lets a player set their own
 * individual {@code nametag-format} override — the legacy {@code /nametags
 * set} subcommand, the separate {@code /nametag set} command, and the
 * chat-based {@code /nametag editor} (see
 * {@link afx.customplayernametags.manager.NametagEditorManager}) all go
 * through {@link #effectiveLineCharacterLimit} so none of the three can ever
 * disagree about how many characters a line may hold.
 */
public final class NametagFormatPermissions {

    /**
     * Prefix for the tiered {@code customplayernametags.bypasslinecharacterlimit.<N>}
     * permission nodes (e.g. {@code customplayernametags.bypasslinecharacterlimit.40}),
     * which let a permissions plugin grant a specific player/group a
     * per-line character limit of {@code N}, overriding
     * {@code nametag-line-max-characters} from config.yml. See
     * {@link #effectiveLineCharacterLimit}.
     */
    private static final String CHARACTER_LIMIT_PERMISSION_PREFIX = "customplayernametags.bypasslinecharacterlimit.";

    private NametagFormatPermissions() {
    }

    /**
     * Scans {@code player}'s effective permissions (own + inherited, e.g.
     * from LuckPerms groups) for every granted (true) node starting with
     * {@code prefix} (e.g. {@code customplayernametags.linelimit.} or
     * {@code customplayernametags.bypasslinecharacterlimit.}) and returns
     * the largest numeric suffix {@code N} found, or {@code -1} if none are
     * granted. A node with a non-numeric or negative suffix (e.g. a typo,
     * or explicitly {@code false} to revoke it again for a specific
     * player/group) is ignored rather than throwing.
     */
    private static int highestTieredPermission(Player player, String prefix) {
        int highest = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String permission = info.getPermission();
            if (!permission.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            try {
                int value = Integer.parseInt(permission.substring(prefix.length()));
                if (value >= 0 && value > highest) {
                    highest = value;
                }
            } catch (NumberFormatException ignored) {
                // Not a numeric suffix — not one of our nodes, skip it.
            }
        }
        return highest;
    }

    /**
     * The maximum number of characters {@code player} may use on a single
     * line of their own format via {@code /nametags set}, {@code /nametag
     * set}, or {@code /nametag editor}, or {@code -1} for no limit.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code customplayernametags.bypasslinecharacterlimit} —
     *       unlimited, wins over everything else.</li>
     *   <li>The highest granted
     *       {@code customplayernametags.bypasslinecharacterlimit.<N>} node
     *       (e.g. {@code customplayernametags.bypasslinecharacterlimit.40})
     *       — lets a permissions plugin give specific players/groups a
     *       bigger (or smaller) per-line character limit than the server
     *       default without touching config.yml. If a player has several
     *       of these nodes, the largest {@code N} applies.</li>
     *   <li>Otherwise, the configured {@code nametag-line-max-characters}.</li>
     * </ol>
     */
    public static int effectiveLineCharacterLimit(ConfigManager config, Player player) {
        if (player.hasPermission("customplayernametags.bypasslinecharacterlimit")) {
            return -1;
        }
        int tiered = highestTieredPermission(player, CHARACTER_LIMIT_PERMISSION_PREFIX);
        return tiered >= 0 ? tiered : config.getNametagLineMaxCharacters();
    }

    /**
     * @param plainLines each line's actually-visible text — see
     *                   {@link afx.customplayernametags.format.NametagFormatter#plainText}
     *                   — i.e. after placeholders are resolved and color/
     *                   formatting markup is stripped out, not the raw
     *                   stored format string. {@code "<red>%luckperms_prefix%{player}"}
     *                   parsed for a VIP player named John123 becomes the
     *                   single line {@code "[VIP]John123"} (12 characters),
     *                   not the length of the unparsed format itself.
     */
    public static boolean exceedsLineCharacterLimit(List<String> plainLines, int limit) {
        if (limit < 0) return false;
        for (String line : plainLines) {
            if (line.length() > limit) return true;
        }
        return false;
    }
}
