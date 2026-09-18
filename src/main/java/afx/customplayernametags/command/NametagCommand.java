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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
        // Usage: /nametags dismount <player> [ticks]
        // The <ticks> argument is optional — if omitted, dismount-duration-ticks
        // from config.yml is used instead, same value AUTO/MANUAL mode dismounts use.
        if (args.length >= 1 && args[0].equalsIgnoreCase("dismount")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                messages.send(sender, "console-only");
                return true;
            }

            if (args.length != 2 && args.length != 3) {
                messages.send(sender, "dismount-usage", "{label}", label);
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-not-found");
                return true;
            }

            long durationTicks;
            if (args.length == 3) {
                try {
                    durationTicks = Long.parseLong(args[2]);
                    if (durationTicks < ConfigManager.MIN_DISMOUNT_DURATION_TICKS) {
                        durationTicks = ConfigManager.MIN_DISMOUNT_DURATION_TICKS;
                    }
                } catch (NumberFormatException e) {
                    messages.send(sender, "dismount-invalid-ticks", "{ticks}", args[2]);
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

        if (!sender.hasPermission("customplayernametags.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.updateConfigFile();
            config.load();
            messages.load();
            plugin.getGuiConfigManager().load();
            plugin.getPlayerFormatStore().load();
            nametagManager.getGroupFormatStore().load();
            plugin.getEditorPlaceholderStore().load();
            nametagManager.stopRefreshTask();
            nametagManager.startRefreshTask();
            nametagManager.refreshAll();
            // config.load() silently falls a malformed nametag-format/bedrock-nametag-format back
            // to the default rather than letting broken {widget...} tag text render on every
            // nametag — surfaced here so whoever ran the reload actually finds out about it.
            if (config.wasNametagFormatInvalidOnLastLoad()) {
                messages.send(sender, "format-invalid-fallback", "{key}", "nametag-format");
            }
            if (config.wasBedrockGlobalFormatInvalidOnLastLoad()) {
                messages.send(sender, "format-invalid-fallback", "{key}", "bedrock-nametag-format");
            }
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

        if (args.length == 1 && args[0].equalsIgnoreCase("editor")) {
            if (sender instanceof Player player) {
                plugin.getNametagEditorManager().openAdminGui(player);
            } else {
                messages.send(sender, "player-only");
            }
            return true;
        }


        if (args.length >= 1 && args[0].equalsIgnoreCase("config")) {
            return handleConfigCommand(sender, label, args);
        }

        messages.sendList(sender, "usage-top-level", "{label}", label);
        return true;
    }

    /**
     * Handles the {@code /nametags format ...} family of subcommands. Every
     * one of {@code set} and {@code reset} branches on a {@code global} or
     * {@code player} target:
     * <ul>
     *   <li>{@code /nametags format set global "<format>"} — sets the
     *       global {@code nametag-format}, persists it to config.yml, and
     *       refreshes every online player who has no per-player override.</li>
     *   <li>{@code /nametags format set player <player> "<format>"} — sets a
     *       per-player format override for {@code player}, replacing the
     *       global format just for them, and refreshes their tag immediately.</li>
     *   <li>{@code /nametags format global reset [java|bedrock]} — resets the
     *       global format (or the Bedrock global format, when
     *       {@code enable-separate-bedrock-global-format} is enabled) back
     *       to the default ({@code {player}}) and refreshes every online
     *       player who has no per-player override.</li>
     *   <li>{@code /nametags format player disable <player>} — clears
     *       {@code player}'s per-player format override, reverting them to
     *       the global {@code nametag-format}, and refreshes their tag
     *       immediately.</li>
     * </ul>
     */
    private boolean handleFormatCommand(CommandSender sender, String label, String[] args) {
        // Preferred concise syntax: /nametags format global set|reset|view [...]
        if (args.length >= 3 && args[1].equalsIgnoreCase("global")) {
            if (args[2].equalsIgnoreCase("reset")) {
                // Same java/bedrock split as "global set" — platform is required only when a
                // separate Bedrock global format is enabled.
                boolean separateReset = config.isSeparateBedrockGlobalFormatEnabled();
                int resetIdx = 3; boolean bedrockReset = false;
                if (separateReset) {
                    if (args.length <= resetIdx || (!args[resetIdx].equalsIgnoreCase("java") && !args[resetIdx].equalsIgnoreCase("bedrock"))) {
                        messages.sendList(sender, "usage-format", "{label}", label); return true;
                    }
                    bedrockReset = args[resetIdx++].equalsIgnoreCase("bedrock");
                }
                if (args.length != resetIdx) { messages.sendList(sender, "usage-format", "{label}", label); return true; }
                if (bedrockReset) { config.resetBedrockGlobalFormat(); } else { config.resetGlobalFormat(); }
                nametagManager.refreshAll(); messages.send(sender, "format-reset-global-success"); return true;
            }
            if (args[2].equalsIgnoreCase("set")) {
                // Requested syntax: global set [java|bedrock] <format>.
                // Platform is required only when a separate Bedrock global
                // format is enabled — see enable-separate-bedrock-global-format.
                boolean separateGlobal = config.isSeparateBedrockGlobalFormatEnabled();
                int idx = 3; boolean bedrock = false;
                if (separateGlobal) {
                    if (args.length <= idx || (!args[idx].equalsIgnoreCase("java") && !args[idx].equalsIgnoreCase("bedrock"))) {
                        messages.sendList(sender, "usage-format", "{label}", label); return true;
                    }
                    bedrock = args[idx++].equalsIgnoreCase("bedrock");
                }
                String value = joinAndUnquote(args, idx);
                if (value.isEmpty()) { messages.sendList(sender, "usage-format", "{label}", label); return true; }
                if (bedrock) { config.setBedrockGlobalFormat(value); } else { config.setGlobalFormat(value); }
                nametagManager.refreshAll(); messages.send(sender, "format-set-global-success"); return true;
            }
            if (args[2].equalsIgnoreCase("view")) {
                // Same java/bedrock split as "global set"/"global reset".
                boolean separateGlobalView = config.isSeparateBedrockGlobalFormatEnabled();
                int viewIdx = 3; boolean bedrockGlobalView = false;
                if (separateGlobalView) {
                    if (args.length <= viewIdx || (!args[viewIdx].equalsIgnoreCase("java") && !args[viewIdx].equalsIgnoreCase("bedrock"))) {
                        messages.sendList(sender, "usage-format", "{label}", label); return true;
                    }
                    bedrockGlobalView = args[viewIdx++].equalsIgnoreCase("bedrock");
                }
                Player context = args.length > viewIdx ? Bukkit.getPlayerExact(args[viewIdx]) : (sender instanceof Player p ? p : null);
                if (args.length > viewIdx && context == null) { messages.send(sender, "player-not-found"); return true; }
                String label2 = bedrockGlobalView ? "Global (Bedrock)" : "Global";
                String rawView = bedrockGlobalView ? nametagManager.getBedrockGlobalRawFormat() : nametagManager.getGlobalRawFormat();
                String parsedView = bedrockGlobalView ? nametagManager.getBedrockGlobalParsedFormat(context) : nametagManager.getGlobalParsedFormat(context);
                sendFormatView(sender, label2, rawView, parsedView, context);
                return true;
            }
        }
        // Preferred concise syntax: /nametags format player set|disable|view <player> [...]
        if (args.length >= 3 && args[1].equalsIgnoreCase("player")) {
            if (args[2].equalsIgnoreCase("disable") && args.length == 4) {
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) { messages.send(sender, "player-not-found"); return true; }
                nametagManager.resetFormatOverride(target.getUniqueId());
                notifyFormatDisabled(sender, target);
                messages.send(sender, "format-reset-player-success", "{player}", target.getName()); return true;
            }
            if (args[2].equalsIgnoreCase("set") && args.length >= 5) {
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) { messages.send(sender, "player-not-found"); return true; }
                Boolean announceOverride = extractNotifyOverride(args, 4);
                int formatStart = announceOverride != null ? 5 : 4;
                String value = joinAndUnquote(args, formatStart);
                if (value.isEmpty()) { messages.sendList(sender, "usage-format", "{label}", label); return true; }
                nametagManager.setFormatOverride(target.getUniqueId(), value);
                notifyFormatChanged(sender, target, announceOverride, value);
                messages.send(sender, "format-set-player-success", "{player}", target.getName()); return true;
            }
            if (args[2].equalsIgnoreCase("view") && args.length == 4) {
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) { messages.send(sender, "player-not-found"); return true; }
                sendFormatView(sender, target.getName(), nametagManager.getEffectiveRawFormat(target), nametagManager.getEffectiveParsedFormat(target), target);
                return true;
            }
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

                Boolean announceOverride = extractNotifyOverride(args, 4);
                int formatStart = announceOverride != null ? 5 : 4;
                String newFormat = joinAndUnquote(args, formatStart);
                if (newFormat.isEmpty()) {
                    messages.sendList(sender, "usage-format-set", "{label}", label);
                    return true;
                }

                nametagManager.setFormatOverride(target.getUniqueId(), newFormat);
                notifyFormatChanged(sender, target, announceOverride, newFormat);
                messages.send(sender, "format-set-player-success", "{player}", target.getName());
                return true;
            }

            // "format set" was used, but with an unrecognized target — show
            // just the "format set" usages.
            messages.sendList(sender, "usage-format-set", "{label}", label);
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("groups")) {
            return handleFormatGroupsCommand(sender, label, args);
        }

        // "format" was used, but with an unrecognized (or missing)
        // subcommand — show just the "format" usages (set/reset),
        // not every leaf command underneath them.
        messages.sendList(sender, "usage-format", "{label}", label);
        return true;
    }

    /**
     * Handles {@code /nametags format groups add/edit/remove/view [java|bedrock] <group> [format]}
     * — admin management of custom nametag/tablist formats assigned to
     * LuckPerms groups (see {@link afx.customplayernametags.config.GroupFormatStore}).
     * These sit in the format-resolution priority between per-player
     * overrides and the Bedrock/global formats: see
     * {@link NametagManager#getEffectiveRawFormat}.
     *
     * <ul>
     *   <li>{@code /nametags format groups add [java|bedrock] <group> <format>}
     *       — creates a new group format. Fails with
     *       {@code format-groups-add-exists} if that group (on that
     *       platform, when separate Bedrock group formats are enabled)
     *       already has one — use {@code edit} instead.</li>
     *   <li>{@code /nametags format groups edit [java|bedrock] <group> <format>}
     *       — updates an existing group format. Fails with
     *       {@code format-groups-edit-not-found} if that group doesn't
     *       have one yet — use {@code add} instead.</li>
     *   <li>{@code /nametags format groups remove [java|bedrock] <group>} —
     *       clears a group's format, reverting members of that group to
     *       whichever lower-priority format tier next applies to them.</li>
     * </ul>
     *
     * <p>This mirrors the {@code add}/{@code edit}/{@code remove} split
     * format-management command already uses, rather than the
     * single upsert-style {@code set} an earlier version of this command
     * had — explicit create-vs-update failure modes catch a typo'd group
     * name (an {@code edit} silently creating a new entry instead of
     * erroring) or an accidental overwrite ({@code add} silently replacing
     * an existing format) before either one takes effect.
     *
     * <p>The {@code [java|bedrock]} platform argument is only accepted (and
     * required) when {@code enable-separate-bedrock-group-formats} is
     * enabled in config.yml.
     *
     * <p>None of these require LuckPerms to actually be installed to be
     * configured (a server owner may set them up ahead of time), but a
     * warning is sent either way if it isn't, since the format simply
     * won't apply to anyone until it is.
     */
    private boolean handleFormatGroupsCommand(CommandSender sender, String label, String[] args) {
        // Requested syntax: groups view [java|bedrock] <group> [player].
        if (args.length >= 3 && args[2].equalsIgnoreCase("view")) {
            boolean separateView = config.isSeparateBedrockGroupFormatsEnabled();
            int idx = 3; boolean bedrockView = false;
            if (separateView) {
                if (args.length <= idx || (!args[idx].equalsIgnoreCase("java") && !args[idx].equalsIgnoreCase("bedrock"))) {
                    messages.sendList(sender, "usage-format-groups", "{label}", label); return true;
                }
                bedrockView = args[idx++].equalsIgnoreCase("bedrock");
            }
            if (args.length <= idx) { messages.sendList(sender, "usage-format-groups", "{label}", label); return true; }
            String groupView = args[idx++];
            var viewStore = nametagManager.getGroupFormatStore();
            if (!viewStore.contains(groupView, bedrockView)) { messages.send(sender, "format-groups-view-not-found", "{group}", groupView); return true; }
            Player viewContext = args.length > idx ? Bukkit.getPlayerExact(args[idx]) : (sender instanceof Player pv ? pv : null);
            if (args.length > idx && viewContext == null) { messages.send(sender, "player-not-found"); return true; }
            String rawGroupFormat = viewStore.get(groupView, bedrockView);
            sendFormatView(sender, "Group '" + groupView + "'", rawGroupFormat, nametagManager.parseFormat(viewContext, rawGroupFormat), viewContext);
            warnIfLuckPermsMissing(sender);
            return true;
        }
        // Requested syntax: groups add/edit/remove [java|bedrock] <group> [format].
        // Platform is required only when separate Bedrock group formats are enabled.
        if (args.length >= 3 && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("edit") || args[2].equalsIgnoreCase("remove"))) {
            boolean separate = config.isSeparateBedrockGroupFormatsEnabled();
            int index = 3; boolean bedrock = false;
            if (separate) {
                if (args.length <= index || (!args[index].equalsIgnoreCase("java") && !args[index].equalsIgnoreCase("bedrock"))) {
                    messages.sendList(sender, "usage-format-groups", "{label}", label); return true;
                }
                bedrock = args[index++].equalsIgnoreCase("bedrock");
            }
            if (args.length <= index) { messages.sendList(sender, "usage-format-groups", "{label}", label); return true; }
            String group = args[index++];
            var store = nametagManager.getGroupFormatStore();
            if (args[2].equalsIgnoreCase("remove")) {
                // Extra arguments after the group name are a usage mistake, not a
                // missing group — reporting "not found" for them was misleading.
                if (args.length != index) { messages.sendList(sender, "usage-format-groups", "{label}", label); return true; }
                if (!store.contains(group, bedrock)) { messages.send(sender, "format-groups-remove-not-found", "{group}", group); return true; }
                store.remove(group, bedrock); warnIfLuckPermsMissing(sender); nametagManager.refreshAll(); messages.send(sender, "format-groups-remove-success", "{group}", group); return true;
            }
            boolean add = args[2].equalsIgnoreCase("add");
            boolean alreadyExists = store.contains(group, bedrock);
            if (add && alreadyExists) { messages.send(sender, "format-groups-add-exists", "{group}", group); return true; }
            if (!add && !alreadyExists) { messages.send(sender, "format-groups-edit-not-found", "{group}", group); return true; }
            String value = joinAndUnquote(args, index);
            if (value.isEmpty()) { messages.sendList(sender, "usage-format-groups", "{label}", label); return true; }
            store.set(group, value, bedrock); warnIfLuckPermsMissing(sender); nametagManager.refreshAll();
            messages.send(sender, add ? "format-groups-add-success" : "format-groups-edit-success", "{group}", group); return true;
        }

        messages.sendList(sender, "usage-format-groups", "{label}", label);
        return true;
    }

    /** Sends a warning that group formats won't apply to anyone yet, if LuckPerms isn't installed. */
    private void warnIfLuckPermsMissing(CommandSender sender) {
        if (!nametagManager.isLuckPermsAvailable()) {
            messages.send(sender, "format-groups-luckperms-missing");
        }
    }

    /**
     * Sends both the raw (unparsed) and the resolved (parsed) form of a
     * format to {@code sender} — used by every {@code /nametags format
     * .../view} variant so a single command always shows both, as opposed
     * to a caller having to separately ask for "unparsed" then "parsed".
     *
     * @param label       what to call this format in the header (e.g.
     *                    {@code "Global"}, a player's name, or
     *                    {@code "Group 'vip'"}).
     * @param parseContext the player {@code parsed} was actually resolved
     *                    against, if any — mentioned in the parsed line's
     *                    header so it's clear whose placeholder values are
     *                    shown, since the same format can render
     *                    differently per player.
     */
    private void sendFormatView(CommandSender sender, String label, String raw, String parsed, Player parseContext) {
        sender.sendMessage(org.bukkit.ChatColor.GOLD + label + " — unparsed:");
        sender.sendMessage(raw == null || raw.isEmpty() ? org.bukkit.ChatColor.GRAY + "(empty)" : raw);
        sender.sendMessage(org.bukkit.ChatColor.GOLD + label + " — parsed"
                + (parseContext != null ? " (as seen by " + parseContext.getName() + ")" : " (no player context — player-only placeholders may be blank)")
                + ":");
        sender.sendMessage(afx.customplayernametags.format.NametagFormatter.toComponent(parsed == null || parsed.isEmpty() ? "" : parsed));
    }

    /**
     * Handles the {@code /nametags config} family — admin, in-game viewing
     * and editing of the subset of {@code config.yml} values registered as
     * runtime-configurable in {@link ConfigManager} (see
     * {@link ConfigManager#getConfigurableKeys()}). Values that shouldn't
     * safely be changed at runtime (e.g. {@code dismount-commands}, a list,
     * or the internal {@code config-version} marker) are simply never
     * registered there, so they can never be reached from this command.
     *
     * <ul>
     *   <li>{@code /nametags config} — lists every configurable key with
     *       its current value.</li>
     *   <li>{@code /nametags config <key>} — shows just that key's current
     *       value.</li>
     *   <li>{@code /nametags config <key> <new value>} — parses and applies
     *       {@code <new value>} for {@code <key>}, persists it to
     *       config.yml, and immediately refreshes every online player's
     *       nametag (and restarts the placeholder-refresh task, in case the
     *       key that changed was one of the two placeholder-refresh
     *       settings) so the change takes effect without a server
     *       restart.</li>
     * </ul>
     */
    private boolean handleConfigCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            messages.send(sender, "config-list-header");
            for (String key : config.getConfigurableKeys()) {
                ConfigManager.ConfigField field = config.getConfigField(key);
                messages.send(sender, "config-list-entry", "{key}", key, "{value}", field.getter().get());
            }
            return true;
        }

        String key = args[1];
        ConfigManager.ConfigField field = config.getConfigField(key);
        if (field == null) {
            messages.send(sender, "config-key-not-found", "{key}", key);
            return true;
        }

        if (args.length == 2) {
            messages.send(sender, "config-list-entry", "{key}", field.key(), "{value}", field.getter().get());
            return true;
        }

        String newValue = joinAndUnquote(args, 2);
        if (newValue.isEmpty()) {
            messages.send(sender, "config-invalid-value", "{key}", field.key(), "{hint}", configValueHint(field));
            return true;
        }

        if (!config.setConfigValue(field.key(), newValue)) {
            messages.send(sender, "config-invalid-value", "{key}", field.key(), "{hint}", configValueHint(field));
            return true;
        }

        // Refresh the plugin: restarts the placeholder-refresh task (in
        // case the two placeholder-refresh settings themselves just
        // changed) and re-renders every online player's nametag/tablist
        // entry immediately, the same as /nametags reload does.
        nametagManager.stopRefreshTask();
        nametagManager.startRefreshTask();
        nametagManager.refreshAll();

        messages.send(sender, "config-set-success", "{key}", field.key(), "{value}", field.getter().get());
        return true;
    }

    /** A short human-readable hint of what {@code field} accepts, for an invalid-value error message. */
    private static String configValueHint(ConfigManager.ConfigField field) {
        return switch (field.type()) {
            case BOOLEAN -> "true or false";
            case ENUM -> String.join(", ", field.enumValues());
            case INTEGER -> "a whole number";
            case LONG -> "a whole number";
            case DOUBLE -> "a number";
            case STRING -> "text";
        };
    }

    // NOTE: /nametags toggle used to live here. It's now /nametag toggle
    // (see PlayerNametagCommand) — a per-player preference belongs with the
    // rest of a player's own /nametag commands, not the admin/general
    // /nametags command.

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
        return joinAndUnquote(args, fromIndex, args.length);
    }

    private static String joinAndUnquote(String[] args, int fromIndex, int toIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < toIndex; i++) {
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

    /**
     * Reads an optional {@code announce}/{@code silent} argument placed
     * right after the target player's name — before the format itself — in
     * a {@code /nametags format player set}-style command, e.g.
     * {@code /nametags format player set <player> silent <format>}. Only
     * meaningful when {@code nametag-format-notify-mode} is
     * {@link ConfigManager.NotifyMode#BOTH}, since that word only exists in
     * that mode. Returns {@code null} (no override — fall back to the
     * config's default behavior) if the mode isn't BOTH, or the argument at
     * {@code index} isn't one of those two words.
     */
    private Boolean extractNotifyOverride(String[] args, int index) {
        if (config.getNotifyMode() != ConfigManager.NotifyMode.BOTH || index < 0 || index >= args.length) {
            return null;
        }
        String value = args[index];
        if (value.equalsIgnoreCase("announce")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("silent")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Tells {@code target} in chat that their individual nametag format was
     * just changed by someone else, per {@code nametag-format-notify-mode}
     * (see {@link ConfigManager.NotifyMode}) — unless {@code target} has one
     * of the {@code customplayernametags.notify.override.*} permissions
     * explicitly set, which always wins over both the config default and
     * (in BOTH mode) the command's own announce/silent argument.
     */
    private void notifyFormatChanged(CommandSender sender, Player target, Boolean announceOverride, String newRawFormat) {
        if (target.getUniqueId().equals(sender instanceof Player p ? p.getUniqueId() : null)) {
            return; // A player changing their own format doesn't need to be told about it.
        }
        ConfigManager.NotifyMode mode = config.getNotifyMode();
        boolean notify = switch (mode) {
            case SILENT -> false;
            case BOTH -> announceOverride == null || announceOverride;
            case NOTIFY -> true;
        };
        if (target.isPermissionSet("customplayernametags.notify.override.silent")
                && target.hasPermission("customplayernametags.notify.override.silent")) {
            notify = false;
        } else if (target.isPermissionSet("customplayernametags.notify.override.notify")
                && target.hasPermission("customplayernametags.notify.override.notify")) {
            notify = true;
        }
        if (notify) {
            target.sendMessage(nametagManager.buildFormatChangedNotify(config, sender, target, newRawFormat));
        }
    }

    /**
     * Tells {@code target} their individual nametag format override was
     * just cleared by {@code sender} (via {@code /nametags format player
     * disable}), and whether they landed back on their group's format or
     * the plain global one. Mirrors {@link #notifyFormatChanged}'s
     * self-change skip and {@code nametag-format-notify-mode} handling;
     * unlike {@code set}, {@code disable} has no per-command
     * announce/silent argument, so in {@code BOTH} mode this always
     * notifies.
     */
    private void notifyFormatDisabled(CommandSender sender, Player target) {
        if (target.getUniqueId().equals(sender instanceof Player p ? p.getUniqueId() : null)) {
            return; // A player disabling their own format doesn't need to be told about it.
        }
        ConfigManager.NotifyMode mode = config.getNotifyMode();
        boolean notify = mode != ConfigManager.NotifyMode.SILENT;
        if (target.isPermissionSet("customplayernametags.notify.override.silent")
                && target.hasPermission("customplayernametags.notify.override.silent")) {
            notify = false;
        } else if (target.isPermissionSet("customplayernametags.notify.override.notify")
                && target.hasPermission("customplayernametags.notify.override.notify")) {
            notify = true;
        }
        if (notify) {
            boolean group = nametagManager.hasGroupFormat(target);
            String type = group ? "group" : "global";
            String newRawFormat = nametagManager.getEffectiveRawFormat(target);
            target.sendMessage(nametagManager.buildFormatDisabledNotify(config, sender, target, type, newRawFormat));
        }
    }

    /**
     * The group names worth suggesting for the group-name argument of
     * {@code /nametags format groups <action> ...}, for a given platform.
     * {@code add} only makes sense on a group that doesn't have a stored
     * format yet, so it's suggested LuckPerms groups minus ones already in
     * the store; {@code edit}/{@code remove}/{@code view} only make sense
     * on a group that already has one, so it's the inverse — exactly the
     * store's own group list for that platform.
     */
    private List<String> groupNameCompletions(String action, boolean bedrock) {
        if (action.equalsIgnoreCase("add")) {
            List<String> existing = nametagManager.getGroupFormatStore().getGroupNames(bedrock);
            List<String> suggestions = new ArrayList<>();
            for (String group : nametagManager.getLuckPermsGroupNames()) {
                if (!existing.contains(group.toLowerCase(Locale.ROOT))) {
                    suggestions.add(group);
                }
            }
            return suggestions;
        }
        return nametagManager.getGroupFormatStore().getGroupNames(bedrock);
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
                completions.add("editor");
                completions.add("config");
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
            // Suggest the configured default so it's obvious what omitting this
            // arg falls back to (dismount-duration-ticks from config.yml)
            completions.add(String.valueOf(config.getDismountDurationTicks()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("config") && isAdmin) {
            completions.addAll(config.getConfigurableKeys());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("config") && isAdmin) {
            ConfigManager.ConfigField field = config.getConfigField(args[1]);
            if (field != null) {
                switch (field.type()) {
                    case BOOLEAN -> {
                        completions.add("true");
                        completions.add("false");
                    }
                    case ENUM -> completions.addAll(field.enumValues());
                    default -> completions.add(field.getter().get());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("format") && isAdmin) {
            completions.add("player");
            completions.add("global");
            completions.add("groups");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("format") && isAdmin) {
            if (args[1].equalsIgnoreCase("player")) {
                completions.add("set");
                completions.add("disable");
                completions.add("view");
            } else if (args[1].equalsIgnoreCase("global")) {
                completions.add("set");
                completions.add("reset");
                completions.add("view");
            } else if (args[1].equalsIgnoreCase("groups")) {
                completions.add("add");
                completions.add("edit");
                completions.add("remove");
                completions.add("view");
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("format") && isAdmin) {
            if (args[1].equalsIgnoreCase("player")
                    && (args[2].equalsIgnoreCase("set") || args[2].equalsIgnoreCase("disable") || args[2].equalsIgnoreCase("view"))) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    completions.add(online.getName());
                }
            } else if (args[1].equalsIgnoreCase("global")
                    && (args[2].equalsIgnoreCase("set") || args[2].equalsIgnoreCase("reset") || args[2].equalsIgnoreCase("view"))
                    && config.isSeparateBedrockGlobalFormatEnabled()) {
                // Platform is only a real argument to suggest here when the
                // separate Bedrock global format is actually enabled —
                // matching what handleFormatCommand's "global
                // set"/"reset"/"view" branches all now require/accept.
                completions.add("java");
                completions.add("bedrock");
            } else if (args[1].equalsIgnoreCase("groups")
                    && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("edit")
                    || args[2].equalsIgnoreCase("remove") || args[2].equalsIgnoreCase("view"))) {
                if (config.isSeparateBedrockGroupFormatsEnabled()) {
                    // Platform comes before the group name for every one of
                    // add/edit/remove/view once separate Bedrock group
                    // formats are enabled — the group name itself moves to
                    // the next argument (see the length == 5 branch below).
                    completions.add("java");
                    completions.add("bedrock");
                } else {
                    completions.addAll(groupNameCompletions(args[2], false));
                }
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("format") && args[1].equalsIgnoreCase("global")
                && isAdmin && args[2].equalsIgnoreCase("view")
                && (args[3].equalsIgnoreCase("java") || args[3].equalsIgnoreCase("bedrock"))) {
            // "global view java|bedrock <player>" — same optional player-context
            // argument "global view <player>" gets without a platform in front.
            for (Player online : Bukkit.getOnlinePlayers()) {
                completions.add(online.getName());
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("format") && args[1].equalsIgnoreCase("groups")
                && isAdmin && config.isSeparateBedrockGroupFormatsEnabled()
                && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("edit")
                || args[2].equalsIgnoreCase("remove") || args[2].equalsIgnoreCase("view"))
                && (args[3].equalsIgnoreCase("java") || args[3].equalsIgnoreCase("bedrock"))) {
            // Same suggestions as the length == 4, separate-Bedrock-disabled
            // case above, just one argument further along to make room for
            // the [java|bedrock] platform argument that preceded the group
            // name here.
            completions.addAll(groupNameCompletions(args[2], args[3].equalsIgnoreCase("bedrock")));
        }

        return filterByPrefix(completions, args.length == 0 ? "" : args[args.length - 1]);
    }

    /**
     * Narrows {@code completions} to the ones actually starting with what the
     * sender has typed so far. Bukkit hands a plugin's tab completer the raw
     * argument list and sends whatever it returns straight to the client
     * without filtering, so without this every suggestion for a position
     * keeps showing no matter how much of one has already been typed.
     */
    private static List<String> filterByPrefix(List<String> completions, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return completions;
        }
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(completions.size());
        for (String completion : completions) {
            if (completion != null && completion.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                matches.add(completion);
            }
        }
        return matches;
    }
}
