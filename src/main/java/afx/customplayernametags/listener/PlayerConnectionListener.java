package afx.customplayernametags.listener;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.MessageManager;
import afx.customplayernametags.manager.MultiverseIntegration;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import afx.customplayernametags.update.UpdateChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.List;
import java.util.UUID;

public final class PlayerConnectionListener implements Listener {

    private static final String UPDATE_NOTIFY_PERMISSION = "customplayernametags.updatenotify";
    private static final String MULTIVERSE_NOTIFY_PERMISSION = "customplayernametags.multiversenotify";
    private static final String MULTIVERSE_PASSENGER_MODE_FIX_COMMAND =
            "/mv config passenger-mode dismount_passengers";
    /**
     * Delay, in ticks, before the Multiverse passenger-mode join notice is sent — after the
     * update notice and clear of the initial burst of other join-time messages/events, so it
     * doesn't get lost among them and reads as its own, separate notice to the admin.
     */
    private static final long MULTIVERSE_NOTIFY_DELAY_TICKS = 5L;

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;
    private final NametagManager nametagManager;
    private final MessageManager messages;

    public PlayerConnectionListener(CustomPlayerNametags plugin, ConfigManager config, NametagManager nametagManager,
                                    MessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.nametagManager = nametagManager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();

        // One tick for LuckPerms/Essentials/PAPI; a bit longer helps slow expansions.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joined.isOnline()) {
                return;
            }
            applyJoinNametags(joined);
        }, 2L);

        // Second pass for late-loading placeholder data.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joined.isOnline()) {
                return;
            }
            nametagManager.refresh(joined, true);
        }, 40L);

        notifyOfUpdate(joined);

        // Deferred a few ticks so it doesn't compete with everything else already
        // firing at the instant of join (the update notice above, other plugins'
        // own join messages, etc.) and reads as its own, separate notice.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joined.isOnline()) {
                return;
            }
            notifyOfMultiversePassengerMode(joined);
        }, MULTIVERSE_NOTIFY_DELAY_TICKS);
    }

    /**
     * Messages a player on join if a newer plugin version is already known
     * to be available. Gated by {@link #UPDATE_NOTIFY_PERMISSION} rather
     * than admin/OP status, so who gets pinged about updates can be
     * configured separately from who can run admin commands. Uses whatever
     * {@link UpdateChecker} last found (from the startup check, or a
     * since-run {@code /nametags update}) rather than firing a fresh
     * Modrinth request for every join — that result is cached specifically
     * so this stays free.
     */
    private void notifyOfUpdate(Player joined) {
        if (!joined.hasPermission(UPDATE_NOTIFY_PERMISSION)) {
            return;
        }
        UpdateChecker updateChecker = plugin.getUpdateChecker();
        if (updateChecker == null) {
            return;
        }
        UpdateChecker.Result result = updateChecker.getLastResult();
        if (result == null || !result.isUpdateAvailable()) {
            return;
        }
        messages.send(joined, "update-notice",
                "{version}", result.getLatestVersion(),
                "{current}", plugin.getDescription().getVersion(),
                "{url}", result.getReleaseUrl());
    }

    /**
     * Warns an admin on join if Multiverse-Portals is installed, Multiverse-Core's
     * {@code teleport.passenger-mode} config value is still the out-of-the-box
     * {@code default} setting, which can let a nametag's passenger entity block
     * Multiverse-Portals from teleporting a player instead of this plugin's own
     * dismount handling (see {@link #onCommand} and {@link #onTeleport}) taking
     * care of it first. Multiverse-Portals is what's actually affected by this,
     * so the notice is skipped entirely on a server running Multiverse-Core
     * without it. The message ends with a separate clickable line: clicking it
     * runs {@value #MULTIVERSE_PASSENGER_MODE_FIX_COMMAND} as the admin
     * themselves (not the console), so it only succeeds if they actually hold
     * Multiverse-Core's {@code multiverse.core.config} permission — exactly as
     * if they'd typed the command by hand.
     *
     * <p>Gated by {@link #MULTIVERSE_NOTIFY_PERMISSION} rather than
     * admin/OP status, matching {@link #notifyOfUpdate}, so who gets
     * pinged about this can be configured independently of who can run
     * this plugin's own admin commands. Also gated by
     * {@code notify-multiverse-passenger-mode-default} in config.yml
     * (default {@code true}), so a server owner can turn this notice off
     * entirely — e.g. once they've already fixed it or deliberately want
     * {@code passenger-mode} left at {@code default}.
     */
    private void notifyOfMultiversePassengerMode(Player joined) {
        if (!config.isNotifyMultiversePassengerModeDefault()) {
            return;
        }
        if (!joined.hasPermission(MULTIVERSE_NOTIFY_PERMISSION)) {
            return;
        }
        if (!MultiverseIntegration.isPortalsAvailable()) {
            return;
        }
        if (!MultiverseIntegration.isPassengerModeDefault()) {
            return;
        }

        // MessageManager#get() already runs '&' color codes through
        // ChatColor.translateAlternateColorCodes, so what comes back here
        // uses real '§' section-sign codes — legacySection(), not
        // legacyAmpersand(), is the serializer that understands those.
        Component notice = LegacyComponentSerializer.legacySection()
                .deserialize(messages.get("multiverse-passenger-mode-notice"));
        Component click = LegacyComponentSerializer.legacySection()
                .deserialize(messages.get("multiverse-passenger-mode-click"))
                .clickEvent(ClickEvent.runCommand(MULTIVERSE_PASSENGER_MODE_FIX_COMMAND))
                .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacySection()
                        .deserialize(messages.get("multiverse-passenger-mode-click-hover"))));

        joined.sendMessage(notice);
        joined.sendMessage(click);
    }

    private void applyJoinNametags(Player joined) {
        nametagManager.refresh(joined, true);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(joined)) {
                nametagManager.resendTo(joined, viewer);
            }
        }
        for (Player existing : Bukkit.getOnlinePlayers()) {
            if (!existing.equals(joined)) {
                nametagManager.resendTo(existing, joined);
            }
        }

        NametagDisplayManager displayManager = nametagManager.getDisplayManager();
        if (displayManager != null) {
            displayManager.showExistingTo(joined);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nametagManager.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Intercepts all player commands at the lowest priority and, depending
     * on {@code nametag-dismount-mode}, automatically dismounts their
     * nametag to prevent the passenger entity from blocking command
     * execution (especially teleportation):
     *
     * <ul>
     *   <li>{@code NONE} — never automatically dismounts; {@code dismount-commands}
     *       is ignored entirely.</li>
     *   <li>{@code AUTO} — dismounts on every command the player runs.</li>
     *   <li>{@code MANUAL} — only dismounts when the command matches an
     *       entry in {@code dismount-commands} (including subcommands, e.g.
     *       a configured {@code "mv tp"} entry matches {@code "/mv tp world"}).</li>
     * </ul>
     *
     * <p>In every mode the dismount duration is {@code dismount-duration-ticks}
     * from config.yml, and the nametag automatically remounts after it
     * expires (or immediately on an actual world change, whichever comes
     * first). None of this affects the console-only
     * {@code /nametags dismount <player>} command, which always works.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        NametagDisplayManager displayManager = nametagManager.getDisplayManager();
        if (displayManager == null) {
            return;
        }

        ConfigManager.DismountMode mode = config.getNametagDismountMode();
        if (mode == ConfigManager.DismountMode.NONE) {
            return;
        }

        List<String> tokens = ConfigManager.tokenizeCommand(event.getMessage());
        if (tokens.isEmpty()) {
            return;
        }

        boolean shouldDismount = mode == ConfigManager.DismountMode.AUTO
                || config.matchesDismountCommand(tokens);
        if (!shouldDismount) {
            return;
        }

        UUID uuid = event.getPlayer().getUniqueId();
        displayManager.dismount(uuid, config.getDismountDurationTicks());
    }

    /**
     * Dismounts a player's nametag before any teleport is actually carried
     * out, not just ones triggered by a typed command. {@link #onCommand}
     * only sees {@code PlayerCommandPreprocessEvent}, so it never fires for
     * nether/end portals or for other plugins calling
     * {@code Player#teleport(...)} directly via the API — in both cases the
     * nametag's passenger entity is still attached when Bukkit processes
     * the teleport, and a stale passenger reference is enough to silently
     * block a cross-world move. Handling {@link PlayerTeleportEvent} here
     * (regardless of cause) closes that gap the same way {@code onCommand}
     * does for commands. Uses {@code EventPriority.LOWEST} so this runs
     * before other plugins react to the event.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        NametagDisplayManager displayManager = nametagManager.getDisplayManager();
        if (displayManager == null) {
            return;
        }
        if (config.getNametagDismountMode() == ConfigManager.DismountMode.NONE) {
            return;
        }
        displayManager.dismount(event.getPlayer().getUniqueId(), config.getDismountDurationTicks());
    }

    /**
     * When a player changes worlds, remounts their nametag and
     * cancels/removes any active dismount timer instead of waiting for it
     * to expire. {@link NametagManager#refresh} respawns the display
     * entities (they can't follow a player across worlds), and that
     * respawn path — via {@link NametagDisplayManager#remove} — clears any
     * pending dismount window before re-mounting the fresh entities, so the
     * tag never sits dismounted longer than the world change itself.
     *
     * <p>Deferred to the next tick (matching {@link #onRespawn}) rather
     * than run synchronously inside the event: the fresh displays'
     * visibility is applied via {@code Player#showEntity} for every other
     * online player, which only takes effect once the server's entity
     * tracker has actually caught up to the just-arrived player in their
     * destination world. Doing that in the same tick as the world-change
     * event races the tracker and silently no-ops for viewers already
     * present in that world — the tag then stayed invisible to them until
     * something else (e.g. the owner crouching) re-ran the same visibility
     * pass a moment later. One tick is enough for the tracker to catch up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                nametagManager.refresh(player, true);
            }
        });
    }

    /**
     * Reacts to a crouch/uncrouch the instant the client's toggle packet is
     * processed, instead of waiting for {@link NametagDisplayManager}'s
     * once-a-tick poll ({@code tickMaintain}) to notice
     * {@code player.isSneaking()} changed on its own. Both paths end up
     * calling the exact same {@link NametagManager#refresh} ->
     * {@link NametagDisplayManager#update} -> {@code applySneakState}
     * pipeline; this just fires it the moment the event is handled rather
     * than on {@code tickMaintain}'s next pass, shaving off up to one full
     * tick (50ms) of pure server-side polling latency before the
     * height/opacity/text change goes out. {@code ignoreCancelled = true}
     * skips this if another plugin cancelled the toggle, since the
     * player's pose isn't actually changing in that case (letting
     * {@code tickMaintain} stay the single source of truth for that
     * outcome).
     *
     * <p>Note: this closes the gap on the plugin's own detection latency
     * only. For Bedrock/Geyser viewers specifically, Geyser does not
     * forward a Display entity's {@code interpolation_delay} /
     * {@code interpolation_duration} metadata to the Bedrock client at all
     * (those translators are no-ops in Geyser's entity definitions — only
     * the raw translation vector is forwarded), so any remaining smoothing
     * a Bedrock player sees on the height change is Geyser/Bedrock-side
     * client interpolation of that translation update, not something this
     * plugin's {@code snapBedrockHeight} (which already sets both to 0) can
     * influence further.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        // Deferred by a tick on purpose: the server only applies the new
        // pose after this event finishes, so Player#isSneaking() — which
        // the whole refresh -> update -> applySneakState path reads — still
        // reports the OLD value while the handler is running. Refreshing
        // synchronously here would therefore re-render the tag in the pose
        // it was already in and change nothing, leaving tickMaintain's poll
        // to catch the real change a tick later anyway.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                nametagManager.refresh(player, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                nametagManager.refresh(player, true);
            }
        });
    }
}