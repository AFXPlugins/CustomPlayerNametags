package afx.customplayernametags.command;

import afx.customplayernametags.config.MessageManager;
import afx.customplayernametags.manager.NametagEditorManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

/**
 * {@code /nametag} — a player managing only their own individual nametag.
 * Purely a GUI entry point: every action (view current format, toggle
 * seeing others' nametags, edit whatever nametag format an admin currently
 * has applied to this player) lives in {@link NametagEditorManager}'s menu,
 * not as text subcommands here. Kept deliberately free of arguments so
 * there's no subcommand syntax to remember — running the command always
 * opens the menu.
 */
public final class PlayerNametagCommand implements CommandExecutor, TabCompleter {
    private final NametagEditorManager editor;
    private final MessageManager messages;

    public PlayerNametagCommand(NametagEditorManager editor, MessageManager messages) {
        this.editor = editor;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("customplayernametags.nametag")) {
            messages.send(player, "no-permission");
            return true;
        }
        editor.openMainMenu(player);
        return true;
    }

    /** No subcommands to complete — everything is driven by the GUI. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
