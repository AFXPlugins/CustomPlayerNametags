package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ConfigManager {

    private static final String DEFAULT_FORMAT = "{player}";

    /** Height (in blocks) the nametag floats above the player. */
    private static final double NAMETAG_HEIGHT_OFFSET = 2.085;
    /** Extra height while sneaking, for all viewers. */
    private static final double SNEAK_HEIGHT_ADJUST = 0.270;
    /**
     * Baked-in height correction (in blocks) for Bedrock/Geyser viewers
     * only (standing + sneaking), applied unconditionally regardless of
     * config — negative lowers the tag. This used to double as the
     * fallback default passed to {@code cfg.getDouble("bedrock-height-adjust", ...)},
     * which only ever took effect when the key was <em>missing</em> from
     * config.yml. Once config.yml started always shipping the key
     * (explicitly set to {@code 0}, so {@code bedrock-height-adjust} in the
     * file reads as "how much extra to add on top of the built-in
     * correction"), that fallback stopped doing anything at all — the
     * explicit {@code 0} in the file was silently overriding this
     * correction to nothing every time, instead of adding to it, leaving
     * Bedrock/Geyser viewers with no downward correction and the tag
     * rendering noticeably too high. Now applied unconditionally in
     * {@link #getBedrockHeightAdjust()} together with the config value, so
     * the config's {@code 0} default genuinely means "no extra adjustment
     * on top of this" rather than "replace this with zero".
     */
    private static final double BAKED_IN_BEDROCK_HEIGHT_ADJUST = -0.6;
    /**
     * Baked-in extra height (in blocks) for Bedrock/Geyser viewers while
     * sneaking, applied unconditionally regardless of config — see
     * {@link #BAKED_IN_BEDROCK_HEIGHT_ADJUST} for why this can't just be a
     * {@code cfg.getDouble} fallback default.
     */
    private static final double BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST = 0.32;
    /**
     * Default for {@code global-nametag-height-adjust} in config.yml: a
     * flat height correction (in blocks) added on top of the built-in
     * nametag height for every viewer, Java and Bedrock/Geyser alike.
     * {@code 0.0} means "use the built-in default height as-is". See
     * {@link #getGlobalHeightAdjust()}.
     */
    private static final double DEFAULT_GLOBAL_HEIGHT_ADJUST = 0.0;

    /**
     * Controls when the plugin automatically dismounts a player's nametag
     * in response to a command they run. Configured via
     * {@code nametag-dismount-mode} in config.yml.
     *
     * <p>None of these values affect the console-only
     * {@code /nametags dismount <player>} command, which always
     * works regardless of the configured mode.
     */
    public enum DismountMode {
        /** Never automatically dismount. Entries in {@code dismount-commands} are ignored. */
        NONE,
        /** Automatically dismount on every command a player runs. */
        AUTO,
        /** Only dismount when a command listed in {@code dismount-commands} is run. */
        MANUAL;

        private static DismountMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            try {
                return DismountMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    private final CustomPlayerNametags plugin;

    /**
     * Fallback used for {@code dismount-duration-ticks} if it's missing or
     * invalid in config.yml. 5 ticks (0.25s) gives teleports that don't
     * complete synchronously — e.g. cross-dimension moves that need to load
     * chunks or run a safe-spot search — enough of a buffer to actually
     * finish before the nametag remounts and blocks them again. This is
     * just a ceiling: {@link afx.customplayernametags.listener.PlayerConnectionListener#onWorldChange}
     * already closes the dismount window immediately once a world change
     * actually happens, so a generous value here doesn't mean the tag stays
     * visibly detached any longer for teleports that complete quickly.
     */
    private static final long DEFAULT_DISMOUNT_DURATION_TICKS = 5L;

    /**
     * Floor for {@code dismount-duration-ticks} (and the console command's
     * optional {@code [ticks]} argument). Below this, the dismount window
     * can close on the very next {@code tickMaintain()} tick after being
     * opened — leaving whatever triggered the dismount (a teleport or other
     * command) effectively no buffer to finish before the nametag remounts
     * and blocks it again, which is exactly the race this system exists to
     * prevent. See config.yml's own "Don't set value lower than 2" note.
     */
    public static final long MIN_DISMOUNT_DURATION_TICKS = 2L;

    private String nametagFormat;
    private double nametagRenderDistance;
    private DismountMode nametagDismountMode;
    private List<List<String>> dismountCommands;
    private long dismountDurationTicks;
    /** Config-provided fine-tuning delta for bedrock-height-adjust — added on top of {@link #BAKED_IN_BEDROCK_HEIGHT_ADJUST}, not a replacement for it. */
    private double bedrockHeightAdjustConfig;
    /** Config-provided fine-tuning delta for bedrock-sneak-height-adjust — added on top of {@link #BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST}, not a replacement for it. */
    private double bedrockSneakHeightAdjustConfig;
    private double globalHeightAdjust;

    public ConfigManager(CustomPlayerNametags plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.nametagFormat = cfg.getString("nametag-format", DEFAULT_FORMAT);

        // Default: 64 blocks, vanilla's own fixed cutoff for rendering any
        // entity nametag regardless of server entity-tracking-range settings.
        this.nametagRenderDistance = cfg.getDouble("nametag-render-distance", 64.0);

        this.nametagDismountMode = DismountMode.parse(cfg.getString("nametag-dismount-mode", "AUTO"));

        long configuredTicks = cfg.getLong("dismount-duration-ticks", DEFAULT_DISMOUNT_DURATION_TICKS);
        this.dismountDurationTicks = Math.max(configuredTicks, MIN_DISMOUNT_DURATION_TICKS);

        List<List<String>> commands = new ArrayList<>();
        for (String raw : cfg.getStringList("dismount-commands")) {
            List<String> tokens = tokenizeCommand(raw);
            if (!tokens.isEmpty()) {
                commands.add(tokens);
            }
        }
        this.dismountCommands = commands;

        // These two are pure fine-tuning deltas layered on top of the
        // always-applied BAKED_IN_BEDROCK_HEIGHT_ADJUST /
        // BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST constants below (see
        // getBedrockHeightAdjust() / getBedrockSneakHeightAdjust()) — the
        // fallback here is deliberately 0.0, meaning "no extra adjustment
        // on top of the built-in correction", not "no correction at all".
        this.bedrockHeightAdjustConfig = cfg.getDouble("bedrock-height-adjust", 0.0);
        this.bedrockSneakHeightAdjustConfig = cfg.getDouble("bedrock-sneak-height-adjust", 0.0);
        this.globalHeightAdjust = cfg.getDouble("global-nametag-height-adjust", DEFAULT_GLOBAL_HEIGHT_ADJUST);
    }

    /**
     * Splits a command (either from {@code dismount-commands} in config.yml
     * or from a raw {@code /command args...} string typed by a player) into
     * lowercase tokens, stripping any leading slash and, on the first token
     * only, any plugin namespace prefix (e.g. {@code "essentials:tp"} ->
     * {@code "tp"}).
     *
     * <p>Used both to normalize the configured {@code dismount-commands}
     * entries and to tokenize the command a player actually ran, so the two
     * can be compared token-by-token — this is what lets a multi-word entry
     * like {@code "mv tp"} match {@code "/mv tp world"}.
     */
    public static List<String> tokenizeCommand(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String withoutSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        String trimmed = withoutSlash.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = trimmed.split("\\s+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                int colonIndex = part.indexOf(':');
                if (colonIndex != -1) {
                    part = part.substring(colonIndex + 1);
                }
                if (part.isEmpty()) {
                    continue;
                }
            }
            tokens.add(part.toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    public String getNametagFormat() {
        return nametagFormat;
    }

    /**
     * Sets the global {@code nametag-format} to {@code format}, persists it
     * to config.yml, and updates the in-memory value immediately (no reload
     * needed). Passing {@code null} or an empty string resets it back to the
     * default. Backs {@code /nametags format set global}.
     */
    public void setGlobalFormat(String format) {
        String value = (format == null || format.isEmpty()) ? DEFAULT_FORMAT : format;
        this.nametagFormat = value;
        plugin.getConfig().set("nametag-format", value);
        plugin.saveConfig();
    }

    /**
     * Resets the global {@code nametag-format} back to the default
     * ({@code {player}}). Backs {@code /nametags format reset global}.
     */
    public void resetGlobalFormat() {
        setGlobalFormat(DEFAULT_FORMAT);
    }

    public double getNametagHeightOffset() {
        return NAMETAG_HEIGHT_OFFSET;
    }

    /** Extra height added back while sneaking, on top of the pose-tracking translation, to keep the tag from riding too low. */
    public double getSneakHeightAdjust() {
        return SNEAK_HEIGHT_ADJUST;
    }

    /**
     * Flat height correction applied whenever a nametag is shown to a
     * Bedrock/Geyser <b>viewer</b> — standing or sneaking, regardless of
     * which platform the player wearing the tag is on. This corrects for
     * how Geyser's client renders a passenger-mounted display compared to
     * vanilla Java, which is a property of the viewer's renderer, not of
     * the tag's owner.
     *
     * <p>Equal to {@link #BAKED_IN_BEDROCK_HEIGHT_ADJUST} (always applied)
     * plus whatever fine-tuning delta is configured via
     * {@code bedrock-height-adjust} in config.yml, which defaults to and is
     * intended to normally stay at {@code 0.0} — i.e. "use the built-in
     * correction as-is". This addition (rather than the config value simply
     * replacing the built-in one) matters: config.yml always ships with
     * this key present and set to {@code 0}, so if the config value
     * replaced the built-in correction instead of adding to it, every
     * install would silently get zero Bedrock/Geyser correction.
     */
    public double getBedrockHeightAdjust() {
        return BAKED_IN_BEDROCK_HEIGHT_ADJUST + bedrockHeightAdjustConfig;
    }

    /**
     * Extra height added on top of {@link #getBedrockHeightAdjust()} when a
     * Bedrock/Geyser viewer is looking at a sneaking player's tag (any
     * owner platform), since the Java/Bedrock rendering gap doesn't
     * necessarily stay constant across poses.
     *
     * <p>Equal to {@link #BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST} (always
     * applied) plus whatever fine-tuning delta is configured via
     * {@code bedrock-sneak-height-adjust} in config.yml — see
     * {@link #getBedrockHeightAdjust()} for why this is additive rather
     * than a straight replacement.
     */
    public double getBedrockSneakHeightAdjust() {
        return BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST + bedrockSneakHeightAdjustConfig;
    }

    /**
     * Extra height (in blocks) added to a Bedrock/Geyser viewer's copy of
     * the tag specifically for the brief window while it's dismounted
     * (mid-command — see {@link afx.customplayernametags.manager.NametagDisplayManager#dismount}),
     * on top of the same fixed reference point Java viewers use for that
     * window.
     *
     * <p>Not a separate config option — always computed as the exact
     * opposite of the <em>effective</em> {@link #getBedrockHeightAdjust()}
     * (built-in correction plus any configured fine-tuning delta), so it
     * cancels that correction out for the brief dismounted window (whose
     * reference point, unlike the continuously-mounted case, is calibrated
     * against vanilla Java's own passenger-mounting math and doesn't need —
     * and previously took stacking damage from — its own independent
     * Bedrock/Geyser correction; see the class javadoc history in
     * {@link afx.customplayernametags.manager.NametagDisplayManager} for
     * why a separately-tunable value here used to cause visible popping).
     */
    public double getBedrockDismountHeightAdjust() {
        return -getBedrockHeightAdjust();
    }

    /**
     * Flat height correction (in blocks) added to the nametag's height for
     * <b>every</b> viewer — Java and Bedrock/Geyser alike — on top of the
     * built-in default height and every other adjustment in this class.
     * {@code 0.0} (the default) means the built-in default height is used
     * as-is. Configured via {@code global-nametag-height-adjust} in
     * config.yml.
     */
    public double getGlobalHeightAdjust() {
        return globalHeightAdjust;
    }

    /**
     * Maximum distance (in blocks) from a viewer at which their custom
     * TextDisplay nametag is shown at all, matching vanilla's own fixed
     * client-side nametag render cutoff (64 blocks) rather than whatever
     * the server's entity-tracking-range is set to. Configured via
     * {@code nametag-render-distance} in config.yml.
     */
    public double getNametagRenderDistance() {
        return nametagRenderDistance;
    }

    /**
     * How long (in ticks) a player's nametag should remain dismounted after
     * a triggering command or teleport. Configured via
     * {@code dismount-duration-ticks} in config.yml (default 10). Also used
     * as the duration for every entry in {@code dismount-commands}, and for
     * the console-only {@code /nametags dismount <player>} command. The
     * player's nametag will automatically remount after this duration, or
     * immediately on an actual world change, whichever comes first.
     */
    public long getDismountDurationTicks() {
        return dismountDurationTicks;
    }

    /**
     * Which automatic-dismount behavior is active, per {@code nametag-dismount-mode}
     * in config.yml. Does not affect the console-only
     * {@code /nametags dismount <player>} command, which always works.
     */
    public DismountMode getNametagDismountMode() {
        return nametagDismountMode;
    }

    /**
     * The configured {@code dismount-commands} entries, each already split
     * into lowercase tokens via {@link #tokenizeCommand(String)}.
     */
    public List<List<String>> getDismountCommands() {
        return Collections.unmodifiableList(dismountCommands);
    }

    /**
     * True if {@code messageTokens} (the tokenized command a player just
     * ran) is matched by any entry in {@code dismount-commands} — i.e. the
     * entry's tokens appear, in order, as a leading prefix of the command
     * actually run. This is what makes a configured entry like
     * {@code "mvtp"} match {@code "/mvtp world"} and {@code "mv tp"} match
     * {@code "/mv tp world"}, including any further subcommand arguments.
     */
    public boolean matchesDismountCommand(List<String> messageTokens) {
        for (List<String> entry : dismountCommands) {
            if (isPrefix(entry, messageTokens)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrefix(List<String> prefix, List<String> tokens) {
        if (prefix.isEmpty() || prefix.size() > tokens.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(tokens.get(i))) {
                return false;
            }
        }
        return true;
    }
}