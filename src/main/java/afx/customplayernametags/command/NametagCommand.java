package afx.customplayernametags.command;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.MessageManager;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import afx.customplayernametags.update.UpdateChecker;
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

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;
    private final NametagManager nametagManager;
    private final MessageManager messages;

    public NametagCommand(CustomPlayerNametags plugin, ConfigManager config, NametagManager nametagManager,
                          MessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.nametagManager = nametagManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "dismount" is console-only, unconditionally — it works no matter what
        // nametag-dismount-mode is set to (including NONE), since it's a manual
        // override rather than the automatic per-command dismounting.
        // Usage: /nametags dismount <player>
        // Always uses dismount-duration-ticks from config.yml, same value
        // AUTO/MANUAL mode dismounts use — there's no per-invocation override.
        if (args.length >= 1 && args[0].equalsIgnoreCase("dismount")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                messages.send(sender, "console-only");
                return true;
            }

            if (args.length != 2) {
                messages.send(sender, "dismount-usage", "{label}", label);
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-not-found");
                return true;
            }

            NametagDisplayManager displayManager = nametagManager.getDisplayManager();
            if (displayManager != null) {
                displayManager.dismount(target.getUniqueId(), config.getDismountDurationTicks());
            }
            return true;
        }

        if (!sender.hasPermission("customplayernametags.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            config.load();
            messages.load();
            nametagManager.stopRefreshTask();
            nametagManager.startRefreshTask();
            nametagManager.refreshAll();
            messages.send(sender, "reload-success");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
            UpdateChecker updateChecker = plugin.getUpdateChecker();
            if (updateChecker == null) {
                messages.send(sender, "update-checker-not-ready");
                return true;
            }
            messages.send(sender, "update-checking");
            // Always a fresh network check (unlike the cached result used for
            // the OP-join notice) — this is an explicit "check now" request.
            updateChecker.check(result -> sendUpdateCheckResult(sender, result));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("format")) {
            return handleFormatCommand(sender, label, args);
        }

        messages.sendList(sender, "usage-top-level", "{label}", label);
        return true;
    }

    /**
     * Handles the {@code /nametags format ...} family of subcommands. Every
     * one of {@code view}, {@code set}, and {@code reset} branches on a
     * {@code global} or {@code player} target:
     * <ul>
     *   <li>{@code /nametags format view global unparsed} — shows the raw
     *       global {@code nametag-format} from config.yml, exactly as
     *       stored (placeholders and {@code &} codes untouched).</li>
     *   <li>{@code /nametags format view global parsed <player>} — shows
     *       the global {@code nametag-format} resolved through
     *       PlaceholderAPI (using {@code player} as the placeholder
     *       context, since resolving player-only placeholders needs
     *       someone to resolve them against) with {@code &} colors
     *       applied.</li>
     *   <li>{@code /nametags format view player <unparsed|parsed> <player>}
     *       — shows either the raw or resolved form (caller's choice) of
     *       the format currently in effect for {@code player} (their
     *       per-player override from {@code format set player}, or else
     *       the global format).</li>
     *   <li>{@code /nametags format set global "<format>"} — sets the
     *       global {@code nametag-format}, persists it to config.yml, and
     *       refreshes every online player who has no per-player override.</li>
     *   <li>{@code /nametags format set player <player> "<format>"} — sets a
     *       per-player format override for {@code player}, replacing the
     *       global format just for them, and refreshes their tag immediately.</li>
     *   <li>{@code /nametags format reset global} — resets the global
     *       format back to the default (the player's plain username) and
     *       refreshes every online player who has no per-player override.</li>
     *   <li>{@code /nametags format reset player <player>} — clears
     *       {@code player}'s per-player format override, reverting them to
     *       the global {@code nametag-format}, and refreshes their tag
     *       immediately.</li>
     * </ul>
     */
    private boolean handleFormatCommand(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("view")) {
            if (args.length == 4 && args[2].equalsIgnoreCase("global") && args[3].equalsIgnoreCase("unparsed")) {
                messages.send(sender, "format-view-global-unparsed-header");
                sender.sendMessage(nametagManager.getGlobalRawFormat());
                return true;
            }

            if (args.length == 5 && args[2].equalsIgnoreCase("global") && args[3].equalsIgnoreCase("parsed")) {
                // Parsing needs a player context — PlaceholderAPI can't
                // resolve player-only placeholders otherwise.
                Player target = Bukkit.getPlayerExact(args[4]);
                if (target == null) {
                    messages.send(sender, "player-not-found");
                    return true;
                }

                messages.send(sender, "format-view-global-parsed-header", "{player}", target.getName());
                sender.sendMessage(nametagManager.getGlobalParsedFormat(target));
                return true;
            }

            if (args.length == 5 && args[2].equalsIgnoreCase("player")
                    && (args[3].equalsIgnoreCase("unparsed") || args[3].equalsIgnoreCase("parsed"))) {
                boolean parsed = args[3].equalsIgnoreCase("parsed");

                Player target = Bukkit.getPlayerExact(args[4]);
                if (target == null) {
                    messages.send(sender, "player-not-found");
                    return true;
                }

                String value = parsed
                        ? nametagManager.getEffectiveParsedFormat(target)
                        : nametagManager.getEffectiveRawFormat(target);

                messages.send(sender, "format-view-player-header",
                        "{player}", target.getName(),
                        "{type}", parsed ? "parsed" : "unparsed");
                // The "unparsed" value is stored with literal '&' codes and unresolved
                // placeholders, so sending it as-is shows it exactly as stored. The
                // "parsed" value already has '&' codes translated to real color codes
                // and placeholders resolved, so sending it as-is shows it exactly as
                // it renders above the player's head.
                sender.sendMessage(value);
                return true;
            }

            // "format view" was used, but with an unrecognized target/mode
            // combination — show just the "format view" usages, not every
            // "format" usage.
            messages.sendList(sender, "usage-format-view", "{label}", label);
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            if (args.length >= 3 && args[2].equalsIgnoreCase("global")) {
                String newFormat = joinAndUnquote(args, 3);
                if (newFormat.isEmpty()) {
                    messages.sendList(sender, "usage-format-set", "{label}", label);
                    return true;
                }

                config.setGlobalFormat(newFormat);
                nametagManager.refreshAll();
                messages.send(sender, "format-set-global-success");
                return true;
            }

            if (args.length >= 4 && args[2].equalsIgnoreCase("player")) {
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    messages.send(sender, "player-not-found");
                    return true;
                }

                String newFormat = joinAndUnquote(args, 4);
                if (newFormat.isEmpty()) {
                    messages.sendList(sender, "usage-format-set", "{label}", label);
                    return true;
                }

                nametagManager.setFormatOverride(target.getUniqueId(), newFormat);
                messages.send(sender, "format-set-player-success", "{player}", target.getName());
                return true;
            }

            // "format set" was used, but with an unrecognized target — show
            // just the "format set" usages.
            messages.sendList(sender, "usage-format-set", "{label}", label);
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (args.length == 3 && args[2].equalsIgnoreCase("global")) {
                config.resetGlobalFormat();
                nametagManager.refreshAll();
                messages.send(sender, "format-reset-global-success");
                return true;
            }

            if (args.length == 4 && args[2].equalsIgnoreCase("player")) {
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    messages.send(sender, "player-not-found");
                    return true;
                }

                nametagManager.resetFormatOverride(target.getUniqueId());
                messages.send(sender, "format-reset-player-success", "{player}", target.getName());
                return true;
            }

            // "format reset" was used, but with an unrecognized target —
            // show just the "format reset" usages.
            messages.sendList(sender, "usage-format-reset", "{label}", label);
            return true;
        }

        // "format" was used, but with an unrecognized (or missing)
        // subcommand — show just the "format" usages (view/set/reset),
        // not every leaf command underneath them.
        messages.sendList(sender, "usage-format", "{label}", label);
        return true;
    }

    /** Reports the outcome of an {@link UpdateChecker} run to whoever ran {@code /nametags update}. */
    private void sendUpdateCheckResult(CommandSender sender, UpdateChecker.Result result) {
        if (!result.isSuccess()) {
            messages.send(sender, "update-check-failed", "{reason}", result.getFailureReason());
            return;
        }
        if (result.isUpdateAvailable()) {
            messages.send(sender, "update-available",
                    "{version}", result.getLatestVersion(),
                    "{current}", plugin.getDescription().getVersion(),
                    "{url}", result.getReleaseUrl());
        } else {
            messages.send(sender, "update-up-to-date", "{current}", plugin.getDescription().getVersion());
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

        boolean isAdmin = sender.hasPermission("customplayernametags.admin");

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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("format") && isAdmin) {
            completions.add("view");
            completions.add("set");
            completions.add("reset");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("format") && isAdmin) {
            if (args[1].equalsIgnoreCase("view") || args[1].equalsIgnoreCase("set")
                    || args[1].equalsIgnoreCase("reset")) {
                completions.add("global");
                completions.add("player");
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("format") && isAdmin) {
            if (args[1].equalsIgnoreCase("view")
                    && (args[2].equalsIgnoreCase("global") || args[2].equalsIgnoreCase("player"))) {
                completions.add("unparsed");
                completions.add("parsed");
            } else if ((args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("reset"))
                    && args[2].equalsIgnoreCase("player")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    completions.add(online.getName());
                }
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("format") && args[1].equalsIgnoreCase("view")
                && isAdmin
                && ((args[2].equalsIgnoreCase("player")
                && (args[3].equalsIgnoreCase("unparsed") || args[3].equalsIgnoreCase("parsed")))
                || (args[2].equalsIgnoreCase("global") && args[3].equalsIgnoreCase("parsed")))) {
            // "global unparsed" takes no player arg, so it's excluded above.
            for (Player online : Bukkit.getOnlinePlayers()) {
                completions.add(online.getName());
            }
        }

        return completions;
    }
}