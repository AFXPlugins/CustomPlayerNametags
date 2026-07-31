package afx.customplayernametags.listener;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
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

import java.util.List;
import java.util.UUID;

public final class PlayerConnectionListener implements Listener {

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;
    private final NametagManager nametagManager;

    public PlayerConnectionListener(CustomPlayerNametags plugin, ConfigManager config, NametagManager nametagManager) {
        this.plugin = plugin;
        this.config = config;
        this.nametagManager = nametagManager;
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
     * from config, and the nametag automatically remounts after it expires.
     * None of this affects the console-only
     * {@code /nametags dismount <player> <ticks>} command, which always works.
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
     * When a player changes worlds, immediately remounts their nametag and
     * cancels/removes any active dismount timer instead of waiting for it
     * to expire. {@link NametagManager#refresh} respawns the display
     * entities (they can't follow a player across worlds), and that
     * respawn path — via {@link NametagDisplayManager#remove} — clears any
     * pending dismount window before immediately re-mounting the fresh
     * entities, so the tag never sits dismounted longer than the world
     * change itself.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        nametagManager.refresh(event.getPlayer(), true);
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
