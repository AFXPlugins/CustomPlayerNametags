package afx.customplayernametags.command;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import afx.customplayernametags.update.UpdateChecker;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class NametagCommand implements CommandExecutor, TabCompleter {

    private static final String NO_PERMISSION = ChatColor.translateAlternateColorCodes('&',
            "&cYou do not have permission to do that.");
    private static final String RELOAD_SUCCESS = ChatColor.translateAlternateColorCodes('&',
            "&aCustomPlayerNametags reloaded.");
    private static final String PLAYER_NOT_FOUND = ChatColor.translateAlternateColorCodes('&',
            "&cThat player isn't online.");
    private static final String CONSOLE_ONLY = ChatColor.translateAlternateColorCodes('&',
            "&cThat subcommand is console-only.");

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
        // "dismount" is console-only, unconditionally — it works no matter what
        // nametag-dismount-mode is set to (including NONE), since it's a manual
        // override rather than the automatic per-command dismounting.
        // Usage: /nametags dismount <player> [ticks]
        // The <ticks> argument is optional — if omitted, dismount-duration-ticks
        // from config.yml is used instead, same value AUTO/MANUAL mode dismounts use.
        if (args.length >= 1 && args[0].equalsIgnoreCase("dismount")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage(CONSOLE_ONLY);
                return true;
            }

            if (args.length != 2 && args.length != 3) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&cUsage: /" + label + " dismount <player> [ticks]"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(PLAYER_NOT_FOUND);
                return true;
            }

            long durationTicks;
            if (args.length == 3) {
                try {
                    durationTicks = Long.parseLong(args[2]);
                    if (durationTicks < 0) {
                        durationTicks = 0;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&cInvalid tick value: " + args[2]));
                    return true;
                }
            } else {
                // No ticks given — fall back to the configured default duration.
                durationTicks = config.getDismountDurationTicks();
            }

            NametagDisplayManager displayManager = nametagManager.getDisplayManager();
            if (displayManager != null) {
                displayManager.dismount(target.getUniqueId(), durationTicks);
            }
            return true;
        }

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

        if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
            UpdateChecker updateChecker = plugin.getUpdateChecker();
            if (updateChecker == null) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&cThe update checker isn't ready yet — try again in a moment."));
                return true;
            }
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Checking Modrinth for updates..."));
            // Always a fresh network check (unlike the cached result used for
            // the OP-join notice) — this is an explicit "check now" request.
            updateChecker.check(result -> sendUpdateCheckResult(sender, result));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("format")) {
            return handleFormatCommand(sender, label, args);
        }

        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&eUsages:));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f/" + label + " reload &e- Reload the plugin."));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f/" + label + " update &e- Check plugin for updates."));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f/" + label + " format view <unparsed|parsed> <player> &e- View a player's nametag format."));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f/" + label + " format set <player> \"<format>\" &e- Set a player's nametag format."));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f/" + label + " format reset <player> &e- Reset a player's nametag format."));

        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f/" + label + " dismount <player> [ticks] &e- Dismount the player's nametag."));
        }
        return true;
    }

    /**
     * Handles the {@code /nametags format ...} family of subcommands:
     * <ul>
     *   <li>{@code /nametags format view unparsed <player>} — shows the raw
     *       format string currently in effect for {@code player} (their
     *       per-player override from {@code format set}, or else the global
     *       {@code nametag-format} from config.yml), with placeholders and
     *       {@code &} color codes left untouched, exactly as stored.</li>
     *   <li>{@code /nametags format view parsed <player>} — shows that same
     *       format resolved through PlaceholderAPI with colors applied,
     *       i.e. exactly what's currently rendered above the player's head.</li>
     *   <li>{@code /nametags format set <player> "<format>"} — sets a
     *       per-player format override for {@code player}, replacing the
     *       global format just for them, and refreshes their tag immediately.</li>
     *   <li>{@code /nametags format reset <player>} — clears {@code player}'s
     *       per-player format override, reverting them to the global
     *       {@code nametag-format} from config.yml, and refreshes their tag
     *       immediately.</li>
     * </ul>
     */
    private boolean handleFormatCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 4 && args[1].equalsIgnoreCase("view")
                && (args[2].equalsIgnoreCase("unparsed") || args[2].equalsIgnoreCase("parsed"))) {
            boolean parsed = args[2].equalsIgnoreCase("parsed");

            Player target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage(PLAYER_NOT_FOUND);
                return true;
            }

            String value = parsed
                    ? nametagManager.getEffectiveParsedFormat(target)
                    : nametagManager.getEffectiveRawFormat(target);

            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&e" + target.getName() + "&7's " + (parsed ? "parsed" : "unparsed") + " format:"));
            // The "unparsed" value is stored with literal '&' codes and unresolved
            // placeholders, so sending it as-is shows it exactly as stored. The
            // "parsed" value already has '&' codes translated to real color codes
            // and placeholders resolved, so sending it as-is shows it exactly as
            // it renders above the player's head.
            sender.sendMessage(value);
            return true;
        }

        if (args.length >= 4 && args[1].equalsIgnoreCase("set")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(PLAYER_NOT_FOUND);
                return true;
            }

            String newFormat = joinAndUnquote(args, 3);
            if (newFormat.isEmpty()) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&cUsage: /" + label + " format set <player> \"<format>\""));
                return true;
            }

            nametagManager.setFormatOverride(target.getUniqueId(), newFormat);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&aUpdated nametag format for &e" + target.getName() + "&a."));
            return true;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(PLAYER_NOT_FOUND);
                return true;
            }

            nametagManager.resetFormatOverride(target.getUniqueId());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&aReset &e" + target.getName() + "&a's nametag format back to the default."));
            return true;
        }

        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&cUsage: /" + label + " format view <unparsed|parsed> <player>"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&cUsage: /" + label + " format set <player> \"<format>\""));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&cUsage: /" + label + " format reset <player>"));
        return true;
    }

    /** Reports the outcome of an {@link UpdateChecker} run to whoever ran {@code /nametags update}. */
    private void sendUpdateCheckResult(CommandSender sender, UpdateChecker.Result result) {
        if (!result.isSuccess()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&cCould not check for updates: " + result.getFailureReason()));
            return;
        }
        if (result.isUpdateAvailable()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&eA new version is available: &av" + result.getLatestVersion()
                            + " &7(you're running &fv" + plugin.getDescription().getVersion() + "&7). &f"
                            + result.getReleaseUrl()));
        } else {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&aYou're already running the latest version (v"
                            + plugin.getDescription().getVersion() + ")."));
        }
    }

    /**
     * Joins {@code args[fromIndex..]} back into a single string (spaces
     * restored between tokens) and strips one layer of surrounding double
     * quotes if present, so {@code format set <player> "&6VIP &f{player}"}
     * — which Bukkit splits into several whitespace-separated args — is
     * recovered as a single format string.
     */
    private static String joinAndUnquote(String[] args, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        String joined = sb.toString().trim();
        if (joined.length() >= 2 && joined.startsWith("\"") && joined.endsWith("\"")) {
            joined = joined.substring(1, joined.length() - 1);
        }
        return joined;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        boolean isAdmin = sender.hasPermission("nametag.admin");

        if (args.length == 1) {
            if (isAdmin) {
                completions.add("reload");
                completions.add("update");
                completions.add("format");
            }
            if (sender instanceof ConsoleCommandSender) {
                completions.add("dismount");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dismount") && sender instanceof ConsoleCommandSender) {
            // Tab complete player names
            for (Player online : Bukkit.getOnlinePlayers()) {
                completions.add(online.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("dismount") && sender instanceof ConsoleCommandSender) {
            // Tab complete with example tick values (optional — omitting this
            // arg falls back to dismount-duration-ticks from config.yml)
            completions.add(String.valueOf(config.getDismountDurationTicks()));
            completions.add("0");
            completions.add("100");
            completions.add("300");
            completions.add("600");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("format") && isAdmin) {
            completions.add("view");
            completions.add("set");
            completions.add("reset");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("format") && isAdmin) {
            if (args[1].equalsIgnoreCase("view")) {
                completions.add("unparsed");
                completions.add("parsed");
            } else if (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("reset")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    completions.add(online.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("format") && args[1].equalsIgnoreCase("view") && isAdmin) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                completions.add(online.getName());
            }
        }

        return completions;
    }
}