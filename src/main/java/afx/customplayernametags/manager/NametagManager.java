package afx.customplayernametags.manager;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.GroupFormatStore;
import afx.customplayernametags.config.NametagVisibilityStore;
import afx.customplayernametags.config.PlayerFormatStore;
import afx.customplayernametags.config.PlayerWidgetFillStore;
import afx.customplayernametags.format.NametagFormatPermissions;
import afx.customplayernametags.format.NametagFormatter;
import afx.customplayernametags.format.TemplateMarkers;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
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

    /** The plain default nametag format — just the player's own username, no custom markup. */
    private static final String DEFAULT_FORMAT = "{player}";

    private final CustomPlayerNametags plugin;
    private final ConfigManager config;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    /** Admin-hidden player nametags, controlled by /nametags hide <player>. */
    private final java.util.Set<UUID> hiddenTargets = ConcurrentHashMap.newKeySet();
    /**
     * Backs per-player {@code nametag-format} overrides set via
     * {@code /nametags format set <player> "<format>"}. Persisted to
     * {@code player-formats.yml} so overrides survive server restarts. A
     * player with no entry here just uses the global {@code nametag-format}
     * from config.yml, same as before this existed.
     */
    private final PlayerFormatStore formatStore;
    /**
     * Backs a player's Widget fill-ins collected through the "Edit
     * Nametag" flow ({@code Target.PLAYER_EDIT} in
     * {@link NametagEditorManager}) — kept entirely separate from
     * {@link #formatStore}'s explicit per-player overrides so that filling
     * a widget in an inherited global/group format never forks the player
     * onto a frozen copy of it, and so that {@link #resetFormatOverride}
     * (which only ever clears an explicit override) can't accidentally
     * wipe out widget customization the player never asked to lose. See
     * {@link #getEffectiveRawFormat} (overlay) and
     * {@link #saveWidgetFills}.
     */
    private final PlayerWidgetFillStore widgetFillStore;
    /**
     * Backs {@code /nametags format groups create/edit/remove} — custom
     * nametag/tablist formats assigned to LuckPerms groups. Persisted to
     * {@code group-formats.yml}, kept separate from both
     * {@code config.yml} and {@code player-formats.yml}. A player with no
     * per-player override falls through to whichever of their LuckPerms
     * groups has the highest weight and a stored entry here (see
     * {@link #resolveGroupFormat}), before falling further through to the
     * Bedrock/global format tiers.
     */
    private final GroupFormatStore groupFormatStore;
    /**
     * Backs {@code /nametag toggle} — which players currently have other
     * players' nametags hidden from their own view. Persisted to
     * {@code storage/nametag-toggles.db} so the setting survives restarts.
     */
    private final NametagVisibilityStore visibilityStore;
    private BukkitTask refreshTask;
    private NametagDisplayManager displayManager;

    /**
     * Whether the PlaceholderAPI plugin is present and enabled. PlaceholderAPI
     * is a soft dependency — if it's missing, an individual per-player
     * format override still renders (see {@link #getEffectiveRawFormat}),
     * with the built-in {@code {player}} placeholder resolved and any
     * {@code %placeholder%} left unparsed (shown as literal text) instead of
     * throwing {@link NoClassDefFoundError}. Group and global custom
     * formats, however, only apply while PlaceholderAPI is installed —
     * without it, everyone without an individual override just sees the
     * plain default ({@code {player}}).
     */
    private final boolean placeholderApiAvailable;

    /** Whether an expansion failure has already been logged this session — see {@link #applyPlaceholderApi}. */
    private boolean placeholderApiFailureLogged;

    public NametagManager(CustomPlayerNametags plugin, ConfigManager config, PlayerFormatStore formatStore,
                           PlayerWidgetFillStore widgetFillStore, GroupFormatStore groupFormatStore,
                           NametagVisibilityStore visibilityStore) {
        this.plugin = plugin;
        this.config = config;
        this.formatStore = formatStore;
        this.widgetFillStore = widgetFillStore;
        this.groupFormatStore = groupFormatStore;
        this.visibilityStore = visibilityStore;
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (placeholderApiAvailable) {
            plugin.getLogger().info("PlaceholderAPI hooked successfully.");
        } else {
            plugin.getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
        }

        if (isLuckPermsAvailable()) {
            plugin.getLogger().info("LuckPerms hooked successfully. Group nametag formats enabled.");
        } else {
            plugin.getLogger().info("LuckPerms not found. Group nametag formats disabled.");
        }
    }

    public void setDisplayManager(NametagDisplayManager displayManager) {
        this.displayManager = displayManager;
    }

    public NametagDisplayManager getDisplayManager() {
        return displayManager;
    }

    /**
     * Whether PlaceholderAPI is present and enabled. Exposed so callers
     * (e.g. {@code /nametags format} commands) can tell the sender the
     * global {@code nametag-format} is disabled instead of silently letting
     * them view, set, or reset a config value that won't actually render.
     */
    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    /**
     * Builds the chat message a player sees when someone else changes their
     * individual nametag format — the {@code nametag-format-changed-notify}
     * template from config.yml, with {@code {player}} replaced by
     * {@code changer}'s name and {@code {format}} by {@code newRawFormat}
     * parsed exactly the way it'll actually render for {@code target} (the
     * player whose format just changed and who's about to read this
     * message) — the same {@link #parseFormat} pipeline
     * {@code /nametags format ... view} and the real rendered nametag both
     * go through: the built-in {@code {player}} placeholder resolves to
     * {@code target}'s own name, any {@code %placeholder%} is handed to
     * PlaceholderAPI (using {@code target} as the placeholder context, not
     * {@code changer}), each Widget resolves to its own (parsed, possibly
     * limit-truncated) inner content, and a line whose entire visible
     * content came from a Widget that ended up empty is dropped rather than
     * left as a blank gap — see {@link #parseFormat} for the exact steps.
     * The template's own {@code {player}} substitution below still refers
     * to {@code changer} (who made the change), which is why it's applied
     * to the raw template before {@code {format}} — already fully
     * parsed for {@code target} and so no longer containing any literal
     * {@code {player}} of its own — is substituted in. Once {@code {format}}
     * is filled in, the whole template is handed to PlaceholderAPI (if
     * present and {@code changer} is a player) using {@code changer} as the
     * placeholder context — so any placeholder elsewhere in the template
     * describes the player who made the change. {@code \n} line breaks are
     * applied next, and the whole result is then run through
     * {@link NametagFormatter} exactly like a nametag format — so both
     * legacy {@code &} codes and native MiniMessage tags (e.g.
     * {@code <gradient:...>}) work in {@code nametag-format-changed-notify},
     * not just {@code &} codes.
     */
    public Component buildFormatChangedNotify(ConfigManager config, org.bukkit.command.CommandSender changer, Player target, String newRawFormat) {
        String template = config.getFormatChangedNotifyFormat();
        String parsedFormat = parseFormat(target, newRawFormat);
        String result = template.replace("{player}", changer.getName()).replace("{format}", parsedFormat);
        if (placeholderApiAvailable && changer instanceof Player changerPlayer) {
            result = PlaceholderAPI.setPlaceholders(changerPlayer, result);
        }
        result = applyLineBreaks(result);
        return NametagFormatter.toComponent(NametagFormatter.toMiniMessageSource(result));
    }

    /**
     * Whether {@code target} currently falls back to a LuckPerms group
     * format — i.e. what {@link #getEffectiveRawFormat} would resolve to
     * for them right now, with no individual override in play. Exposed so
     * callers (e.g. the {@code disable} branch of {@code /nametags format
     * player}) can tell a player whether they landed on their group's
     * format or the plain global one after their override was cleared,
     * without duplicating {@link #resolveGroupFormat}'s LuckPerms lookup.
     */
    public boolean hasGroupFormat(Player target) {
        return resolveGroupFormat(target) != null;
    }

    /**
     * Same as {@link #buildFormatChangedNotify} but for {@code
     * nametag-format-disabled-notify} — sent when an admin clears a
     * player's individual override (see {@link #resetFormatOverride}) and
     * they fall back to either their group's format or the global one.
     * {@code type} is the literal {@code "group"}/{@code "global"} text for
     * the {@code {type}} token; {@code newRawFormat} should be whatever
     * {@link #getEffectiveRawFormat} resolved to for {@code target} right
     * after the override was cleared.
     */
    public Component buildFormatDisabledNotify(ConfigManager config, org.bukkit.command.CommandSender changer, Player target, String type, String newRawFormat) {
        String template = config.getFormatDisabledNotifyFormat();
        String parsedFormat = parseFormat(target, newRawFormat);
        String result = template.replace("{player}", changer.getName())
                .replace("{type}", type)
                .replace("{format}", parsedFormat);
        if (placeholderApiAvailable && changer instanceof Player changerPlayer) {
            result = PlaceholderAPI.setPlaceholders(changerPlayer, result);
        }
        result = applyLineBreaks(result);
        return NametagFormatter.toComponent(NametagFormatter.toMiniMessageSource(result));
    }

    /**
     * Whether the LuckPerms plugin is present and enabled. Exposed so
     * callers (e.g. {@code /nametags format groups} commands) can tell the
     * sender that group formats are configured but won't actually apply to
     * anyone yet.
     */
    public boolean isLuckPermsAvailable() {
        return LuckPermsIntegration.isAvailable();
    }

    /** The store backing {@code /nametags format groups create/edit/remove}. */
    public GroupFormatStore getGroupFormatStore() {
        return groupFormatStore;
    }

    /**
     * Every LuckPerms group name currently defined on the server, for
     * {@code /nametags format groups create} tab completion. Returns an
     * empty list if LuckPerms isn't installed.
     */
    public List<String> getLuckPermsGroupNames() {
        return LuckPermsIntegration.getAllGroupNames();
    }

    /**
     * {@code target}'s highest-weight LuckPerms group (matching the same
     * priority {@link #resolveGroupFormat} uses to pick which group format
     * applies), or {@code "default"} if LuckPerms isn't installed or the
     * player has no cached group data yet. Purely a display/label value —
     * e.g. for the web editor snapshot ({@code EditorSessionBuilder}) — not
     * used anywhere format resolution itself depends on.
     */
    public String getPrimaryGroupName(Player target) {
        List<String> groups = LuckPermsIntegration.getGroupsByWeightDesc(target);
        return groups.isEmpty() ? "default" : groups.get(0);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void startRefreshTask() {
        stopRefreshTask();
        // enable-nametag-format-placeholder-refresh: if disabled, formats
        // are only ever (re-)resolved on explicit events (join, /nametags
        // set/reset/reload, etc.) instead of on a periodic timer.
        if (!config.isPlaceholderRefreshEnabled()) {
            return;
        }
        long interval = config.getPlaceholderRefreshIntervalTicks();
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player, false);
            }
        }, interval, interval);
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
        // Remove hide-teams for everyone still online. Bukkit.getOnlinePlayers()
        // is iterated directly (no defensive copy needed here — nothing else
        // mutates the online-player set from another thread while this runs
        // synchronously on the main thread during shutdown).
        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();
        for (UUID uuid : states.keySet()) {
            removeHideTeam(uuid, viewers);
        }
        for (Player online : viewers) {
            // Reset every online player's tab list entry back to their
            // default (vanilla username-based) rendering, rather than
            // leaving them stuck on whatever custom format they last had
            // applied while the plugin is disabled/reloading.
            online.playerListName(null);
        }
        states.clear();
        BedrockDetector.clearAll();
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player, true);
        }
        // Any of the format tiers refreshAll's callers just changed
        // (global/Bedrock-global/group formats, or a bulk /nametags reload)
        // could have removed a widget that a player had a fill stored
        // against — catch that here rather than only on the narrower
        // per-player path in setFormatOverride.
        pruneOrphanedWidgetFills();
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
        removeHideTeam(uuid, Bukkit.getOnlinePlayers());
        // The player's client type can never change mid-session, but a
        // future rejoin could come from a different client entirely — drop
        // the cached result so BedrockDetector recomputes it next time
        // rather than ever risking a stale answer across reconnects.
        BedrockDetector.forget(uuid);
    }

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    public void refresh(Player target, boolean force) {
        if (hiddenTargets.contains(target.getUniqueId())) {
            if (displayManager != null) displayManager.remove(target.getUniqueId());
            setVanillaNametagHidden(target, true);
            return;
        }
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

        // Iterate Bukkit's own online-player collection directly instead of
        // copying it into a fresh ArrayList on every call. This method runs
        // once per online player every REFRESH_INTERVAL_TICKS, and used to
        // allocate an O(players)-sized list every single time any player's
        // resolved text changed (e.g. from a placeholder like ping or a
        // scoreboard value that updates continuously) — an O(players^2)
        // allocation/copy cost per refresh cycle on a populated server with
        // any dynamic placeholders in use. Bukkit.getOnlinePlayers() is a
        // safe, already-immutable view to iterate directly here since
        // nothing below mutates it and this always runs on the main thread.
        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();

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

        applyTablist(target, fullText);
    }

    /**
     * Applies {@code fullText} (already fully resolved — same format the
     * nametag itself is rendering) to {@code target}'s tab list entry, so
     * the tab list always mirrors whichever format tier
     * (individual/group/Bedrock-global/global) currently applies to them.
     * Gated behind {@code enable-tablist-format} in config.yml so server
     * owners who only want the overhead nametag customized can opt out.
     *
     * <p>Tab list entries render as a single line client-side, so any
     * {@code \n} line breaks in the format are collapsed to a single space
     * for this specific rendering only — the overhead nametag above the
     * player's head is unaffected and keeps its real line breaks.
     */
    private void applyTablist(Player target, String fullText) {
        if (!config.isTablistFormatEnabled()) {
            return;
        }
        // fullText is already a fully-resolved MiniMessage source string
        // (see parse()) — only the newline-to-space collapse is specific
        // to this tab-list rendering.
        String singleLine = fullText == null ? "" : fullText.replace('\n', ' ');
        target.playerListName(NametagFormatter.toComponent(singleLine));
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
        String raw = getEffectiveRawFormat(target);
        String parsed = parseFormat(target, raw);
        // Extracted from the un-stripped raw format — a Characters-item
        // (see TemplateMarkers) sets a per-line override that this player's
        // own template may carry, on top of (or instead of) the uniform
        // nametag-line-max-characters limit below.
        return truncateToCharacterLimit(target, parsed, TemplateMarkers.lineCharacterOverrides(raw));
    }

    private static boolean allNegative(int[] values) {
        if (values == null) {
            return true;
        }
        for (int v : values) {
            if (v >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Enforcement of {@code nametag-line-max-characters} (see
     * {@link NametagFormatPermissions#effectiveLineCharacterLimit}) on the
     * text that's actually about to be displayed — the only place a
     * line-length limit is ever actually enforced. {@link
     * afx.customplayernametags.manager.NametagEditorManager#confirm} no
     * longer blocks saving a format whose lines are already too long (it
     * only warns); this is what actually keeps a line within its limit,
     * here and for as long as the format is in use — including if a
     * placeholder like {@code %luckperms_prefix%} legitimately expands to
     * a different length later (a rank change, a scoreboard value, etc)
     * after the format was saved. Lines within the limit are returned
     * byte-for-byte unchanged; a line that's too long is cut to exactly
     * the limit's worth of visible characters (colors/decorations on the
     * kept characters are preserved), with a plain white {@code "..."}
     * appended only if {@code nametag-truncate-indicator} is on — see
     * {@link NametagFormatter#truncateLineWithEllipsis} and {@link
     * NametagFormatter#truncateVisibleCharacters}.
     *
     * <p>{@code lineOverrides}, extracted from the raw (pre-{@link
     * TemplateMarkers#strip}) format by {@link TemplateMarkers#lineCharacterOverrides},
     * lets a per-line Characters item (set by an admin in the chunk editor)
     * replace the uniform limit for just that one line — a line with no
     * override falls back to {@code limit} exactly as before.
     */
    private String truncateToCharacterLimit(Player target, String parsedMiniMessageSource, int[] lineOverrides) {
        int limit = NametagFormatPermissions.effectiveLineCharacterLimit(config, target);
        if ((limit < 0 && allNegative(lineOverrides)) || parsedMiniMessageSource.isEmpty()) {
            return parsedMiniMessageSource;
        }
        boolean ellipsis = config.isTruncationEllipsisEnabled();
        Component whole = NametagFormatter.toComponent(parsedMiniMessageSource);
        List<Component> lines = NametagFormatter.splitLines(whole);
        boolean anyTruncated = false;
        List<Component> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            int effectiveLimit = lineOverrides != null && i < lineOverrides.length && lineOverrides[i] >= 0
                    ? lineOverrides[i] : limit;
            Component truncated = ellipsis
                    ? NametagFormatter.truncateLineWithEllipsis(line, effectiveLimit)
                    : NametagFormatter.truncateVisibleCharacters(line, effectiveLimit);
            if (truncated != line) {
                anyTruncated = true;
            }
            result.add(truncated);
        }
        if (!anyTruncated) {
            return parsedMiniMessageSource;
        }
        Component rejoined = Component.empty();
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) {
                rejoined = rejoined.append(Component.text("\n"));
            }
            rejoined = rejoined.append(result.get(i));
        }
        return NametagFormatter.serialize(rejoined);
    }

    /**
     * Resolves {@code raw} into final display text: the built-in
     * {@code {player}} placeholder is substituted with {@code target}'s
     * username first (so it always works, with or without PlaceholderAPI),
     * then the result is handed to PlaceholderAPI — but only if it's
     * actually installed; otherwise any {@code %placeholder%} left in the
     * string is simply left unparsed rather than attempting to call a class
     * that isn't there. {@code &} colors are translated last, followed by
     * {@code \n} line-break substitution. Any Widget with its own
     * {@code limit=} is then cut down to that many visible characters if
     * its now-resolved contents run over — see
     * {@link TemplateMarkers#truncateMarkedWidgets} — since a widget can
     * hold a Placeholder item whose resolved length isn't known until this
     * point. Whether that cut appends the trailing {@code "..."} marker is
     * controlled separately from every other line-limit truncation, via
     * {@link ConfigManager#isWidgetTruncationEllipsisEnabled()}. Finally, any line whose entire visible content came from a
     * Widget that ended up empty (unfilled, filled with nothing but
     * whitespace, or truncated down to nothing) is dropped entirely, so an
     * optional Widget slot nobody's filled in doesn't leave a blank gap in
     * the rendered nametag — see {@link TemplateMarkers#dropEmptyWidgetLines}.
     */
    public String parseFormat(Player target, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Every raw format that can come from the chunk editor may carry
        // admin-only Lock/Characters/Lines/Widget template markers (see
        // TemplateMarkers) — stripping them here, before anything else,
        // is what guarantees none of that metadata ever leaks into actual
        // rendered text: a Widget resolves to its own inner content, and
        // Lock/Characters/Lines simply disappear (they carry no visible
        // content of their own). A Widget with its own limit= gets a
        // transient marker instead of its bare content, so its resolved
        // length can still be enforced below, after {player}/PlaceholderAPI
        // substitution has actually happened.
        String result = TemplateMarkers.stripForRendering(raw);
        result = target != null ? result.replace("{player}", target.getName()) : result;
        if (placeholderApiAvailable) {
            result = applyPlaceholderApi(target, result);
        }
        result = applyLineBreaks(result);
        result = TemplateMarkers.truncateMarkedWidgets(result, config.isWidgetTruncationEllipsisEnabled());
        result = TemplateMarkers.dropEmptyWidgetLines(raw, result);
        // MiniMessage compatibility: legacy '&' codes (including '&#RRGGBB'
        // hex) are converted to their MiniMessage tag equivalents so they
        // coexist with native MiniMessage markup (colors, gradients,
        // rainbow, and the custom <animated:...> construct) already present
        // in the format — see NametagFormatter for details. The resulting
        // string is a self-contained MiniMessage source, not legacy-colored
        // text; NametagDisplayManager (nametags) and applyTablist() (tab
        // list) both deserialize it the same way via NametagFormatter, so
        // both always render identically.
        return NametagFormatter.toMiniMessageSource(result);
    }

    /**
     * Hands {@code text} to PlaceholderAPI, tolerating a misbehaving
     * expansion instead of letting it take the whole nametag pipeline down.
     * A third-party expansion throwing (most often on a null player context,
     * e.g. a console-run {@code /nametags format global view}) would
     * otherwise propagate out of the refresh task and kill every nametag on
     * the server, not just the one placeholder. The unresolved text is
     * returned instead, and the failure is logged once per session so it
     * never spams console from the periodic refresh.
     */
    private String applyPlaceholderApi(Player target, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(target, text);
        } catch (Throwable t) {
            if (!placeholderApiFailureLogged) {
                placeholderApiFailureLogged = true;
                plugin.getLogger().warning("A PlaceholderAPI expansion threw while resolving a nametag format: "
                        + t + " — the placeholder was left unresolved. This is logged once per server start.");
            }
            return text;
        }
    }

    /**
     * Turns a literal two-character {@code \n} escape sequence — as typed
     * in {@code config.yml} (in a single-quoted or plain scalar, where YAML
     * itself won't interpret it) or straight into
     * {@code /nametags format set <player> "<format>"} — into a real line
     * break character, so multi-line nametags work the same regardless of
     * how the format string was authored.
     *
     * <p>Deliberately applied <em>after</em> {@code &} color translation:
     * doing it first would risk a color code accidentally splitting across
     * the two characters of the escape sequence on a hand-typed format.
     * Real newline characters already present in the string (e.g. from a
     * YAML double-quoted or block-style scalar, which SnakeYAML already
     * unescapes on load) are left untouched — this only ever replaces the
     * literal backslash-n text.
     */
    private static String applyLineBreaks(String text) {
        return text.replace("\\n", "\n");
    }

    /**
     * The raw, unparsed format string currently in effect for {@code target}:
     * their per-player override from {@code /nametags format set} if one has
     * been set, otherwise the global {@code nametag-format} from config.yml.
     * Placeholders are left unresolved and {@code &} color codes untranslated
     * — exactly as stored. Backs {@code /nametags format view unparsed}.
     */
    public String getEffectiveRawFormat(Player target) {
        boolean bedrock = BedrockDetector.isBedrockPlayer(target);
        String override = formatStore.get(target.getUniqueId());
        if (override != null) {
            return overlayWidgetFills(target.getUniqueId(),
                    applyBedrockAffixes(override, bedrock && config.isBedrockAffixAppliedToPlayer()));
        }
        // Without PlaceholderAPI installed, only an individual per-player
        // format override (handled above) still works — group and global
        // custom formats fall back to the plain default here rather than
        // applying with any %placeholder% left unresolved. The plain
        // default has no widgets to fill, so there's nothing to overlay.
        if (!placeholderApiAvailable) {
            return DEFAULT_FORMAT;
        }
        String groupFormat = resolveGroupFormat(target);
        if (groupFormat != null) {
            return overlayWidgetFills(target.getUniqueId(),
                    applyBedrockAffixes(groupFormat, bedrock && config.isBedrockAffixAppliedToGroup()));
        }
        if (config.isSeparateBedrockGlobalFormatEnabled() && bedrock) {
            return overlayWidgetFills(target.getUniqueId(),
                    applyBedrockAffixes(config.getBedrockGlobalFormat(), config.isBedrockAffixAppliedToGlobal()));
        }
        return overlayWidgetFills(target.getUniqueId(),
                applyBedrockAffixes(config.getNametagFormat(), bedrock && config.isBedrockAffixAppliedToGlobal()));
    }

    /**
     * Re-applies {@code uuid}'s stored Widget fill-ins (see
     * {@link #widgetFillStore}) on top of {@code base} — whichever
     * override/group/Bedrock-global/global format tier
     * {@link #getEffectiveRawFormat} just resolved for them — instead of
     * that player ever needing their own frozen copy of {@code base} just
     * to remember what they typed into a widget. Matched by each widget's
     * own id (see {@link TemplateMarkers.Widget#id()}), not its position,
     * so a fill survives an admin reordering/adding/removing other widgets
     * in {@code base}. A no-op (returns {@code base} unchanged) if they
     * have no stored fills, or if {@code base} has no widgets at all.
     */
    private String overlayWidgetFills(UUID uuid, String base) {
        Map<String, String> fills = widgetFillStore.getAll(uuid);
        return fills.isEmpty() ? base : TemplateMarkers.overlayWidgetFills(base, fills);
    }

    /**
     * Whether {@code uuid} currently has an explicit individual
     * {@code nametag-format} override on file (set via
     * {@code /nametags format set}/{@code player set}, or the admin-facing
     * editor) — as opposed to only having filled in Widget slots inside
     * whichever global/group format they inherit, which doesn't count as
     * an override. Lets {@link NametagEditorManager}'s player-facing
     * "Edit Nametag" flow tell the two cases apart: an existing explicit
     * override is still edited (and re-saved) as a whole, while a plain
     * inheriting player's widget fill-ins are persisted separately instead
     * — see {@link #saveWidgetFills}.
     */
    public boolean hasIndividualFormatOverride(UUID uuid) {
        return formatStore.get(uuid) != null;
    }

    /**
     * Persists {@code fills} (widget id -> filled content, as
     * extracted from a {@code Target.PLAYER_EDIT} session's serialized
     * format — see {@code TemplateMarkers#extractWidgetContents}) for
     * {@code uuid}, without creating or touching any individual
     * {@code nametag-format} override for them, and immediately refreshes
     * their tag if they're online. Backs the player-facing "Edit Nametag"
     * flow for a player with no override of their own — see
     * {@link NametagEditorManager#confirm}.
     */
    public void saveWidgetFills(UUID uuid, Map<String, String> fills) {
        widgetFillStore.setAll(uuid, fills);
        Player target = Bukkit.getPlayer(uuid);
        if (target != null && target.isOnline()) {
            refresh(target, true);
        }
    }

    /**
     * Housekeeping GC for {@link #widgetFillStore}: scans every format tier
     * that can possibly be reached by anyone — the global format, the
     * Bedrock-global format (if separately configured), every group
     * format (Java and Bedrock-specific), and every individual player
     * override — collects the full set of widget ids still live across all
     * of them, then asks {@link #widgetFillStore} to drop any stored fill
     * that keys a widget id outside that set. Safe (and cheap) to call
     * often; wired into both {@link #refreshAll()} and
     * {@link #setFormatOverride} so a widget that gets removed from a
     * template — or an override that gets replaced wholesale — has its
     * now-orphaned fills cleaned up promptly rather than lingering forever
     * in {@code player-widget-fills.db}.
     */
    public void pruneOrphanedWidgetFills() {
        java.util.Set<String> liveIds = new java.util.HashSet<>();
        liveIds.addAll(TemplateMarkers.widgetIds(config.getNametagFormat()));
        if (config.isSeparateBedrockGlobalFormatEnabled()) {
            liveIds.addAll(TemplateMarkers.widgetIds(config.getBedrockGlobalFormat()));
        }
        for (String groupName : groupFormatStore.getGroupNames()) {
            liveIds.addAll(TemplateMarkers.widgetIds(groupFormatStore.get(groupName)));
        }
        for (String groupName : groupFormatStore.getGroupNames(true)) {
            liveIds.addAll(TemplateMarkers.widgetIds(groupFormatStore.get(groupName, true)));
        }
        for (String format : formatStore.getAllFormats()) {
            liveIds.addAll(TemplateMarkers.widgetIds(format));
        }
        widgetFillStore.pruneOrphans(liveIds);
    }

    private String applyBedrockAffixes(String format, boolean apply) {
        return apply ? config.getBedrockNametagPrefix() + format + config.getBedrockNametagSuffix() : format;
    }

    /**
     * The custom format assigned (via {@code /nametags format groups}) to
     * whichever of {@code target}'s LuckPerms groups has the highest
     * LuckPerms weight <em>and</em> has a stored format — not simply their
     * single highest-weighted group overall. A player might belong to a
     * high-weight group with no custom format configured and a
     * lower-weight one that does; the lower-weight one with an actual
     * format still applies in that case. Returns {@code null} if LuckPerms
     * isn't installed, the player belongs to no group with a stored
     * format, or lookup otherwise fails — callers fall through to the next
     * format tier (Bedrock global, then global) in that case.
     *
     * <p>For a Bedrock/Geyser {@code target} with
     * {@code enable-separate-bedrock-group-formats} on, a group's Bedrock
     * format is only checked <em>within</em> that same group, as a
     * higher-priority alternative to its Java format — not as a
     * replacement for the whole group in the weight ordering. A group with
     * only a Java format configured still applies to a Bedrock player
     * exactly as it would to a Java one; a group is skipped in favor of
     * the next-highest-weighted one only when it has <em>neither</em>
     * variant stored, same as before this per-platform check existed.
     * Getting this backwards — treating "no Bedrock-specific format on
     * this group" as "this group has no format at all" for a Bedrock
     * player — would silently jump them past a group whose (perfectly
     * applicable) Java format an admin clearly intended to apply, and land
     * them on a lower-weighted group's format instead, purely because the
     * higher one was never given its own Bedrock override.
     */
    private String resolveGroupFormat(Player target) {
        if (!isLuckPermsAvailable()) {
            return null;
        }
        boolean bedrock = config.isSeparateBedrockGroupFormatsEnabled() && BedrockDetector.isBedrockPlayer(target);
        List<String> groupsByWeightDesc = LuckPermsIntegration.getGroupsByWeightDesc(target);
        for (String group : groupsByWeightDesc) {
            String format = bedrock ? groupFormatStore.get(group, true) : null;
            if (format == null) {
                format = groupFormatStore.get(group, false);
            }
            if (format != null) {
                return format;
            }
        }
        return null;
    }

    /**
     * Same as {@link #getEffectiveRawFormat(Player)} but resolved with
     * {@code &} colors translated — i.e. exactly what's currently rendered
     * above {@code target}'s head. Backs {@code /nametags format view
     * parsed}.
     *
     * <p>A per-player override (set via {@code /nametags format set
     * player}) always renders, with or without PlaceholderAPI — the built-in
     * {@code {player}} placeholder is resolved either way, and any
     * {@code %placeholder%} is simply left unparsed (shown as literal text)
     * if PlaceholderAPI isn't present. Without an override, the group/global
     * format tiers only apply while PlaceholderAPI is installed — see
     * {@link #getEffectiveRawFormat(Player)}.
     */
    public String getEffectiveParsedFormat(Player target) {
        return parseFormat(target, getEffectiveRawFormat(target));
    }

    /**
     * The raw, unparsed global {@code nametag-format} from config.yml,
     * exactly as stored (placeholders unresolved, {@code &} codes
     * untranslated). Backs {@code /nametags format view unparsed global}.
     */
    public String getGlobalRawFormat() {
        return config.getNametagFormat();
    }

    /**
     * The global {@code nametag-format} resolved through PlaceholderAPI
     * (with no specific player context — player-only placeholders will
     * resolve blank or unresolved) and with {@code &} colors translated.
     */
    public String getGlobalParsedFormat() {
        return getGlobalParsedFormat(null);
    }

    /**
     * Same as {@link #getGlobalParsedFormat()}, but the built-in
     * {@code {player}} placeholder and any PlaceholderAPI placeholders are
     * resolved using {@code context} as the subject — so player-only
     * placeholders (e.g. {@code %player_name%}) resolve to {@code context}'s
     * values instead of coming back blank or unresolved. Backs
     * {@code /nametags format view global parsed <player>}.
     *
     * <p>Always renders the configured global {@code nametag-format}, with
     * or without PlaceholderAPI — this always shows exactly what players
     * actually see, since the live nametag itself no longer falls back to a
     * plain username when PlaceholderAPI is missing.
     */
    public String getGlobalParsedFormat(Player context) {
        return parseFormat(context, getGlobalRawFormat());
    }

    /**
     * The raw, unparsed Bedrock global {@code bedrock-nametag-format} from config.yml, exactly as
     * stored. Only meaningful when {@link afx.customplayernametags.config.ConfigManager#isSeparateBedrockGlobalFormatEnabled()}
     * is true. Backs {@code /nametags format global view bedrock}.
     */
    public String getBedrockGlobalRawFormat() {
        return config.getBedrockGlobalFormat();
    }

    /**
     * Same as {@link #getGlobalParsedFormat(Player)}, but for the Bedrock global format —
     * {@code context} is used to resolve player-scoped placeholders. Backs
     * {@code /nametags format global view bedrock <player>}.
     */
    public String getBedrockGlobalParsedFormat(Player context) {
        return parseFormat(context, getBedrockGlobalRawFormat());
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
        // Setting (or clearing) an override can retire whichever widgets
        // only that override referenced — catch that here rather than
        // waiting for the next refreshAll()/reload.
        pruneOrphanedWidgetFills();
    }

    /**
     * Clears {@code uuid}'s per-player {@code nametag-format} override (if
     * any) from both memory and {@code player-formats.db}, reverting them
     * to the global/group {@code nametag-format} they'd otherwise inherit,
     * and immediately refreshes their tag if they're online. Backs
     * {@code /nametags format player disable}.
     *
     * <p>Deliberately does <em>not</em> touch {@link #widgetFillStore}. A
     * player's Widget fill-ins inside an inherited global/group format
     * were never part of this override to begin with (see
     * {@link #getEffectiveRawFormat}'s overlay and
     * {@link #saveWidgetFills}), so disabling an explicit override an
     * admin set for them must not also wipe out customization they did in
     * a widget the admin never overrode in the first place.
     */
    public void resetFormatOverride(UUID uuid) {
        setFormatOverride(uuid, null);
    }

    // ------------------------------------------------------------------
    // Nametag visibility toggle (/nametag toggle)
    // ------------------------------------------------------------------

    /** Whether {@code uuid} currently has other players' nametags hidden from their own view. */
    public boolean hasNametagsHidden(UUID uuid) {
        return visibilityStore.isHidden(uuid);
    }

    /**
     * Flips whether {@code viewer} sees other players' nametags, persists
     * it, and immediately re-applies visibility to every existing nametag
     * for them. Backs {@code /nametag toggle}. Returns the new state
     * (true = other players' nametags are now hidden from {@code viewer}).
     */
    public boolean toggleNametagsHidden(Player viewer) {
        boolean nowHidden = visibilityStore.toggle(viewer.getUniqueId());
        if (displayManager != null) {
            displayManager.showExistingTo(viewer);
        }
        return nowHidden;
    }

    /** Toggles whether a target's nametag is rendered for everyone. */
    public boolean togglePlayerNametagHidden(Player target) {
        boolean hidden;
        if (hiddenTargets.remove(target.getUniqueId())) {
            hidden = false;
            refresh(target, true);
        } else {
            hiddenTargets.add(target.getUniqueId());
            hidden = true;
            if (displayManager != null) displayManager.remove(target.getUniqueId());
        }
        return hidden;
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

    private void createHideTeam(Player target, Collection<? extends Player> viewers) {
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

    private void removeHideTeam(UUID target, Collection<? extends Player> viewers) {
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
