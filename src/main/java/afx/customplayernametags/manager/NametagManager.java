package afx.customplayernametags.manager;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.PlayerFormatStore;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes each player's full nametag from PlaceholderAPI, hides the vanilla
 * overhead nametag via a scoreboard team with {@code NameTagVisibility.NEVER},
 * and drives an invisible marker display entity that carries the full colored text.
 *
 * Tab list, chat, and real usernames are left completely untouched.
 */
public final class NametagManager {

    /** How often (in ticks) placeholders are re-checked for every online player. */
    private static final long REFRESH_INTERVAL_TICKS = 20L;

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    /**
     * Backs per-player {@code nametag-format} overrides set via
     * {@code /nametags format set <player> "<format>"}. Persisted to
     * {@code player-formats.yml} so overrides survive server restarts. A
     * player with no entry here just uses the global {@code nametag-format}
     * from config.yml, same as before this existed.
     */
    private final PlayerFormatStore formatStore;
    private BukkitTask refreshTask;
    private NametagDisplayManager displayManager;

    /**
     * Whether the PlaceholderAPI plugin is present and enabled. PlaceholderAPI
     * is a soft dependency — if it's missing, placeholders in
     * {@code nametag-format} are left unresolved (only {@code &} color codes
     * are applied) instead of throwing {@link NoClassDefFoundError}.
     */
    private final boolean placeholderApiAvailable;

    public NametagManager(CustomPlayerNametags plugin, ConfigManager config, PlayerFormatStore formatStore) {
        this.plugin = plugin;
        this.config = config;
        this.formatStore = formatStore;
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")

        if (placeholderApiAvailable) {
            plugin.getLogger().info("PlaceholderAPI hooked successfully.");
        } else {
            plugin.getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
        }

    }

    public void setDisplayManager(NametagDisplayManager displayManager) {
        this.displayManager = displayManager;
    }

    public NametagDisplayManager getDisplayManager() {
        return displayManager;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void startRefreshTask() {
        stopRefreshTask();
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player, false);
            }
        }, REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
    }

    public void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public void shutdown() {
        stopRefreshTask();
        if (displayManager != null) {
            displayManager.shutdown();
        }
        // Remove hide-teams for everyone still online.
        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (UUID uuid : states.keySet()) {
            removeHideTeam(uuid, viewers);
        }
        states.clear();
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player, true);
        }
    }

    public void forget(UUID uuid) {
        states.remove(uuid);
        // Note: formatStore is intentionally NOT cleared here. forget()
        // runs on player quit, and a per-player format set via
        // /nametags format set is meant to persist across sessions (and
        // now across restarts too), not get wiped out the moment the
        // player logs off.
        if (displayManager != null) {
            displayManager.remove(uuid);
        }
        removeHideTeam(uuid, new ArrayList<>(Bukkit.getOnlinePlayers()));
    }

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    public void refresh(Player target, boolean force) {
        String fullText = computeFullText(target);
        PlayerState previous = states.get(target.getUniqueId());

        if (!force && previous != null && previous.fullText().equals(fullText)) {
            // Text unchanged — still ensure the display entity exists / follows.
            if (displayManager != null) {
                displayManager.update(target, fullText, false);
            }
            return;
        }

        PlayerState newState = new PlayerState(fullText);
        states.put(target.getUniqueId(), newState);

        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());

        // Ensure a team exists that hides the vanilla nametag.
        if (previous == null) {
            createHideTeam(target, viewers);
        } else {
            // Roster might need the live username if it somehow changed (rare).
            removeHideTeam(target.getUniqueId(), viewers);
            createHideTeam(target, viewers);
        }

        if (displayManager != null) {
            displayManager.update(target, fullText, force);
        }
    }

    public void resendTo(Player target, Player viewer) {
        PlayerState state = states.get(target.getUniqueId());
        if (state == null) {
            return;
        }
        createHideTeam(target, Collections.singletonList(viewer));
        if (displayManager != null) {
            displayManager.showExistingTo(viewer);
        }
    }

    /**
     * Full display string: the entire nametag-format resolved through
     * PlaceholderAPI with colors applied, rendered in full on the display
     * entity so colors work everywhere.
     */
    private String computeFullText(Player target) {
        return getEffectiveParsedFormat(target);
    }

    private String parse(Player target, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String result = placeholderApiAvailable ? PlaceholderAPI.setPlaceholders(target, raw) : raw;
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /**
     * The raw, unparsed format string currently in effect for {@code target}:
     * their per-player override from {@code /nametags format set} if one has
     * been set, otherwise the global {@code nametag-format} from config.yml.
     * Placeholders are left unresolved and {@code &} color codes untranslated
     * — exactly as stored. Backs {@code /nametags format view unparsed}.
     */
    public String getEffectiveRawFormat(Player target) {
        String override = formatStore.get(target.getUniqueId());
        return override != null ? override : config.getNametagFormat();
    }

    /**
     * Same as {@link #getEffectiveRawFormat(Player)} but resolved through
     * PlaceholderAPI with {@code &} colors translated — i.e. exactly what's
     * currently rendered above {@code target}'s head (falling back to their
     * plain username if the resolved format is blank, same as the tag itself
     * does). Backs {@code /nametags format view parsed}.
     */
    public String getEffectiveParsedFormat(Player target) {
        String parsed = parse(target, getEffectiveRawFormat(target));
        if (parsed == null || parsed.isBlank()) {
            return target.getName();
        }
        return parsed;
    }

    /**
     * Sets (or, if {@code format} is {@code null}, clears) a per-player
     * {@code nametag-format} override for {@code uuid}, replacing the global
     * format just for them, persists it to {@code player-formats.yml}, and
     * immediately refreshes their tag if they're online. Backs
     * {@code /nametags format set}.
     */
    public void setFormatOverride(UUID uuid, String format) {
        formatStore.set(uuid, format);
        Player target = Bukkit.getPlayer(uuid);
        if (target != null && target.isOnline()) {
            refresh(target, true);
        }
    }

    /**
     * Clears {@code uuid}'s per-player {@code nametag-format} override (if
     * any) from both memory and {@code player-formats.yml}, reverting them
     * to the global {@code nametag-format} from config.yml, and immediately
     * refreshes their tag if they're online. Backs
     * {@code /nametags format reset}.
     */
    public void resetFormatOverride(UUID uuid) {
        setFormatOverride(uuid, null);
    }

    // ------------------------------------------------------------------
    // Hide-vanilla-nametag teams (no prefix/suffix display)
    // ------------------------------------------------------------------

    private String teamName(UUID uuid) {
        String hex = Integer.toHexString(uuid.hashCode());
        return ("nt" + hex).substring(0, Math.min(15, ("nt" + hex).length()));
    }

    private WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo(
            WrapperPlayServerTeams.NameTagVisibility visibility) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                visibility,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                net.kyori.adventure.text.format.NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE
        );
    }

    private WrapperPlayServerTeams.ScoreBoardTeamInfo hideTeamInfo() {
        return teamInfo(WrapperPlayServerTeams.NameTagVisibility.NEVER);
    }

    /**
     * When {@code hide} is true (default), the vanilla overhead nametag is
     * suppressed via {@code NameTagVisibility.NEVER}. When false, visibility
     * is set to {@code ALWAYS} so the client renders the real player nametag
     * — including vanilla crouch translucency on Java.
     */
    public void setVanillaNametagHidden(Player target, boolean hide) {
        WrapperPlayServerTeams.NameTagVisibility vis = hide
                ? WrapperPlayServerTeams.NameTagVisibility.NEVER
                : WrapperPlayServerTeams.NameTagVisibility.ALWAYS;
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                teamName(target.getUniqueId()),
                WrapperPlayServerTeams.TeamMode.UPDATE,
                teamInfo(vis),
                Collections.<String>emptyList()
        );
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    private void createHideTeam(Player target, List<Player> viewers) {
        // Roster uses the real username so every client type matches.
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                teamName(target.getUniqueId()),
                WrapperPlayServerTeams.TeamMode.CREATE,
                hideTeamInfo(),
                target.getName()
        );
        for (Player viewer : viewers) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    private void removeHideTeam(UUID target, List<Player> viewers) {
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                teamName(target),
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                Collections.<String>emptyList()
        );
        for (Player viewer : viewers) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    private record PlayerState(String fullText) {
    }
}