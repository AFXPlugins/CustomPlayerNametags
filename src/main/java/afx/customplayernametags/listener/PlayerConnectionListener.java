package afx.customplayernametags.listener;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerConnectionListener implements Listener {

    private final CustomPlayerNametags plugin;
    private final NametagManager nametagManager;

    public PlayerConnectionListener(CustomPlayerNametags plugin, NametagManager nametagManager) {
        this.plugin = plugin;
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
