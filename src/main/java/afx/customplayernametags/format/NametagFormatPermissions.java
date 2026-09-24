package afx.customplayernametags.format;

import afx.customplayernametags.config.ConfigManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.List;

/**
 * Shared permission logic for nametag formats. Also hosts
 * {@link #notifyOverride}, the {@code customplayernametags.notify.override.*}
 * lookup used by every code path that tells a player their format changed.
 * The nodes are checked on the person <em>making</em> the change, not on the
 * player whose format is changed.
 *
 * <p>Character-limit logic for anything that lets a player set their own
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

    /** The unlimited-characters bypass node. Must be granted explicitly — see {@link #hasExplicitPermission}. */
    private static final String BYPASS_PERMISSION = "customplayernametags.bypasslinecharacterlimit";

    /** Makes the holder's format changes always notify the affected player. See {@link #notifyOverride}. */
    private static final String NOTIFY_OVERRIDE_NOTIFY = "customplayernametags.notify.override.notify";

    /** Makes the holder's format changes never notify the affected player. See {@link #notifyOverride}. */
    private static final String NOTIFY_OVERRIDE_SILENT = "customplayernametags.notify.override.silent";

    private NametagFormatPermissions() {
    }

    /**
     * Whether {@code player} has {@code node} itself granted (true) among their
     * effective permissions (own + inherited, e.g. from LuckPerms groups) —
     * as opposed to {@link Player#hasPermission}, which also returns true when
     * a wildcard such as {@code *} or {@code customplayernametags.*} covers
     * the node. Admin groups commonly carry such a wildcard, which used to
     * silently exempt every admin from the line limit; matching the exact
     * node here means an admin only bypasses the limit when someone
     * deliberately granted them {@value #BYPASS_PERMISSION}. An explicit
     * {@code false} on the node is ignored, same as
     * {@link #highestTieredPermission}.
     */
    private static boolean hasExplicitPermission(Player player, String node) {
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().equalsIgnoreCase(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Override of {@code nametag-format-notify-mode} for a format change,
     * from the {@code customplayernametags.notify.override.silent} and
     * {@code customplayernametags.notify.override.notify} nodes held by
     * {@code changer} — the person <em>making</em> the change (command sender
     * or GUI editor), not the player whose format is changed. {@code FALSE}
     * = never tell the affected player, {@code TRUE} = always tell them,
     * {@code null} = no override (follow the config / the command's
     * announce|silent argument). {@code silent} wins if the changer somehow
     * has both. A non-player changer (console) never has an override.
     *
     * <p>Only an explicitly granted node counts, via
     * {@link #hasExplicitPermission}. The previous {@code isPermissionSet &&
     * hasPermission} check was satisfied by a wildcard ({@code *},
     * {@code customplayernametags.*}), which handed a wildcard holder
     * <em>both</em> nodes at once — and since {@code silent} wins, admins'
     * changes silently never notified anyone, whatever mode was configured.
     */
    public static Boolean notifyOverride(CommandSender changer) {
        if (!(changer instanceof Player player)) {
            return null;
        }
        if (hasExplicitPermission(player, NOTIFY_OVERRIDE_SILENT)) {
            return Boolean.FALSE;
        }
        if (hasExplicitPermission(player, NOTIFY_OVERRIDE_NOTIFY)) {
            return Boolean.TRUE;
        }
        return null;
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
     *       unlimited, wins over everything else. Only counts when the node
     *       itself is granted; a wildcard ({@code *},
     *       {@code customplayernametags.*}) does not, so admins truncate like
     *       everyone else unless explicitly given this node.</li>
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
        if (hasExplicitPermission(player, BYPASS_PERMISSION)) {
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