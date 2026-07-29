package afx.customplayernametags.command;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.manager.NametagManager;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public final class NametagCommand implements CommandExecutor, TabCompleter {

    private static final String NO_PERMISSION = ChatColor.translateAlternateColorCodes('&',
            "&cYou do not have permission to do that.");
    private static final String RELOAD_SUCCESS = ChatColor.translateAlternateColorCodes('&',
            "&aCustomPlayerNametags reloaded.");

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;
    private final NametagManager nametagManager;

    public NametagCommand(CustomPlayerNametags plugin, ConfigManager config, NametagManager nametagManager) {
        this.plugin = plugin;
        this.config = config;
        this.nametagManager = nametagManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nametag.admin")) {
            sender.sendMessage(NO_PERMISSION);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            config.load();
            nametagManager.stopRefreshTask();
            nametagManager.startRefreshTask();
            nametagManager.refreshAll();
            sender.sendMessage(RELOAD_SUCCESS);
            return true;
        }

        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&cUsage: /" + label + " reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("nametag.admin")) {
            completions.add("reload");
        }

        return completions;
    }
}