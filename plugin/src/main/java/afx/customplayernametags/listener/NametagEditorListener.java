package afx.customplayernametags.listener;

import afx.customplayernametags.manager.NametagEditorManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Wires inventory clicks/closes in an open {@code /nametag editor} session to
 * {@link NametagEditorManager}. Text input (a text chunk) is handled by
 * Paper's native sign editor GUI directly, opened by the manager itself —
 * no anvil GUI is used anywhere in this plugin — so this listener forwards
 * inventory click/close events and sign-submit events, and never touches
 * chat.
 *
 * <p>Also cleans up a still-open session on quit, so a player who disconnects
 * mid-edit doesn't leave a dangling scheduled task.
 *
 * <p>Kept separate from {@link PlayerConnectionListener} — this listener's
 * only concern is the editor's own GUI plumbing, not the rest of that class's
 * join/quit/teleport nametag bookkeeping.
 */
public final class NametagEditorListener implements Listener {

    private final NametagEditorManager editorManager;

    public NametagEditorListener(NametagEditorManager editorManager) {
        this.editorManager = editorManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        editorManager.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        editorManager.handleInventoryClick(event);
    }

    /**
     * Cancelling {@link InventoryClickEvent} alone doesn't stop a click-drag
     * across the menu's slots — that fires {@link InventoryDragEvent}
     * instead, and an uncancelled one lets a player drop real items into a
     * menu slot, where they'd be silently destroyed when the menu closes.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        editorManager.handleInventoryDrag(event);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) editorManager.handleClose(player, event.getInventory());
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        editorManager.handleSignChange(event);
    }
}
