package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ConfigManager {

    private static final String DEFAULT_FORMAT = "%player_name%";

    /** Height (in blocks) the nametag floats above the player. */
    private static final double NAMETAG_HEIGHT_OFFSET = 2.085;
    /** Extra height while sneaking, for all viewers. */
    private static final double SNEAK_HEIGHT_ADJUST = 0.270;
    /** Height correction for Bedrock/Geyser viewers only (standing + sneaking). */
    private static final double BEDROCK_HEIGHT_ADJUST = -0.155;
    /** Extra height for Bedrock/Geyser viewers while sneaking. */
    private static final double BEDROCK_SNEAK_HEIGHT_ADJUST = 0.25;
    /**
     * Extra height for Bedrock/Geyser viewers only, applied for the brief
     * window a tag is dismounted/remounted around a command (see
     * {@link #getBedrockDismountHeightAdjust()}). Found empirically on a
     * real Bedrock client — Geyser's own passenger-mount math for display
     * entities isn't publicly documented, so this can't be derived
     * analytically the way the Java-side offset can.
     */
    private static final double BEDROCK_DISMOUNT_HEIGHT_ADJUST = 0.15;

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
     * invalid in config.yml. 10 ticks (0.5s) gives teleports that don't
     * complete synchronously — e.g. cross-dimension moves that need to load
     * chunks or run a safe-spot search — enough of a buffer to actually
     * finish before the nametag remounts and blocks them again. This is
     * just a ceiling: {@link afx.customplayernametags.listener.PlayerConnectionListener#onWorldChange}
     * already closes the dismount window immediately once a world change
     * actually happens, so a generous value here doesn't mean the tag stays
     * visibly detached any longer for teleports that complete quickly.
     */
    private static final long DEFAULT_DISMOUNT_DURATION_TICKS = 10L;

    private String nametagFormat;
    private double nametagRenderDistance;
    private DismountMode nametagDismountMode;
    private List<List<String>> dismountCommands;
    private long dismountDurationTicks;

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
        this.dismountDurationTicks = configuredTicks > 0 ? configuredTicks : DEFAULT_DISMOUNT_DURATION_TICKS;

        List<List<String>> commands = new ArrayList<>();
        for (String raw : cfg.getStringList("dismount-commands")) {
            List<String> tokens = tokenizeCommand(raw);
            if (!tokens.isEmpty()) {
                commands.add(tokens);
            }
        }
        this.dismountCommands = commands;
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
     * Resets the global {@code nametag-format} back to the default (the
     * player's plain username). Backs {@code /nametags format reset global}.
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
     */
    public double getBedrockHeightAdjust() {
        return BEDROCK_HEIGHT_ADJUST;
    }

    /**
     * Extra height added on top of {@link #getBedrockHeightAdjust()} when a
     * Bedrock/Geyser viewer is looking at a sneaking player's tag (any
     * owner platform), since the Java/Bedrock rendering gap doesn't
     * necessarily stay constant across poses.
     *
     * <p>This stacks with (does not replace) {@link #getBedrockHeightAdjust()}:
     * with the shipped defaults (-0.10 and 0.25) a Bedrock viewer's crouch
     * gets a net +0.15 beyond the shared {@link #getSneakHeightAdjust()}.
     * Setting this equal to the negation of {@link #getBedrockHeightAdjust()}
     * makes the two cancel out, giving Bedrock viewers the exact same crouch
     * height as Java viewers.
     */
    public double getBedrockSneakHeightAdjust() {
        return BEDROCK_SNEAK_HEIGHT_ADJUST;
    }

    /**
     * Extra height (in blocks) added to a Bedrock/Geyser viewer's copy of
     * the tag specifically for the brief window while it's dismounted
     * (mid-command — see {@link afx.customplayernametags.manager.NametagDisplayManager#dismount}),
     * on top of the same fixed reference point Java viewers use for that
     * window.
     *
     * <p>This exists for a different reason than {@link #getBedrockHeightAdjust()}.
     * That one corrects the tag's height while continuously mounted, and is
     * already tuned against a real Bedrock client. This one corrects a
     * separate, unrelated number: the fixed reference height used only for
     * the instant the tag is un-mounted and re-mounted around a command.
     * That reference point is calibrated against vanilla Java's own
     * passenger-mounting math, which is public and predictable. Geyser has
     * to reimplement passenger mounting for Bedrock on its own — display
     * entities don't exist natively on Bedrock — and there's no public spec
     * for exactly what height Geyser's translation lands on, so there was
     * no way to compute the right value analytically the way the Java side
     * could be; {@link #BEDROCK_DISMOUNT_HEIGHT_ADJUST} was instead found
     * empirically by testing on a real Bedrock client.
     */
    public double getBedrockDismountHeightAdjust() {
        return BEDROCK_DISMOUNT_HEIGHT_ADJUST;
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