package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.format.TemplateMarkers;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigManager {

    private static final String DEFAULT_FORMAT = "{player}";

    /**
     * Line width passed to {@code YamlConfigurationOptions#width(int)} before every save — see
     * the comment in {@link #load()} for why this is a large bounded value rather than
     * {@code Integer.MAX_VALUE}. Far longer than any realistic single-line value this plugin
     * ever writes, so in practice this just means "don't wrap."
     */
    private static final int WIDE_LINE_WIDTH = 100_000;

    /**
     * Fallback for {@code nametag-format-changed-notify} if it's missing
     * from config.yml. See {@link #getFormatChangedNotifyFormat()}.
     */
    private static final String DEFAULT_FORMAT_CHANGED_NOTIFY =
            "&7Your nametag format was just changed by {player} to: \n{format}.";

    /**
     * Fallback for {@code nametag-format-disabled-notify} if it's missing
     * from config.yml. See {@link #getFormatDisabledNotifyFormat()}.
     */
    private static final String DEFAULT_FORMAT_DISABLED_NOTIFY =
            "&7Your custom nametag format has been cleared. Now using {type} format: \n{format}";

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
     * Height correction (in blocks), applied per format line beyond the
     * first, for Bedrock/Geyser viewers only — subtracted once per extra
     * rendered line so a multi-line tag's bottom line doesn't drift upward
     * off the player's head as more lines are added. Currently {@code 0}
     * (no correction applied) — not configurable.
     */
    private static final double BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST = 0.0;
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

    /**
     * Controls how a player's nametag looks/behaves while they're crouching.
     * Configured via {@code nametag-crouch-effect} in config.yml.
     */
    public enum CrouchEffect {
        /** The nametag looks the same as when the player is standing (no dim/grey while crouching). */
        NONE,
        /** The nametag dims and turns grey while crouching (existing default behavior). */
        DEFAULT,
        /** The nametag is hidden entirely while crouching. */
        HIDE;

        private static CrouchEffect parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DEFAULT;
            }
            try {
                return CrouchEffect.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DEFAULT;
            }
        }
    }

    /**
     * Controls whether a player is told in chat when someone else changes
     * their individual nametag format (via {@code /nametags format player
     * set} or an admin editing them through the GUI editor). Configured via
     * {@code nametag-format-notify-mode} in config.yml.
     */
    public enum NotifyMode {
        /** Always notify the affected player. */
        NOTIFY,
        /** Never notify the affected player. */
        SILENT,
        /**
         * Lets the admin decide per-change: {@code /nametags format player
         * set [announce|silent] <player> <format>} accepts an optional
         * {@code announce}/{@code silent} word right after {@code set} and
         * before the player's name (defaulting to notify if omitted). These two
         * extra arguments only exist in this mode, and only on {@code set} —
         * {@code disable} and the GUI editor have no such argument and always
         * notify in this mode.
         */
        BOTH;

        private static NotifyMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NOTIFY;
            }
            try {
                return NotifyMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NOTIFY;
            }
        }
    }

    /**
     * The kind of value a runtime-configurable {@code config.yml} entry
     * holds, for {@code /nametags config} — drives both how
     * {@link ConfigField#setter()} parses a typed value and what
     * {@code /nametags config <key> <TAB>} should suggest.
     */
    public enum ConfigValueType {
        STRING, BOOLEAN, INTEGER, LONG, DOUBLE, ENUM
    }

    /**
     * One {@code config.yml} entry that {@code /nametags config} is allowed
     * to view/change at runtime: its type, its currently-effective value as
     * a display string, the fixed set of values it accepts (only meaningful
     * for {@link ConfigValueType#ENUM}), and a setter that parses+validates
     * a raw typed-in string, applies it to the live in-memory field, and
     * persists it to config.yml — returning {@code false} (without applying
     * anything) if the raw value doesn't parse for this field's type.
     *
     * <p>Deliberately does not cover every key in config.yml — entries like
     * {@code dismount-commands} (a list, not a single scalar) or
     * {@code config-version} (an internal marker, not a real setting) are
     * left out entirely, since they can't be safely expressed as one typed
     * value or shouldn't be changed at runtime at all.
     */
    public record ConfigField(String key, ConfigValueType type, List<String> enumValues,
                              Supplier<String> getter, Predicate<String> setter) {
    }

    /**
     * Every {@code config.yml} entry {@code /nametags config} can view or
     * change at runtime, keyed by its config.yml key name and in a fixed,
     * predictable order (for the no-argument {@code /nametags config}
     * listing). Built once in the constructor; each entry's getter/setter
     * always reads/writes this instance's live fields, so it never goes
     * stale across a {@link #load()} or across further
     * {@code /nametags config} calls.
     */
    private final Map<String, ConfigField> configurableFields = new LinkedHashMap<>();

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
    /**
     * Whether {@link #load()} had to fall back {@link #nametagFormat} to the default
     * ({@code {player}}) because {@code nametag-format} in config.yml contained malformed
     * {@code {widget ...}...{/widget}} syntax (e.g. a missing {@code {/widget}} close tag) — see
     * {@link TemplateMarkers#hasMalformedWidgetTag}. Checked by {@code /nametags reload} right
     * after calling {@link #load()} so the admin who triggered the reload gets told about it,
     * rather than the fallback happening silently.
     */
    private boolean nametagFormatInvalidOnLastLoad;
    private double nametagRenderDistance;
    private DismountMode nametagDismountMode;
    private List<List<String>> dismountCommands;
    private long dismountDurationTicks;
    private boolean notifyMultiversePassengerModeDefault;
    /** Config-provided fine-tuning delta for bedrock-height-adjust — added on top of {@link #BAKED_IN_BEDROCK_HEIGHT_ADJUST}, not a replacement for it. */
    private double bedrockHeightAdjustConfig;
    /** Config-provided fine-tuning delta for bedrock-sneak-height-adjust — added on top of {@link #BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST}, not a replacement for it. */
    private double bedrockSneakHeightAdjustConfig;
    /** Config-provided fine-tuning delta for bedrock-per-line-height-adjust — added on top of {@link #BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST}, not a replacement for it. */
    private double bedrockPerLineHeightAdjustConfig;
    private double globalHeightAdjust;

    private boolean placeholderRefreshEnabled;
    private long placeholderRefreshIntervalTicks;
    private int nametagLineMaxCharacters;
    private boolean truncationEllipsisEnabled;
    /**
     * Whether {@link #isTruncationEllipsisEnabled()}'s {@code "..."} marker
     * also applies to a Widget's own character-limit truncation, separately
     * from every other line-limit truncation. See
     * {@link #isWidgetTruncationEllipsisEnabled()}.
     */
    private boolean widgetTruncationEllipsisEnabled;
    private CrouchEffect crouchEffect;
    private boolean nametagsThroughWallsEnabled;
    private boolean stopThroughWallsWhileCrouching;
    private boolean enableSeparateBedrockGlobalFormat;
    private String bedrockGlobalFormat;
    /** Same fallback-tracking as {@link #nametagFormatInvalidOnLastLoad}, for {@code bedrock-nametag-format} instead. */
    private boolean bedrockGlobalFormatInvalidOnLastLoad;
    private String bedrockNametagPrefix;
    private String bedrockNametagSuffix;
    private boolean bedrockAffixesOnGlobal;
    private boolean bedrockAffixesOnPlayer;
    private boolean bedrockAffixesOnGroup;
    private boolean separateBedrockGroupFormats;
    private boolean tablistFormatEnabled;
    private NotifyMode notifyMode;
    private String formatChangedNotify;
    private String formatDisabledNotify;

    public ConfigManager(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        registerConfigurableFields();
    }

    /**
     * Populates {@link #configurableFields} — see that field's javadoc.
     * Called once from the constructor; every getter/setter below closes
     * over {@code this}, not a snapshot of a field's value, so entries stay
     * correct across reloads.
     */
    private void registerConfigurableFields() {
        configurableFields.put("nametag-format", new ConfigField(
                "nametag-format", ConfigValueType.STRING, null,
                this::getNametagFormat,
                value -> { setGlobalFormat(value); return true; }));

        configurableFields.put("bedrock-nametag-format", new ConfigField(
                "bedrock-nametag-format", ConfigValueType.STRING, null,
                this::getBedrockGlobalFormat,
                value -> { setBedrockGlobalFormat(value); return true; }));

        configurableFields.put("enable-nametag-format-placeholder-refresh", new ConfigField(
                "enable-nametag-format-placeholder-refresh", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(placeholderRefreshEnabled),
                value -> setBooleanValue(value, this::setPlaceholderRefreshEnabled)));

        configurableFields.put("nametag-format-placeholder-refresh-interval-ticks", new ConfigField(
                "nametag-format-placeholder-refresh-interval-ticks", ConfigValueType.LONG, null,
                () -> String.valueOf(placeholderRefreshIntervalTicks),
                value -> setLongValue(value, this::setPlaceholderRefreshIntervalTicks)));

        configurableFields.put("enable-separate-bedrock-global-format", new ConfigField(
                "enable-separate-bedrock-global-format", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(enableSeparateBedrockGlobalFormat),
                value -> setBooleanValue(value, this::setSeparateBedrockGlobalFormatEnabled)));
        configurableFields.put("enable-separate-bedrock-group-formats", new ConfigField(
                "enable-separate-bedrock-group-formats", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(separateBedrockGroupFormats),
                value -> setBooleanValue(value, this::setSeparateBedrockGroupFormatsEnabled)));

        configurableFields.put("enable-tablist-format", new ConfigField(
                "enable-tablist-format", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(tablistFormatEnabled),
                value -> setBooleanValue(value, this::setTablistFormatEnabled)));

        configurableFields.put("nametag-line-max-characters", new ConfigField(
                "nametag-line-max-characters", ConfigValueType.INTEGER, null,
                () -> String.valueOf(nametagLineMaxCharacters),
                value -> setIntValue(value, this::setNametagLineMaxCharacters)));

        configurableFields.put("nametag-truncate-indicator", new ConfigField(
                "nametag-truncate-indicator", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(truncationEllipsisEnabled),
                value -> setBooleanValue(value, this::setTruncationEllipsisEnabled)));

        configurableFields.put("nametag-widget-truncate-indicator", new ConfigField(
                "nametag-widget-truncate-indicator", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(widgetTruncationEllipsisEnabled),
                value -> setBooleanValue(value, this::setWidgetTruncationEllipsisEnabled)));

        configurableFields.put("nametag-crouch-effect", new ConfigField(
                "nametag-crouch-effect", ConfigValueType.ENUM, enumNames(CrouchEffect.values()),
                () -> crouchEffect.name(),
                value -> setEnumValue(value, CrouchEffect.class, this::setCrouchEffect)));

        configurableFields.put("enable-nametags-through-walls", new ConfigField(
                "enable-nametags-through-walls", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(nametagsThroughWallsEnabled),
                value -> setBooleanValue(value, this::setNametagsThroughWallsEnabled)));

        configurableFields.put("stop-nametags-through-walls-while-crouching", new ConfigField(
                "stop-nametags-through-walls-while-crouching", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(stopThroughWallsWhileCrouching),
                value -> setBooleanValue(value, this::setStopThroughWallsWhileCrouching)));

        configurableFields.put("nametag-dismount-mode", new ConfigField(
                "nametag-dismount-mode", ConfigValueType.ENUM, enumNames(DismountMode.values()),
                () -> nametagDismountMode.name(),
                value -> setEnumValue(value, DismountMode.class, this::setNametagDismountMode)));

        configurableFields.put("dismount-duration-ticks", new ConfigField(
                "dismount-duration-ticks", ConfigValueType.LONG, null,
                () -> String.valueOf(dismountDurationTicks),
                value -> setLongValue(value, this::setDismountDurationTicks)));

        configurableFields.put("notify-multiverse-passenger-mode-default", new ConfigField(
                "notify-multiverse-passenger-mode-default", ConfigValueType.BOOLEAN, null,
                () -> String.valueOf(notifyMultiversePassengerModeDefault),
                value -> setBooleanValue(value, this::setNotifyMultiversePassengerModeDefault)));

        configurableFields.put("nametag-render-distance", new ConfigField(
                "nametag-render-distance", ConfigValueType.DOUBLE, null,
                () -> String.valueOf(nametagRenderDistance),
                value -> setDoubleValue(value, this::setNametagRenderDistance)));

        configurableFields.put("global-nametag-height-adjust", new ConfigField(
                "global-nametag-height-adjust", ConfigValueType.DOUBLE, null,
                () -> String.valueOf(globalHeightAdjust),
                value -> setDoubleValue(value, this::setGlobalHeightAdjust)));

        configurableFields.put("bedrock-height-adjust", new ConfigField(
                "bedrock-height-adjust", ConfigValueType.DOUBLE, null,
                () -> String.valueOf(bedrockHeightAdjustConfig),
                value -> setDoubleValue(value, this::setBedrockHeightAdjustConfig)));

        configurableFields.put("bedrock-sneak-height-adjust", new ConfigField(
                "bedrock-sneak-height-adjust", ConfigValueType.DOUBLE, null,
                () -> String.valueOf(bedrockSneakHeightAdjustConfig),
                value -> setDoubleValue(value, this::setBedrockSneakHeightAdjustConfig)));

        configurableFields.put("bedrock-per-line-height-adjust", new ConfigField(
                "bedrock-per-line-height-adjust", ConfigValueType.DOUBLE, null,
                () -> String.valueOf(bedrockPerLineHeightAdjustConfig),
                value -> setDoubleValue(value, this::setBedrockPerLineHeightAdjustConfig)));

        configurableFields.put("nametag-format-notify-mode", new ConfigField(
                "nametag-format-notify-mode", ConfigValueType.ENUM, enumNames(NotifyMode.values()),
                () -> notifyMode.name(),
                value -> setEnumValue(value, NotifyMode.class, this::setNotifyMode)));

        configurableFields.put("nametag-format-changed-notify", new ConfigField(
                "nametag-format-changed-notify", ConfigValueType.STRING, null,
                this::getFormatChangedNotifyFormat,
                value -> { setFormatChangedNotifyFormat(value); return true; }));

        configurableFields.put("nametag-format-disabled-notify", new ConfigField(
                "nametag-format-disabled-notify", ConfigValueType.STRING, null,
                this::getFormatDisabledNotifyFormat,
                value -> { setFormatDisabledNotifyFormat(value); return true; }));
    }

    private static List<String> enumNames(Enum<?>[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return Collections.unmodifiableList(names);
    }

    private static boolean setBooleanValue(String raw, java.util.function.Consumer<Boolean> apply) {
        if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
            return false;
        }
        apply.accept(Boolean.parseBoolean(raw));
        return true;
    }

    private static boolean setIntValue(String raw, java.util.function.IntConsumer apply) {
        try {
            apply.accept(Integer.parseInt(raw.trim()));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean setLongValue(String raw, java.util.function.LongConsumer apply) {
        try {
            apply.accept(Long.parseLong(raw.trim()));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean setDoubleValue(String raw, java.util.function.DoubleConsumer apply) {
        try {
            apply.accept(Double.parseDouble(raw.trim()));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static <E extends Enum<E>> boolean setEnumValue(String raw, Class<E> type, java.util.function.Consumer<E> apply) {
        try {
            apply.accept(Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Every {@code config.yml} key {@code /nametags config} can view or
     * change, in a fixed display order.
     */
    public List<String> getConfigurableKeys() {
        return new ArrayList<>(configurableFields.keySet());
    }

    /** The {@link ConfigField} for {@code key}, or {@code null} if it isn't runtime-configurable. */
    public ConfigField getConfigField(String key) {
        for (Map.Entry<String, ConfigField> entry : configurableFields.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Parses {@code rawValue} for {@code key} according to its
     * {@link ConfigField#type()}, applies it to the live in-memory value,
     * and persists it to config.yml — all through that field's own setter.
     * Returns {@code false} (without changing anything) if {@code key}
     * isn't runtime-configurable or {@code rawValue} doesn't parse for its
     * type.
     */
    public boolean setConfigValue(String key, String rawValue) {
        ConfigField field = getConfigField(key);
        if (field == null) {
            return false;
        }
        return field.setter().test(rawValue);
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // Bukkit's YamlConfiguration defaults to wrapping any scalar value past ~80
        // characters onto a folded continuation line (see saveConfig()'s javadoc for the
        // matching quote-style normalization pass). That default is purely cosmetic — the
        // wrapped and unwrapped forms decode to the exact same string — but it makes
        // long values like nametag-format-changed-notify get reflowed across multiple
        // lines any time saveConfig() runs, even for an edit to a completely different
        // key, which is confusing to read and noisy to diff. Setting an effectively
        // unbounded width here keeps every value on a single line. reloadConfig() above
        // hands back a fresh YamlConfiguration each time, so this has to be re-applied on
        // every load(), not just once at startup.
        // A width of Integer.MAX_VALUE was tried here first and, on at least one server,
        // produced a config.yml with a truncated/unterminated quoted scalar that then failed
        // to parse at all on the next boot (SnakeYAML mishandling an extreme width value while
        // deciding where a double-quoted scalar needs escaping) — so this deliberately uses a
        // large-but-bounded width instead: still far beyond any realistic single-line value in
        // this file, without relying on the emitter's behavior at the very edge of int range.
        // saveConfig() below also now verifies every write round-trips before trusting it, as a
        // second line of defense against exactly this kind of bad dump.
        if (cfg instanceof YamlConfiguration yamlCfg) {
            yamlCfg.options().width(WIDE_LINE_WIDTH);
        }

        // nametag-format/bedrock-nametag-format are stored, in config.yml and in memory alike, as
        // the same {widget ...}...{/widget} tag text (see TemplateMarkers) — there's no encoding
        // boundary here any more.
        //
        // A malformed {widget ...}...{/widget} tag (e.g. a missing close tag, or a stray capital
        // {Widget}) isn't recognized as a widget at all, so it would otherwise sail straight
        // through as literal, un-rendered "{widget ...}" text in every nametag — confusing, and
        // easy for an admin to not notice until a player reports it. Caught here instead: this
        // falls back to the default format and remembers that for /nametags reload to report back.
        String rawFormat = TemplateMarkers.ensureWidgetIds(cfg.getString("nametag-format", DEFAULT_FORMAT));
        this.nametagFormatInvalidOnLastLoad = TemplateMarkers.hasMalformedWidgetTag(rawFormat);
        this.nametagFormat = nametagFormatInvalidOnLastLoad ? DEFAULT_FORMAT : rawFormat;
        if (nametagFormatInvalidOnLastLoad) {
            plugin.getLogger().warning("config.yml's \"nametag-format\" has invalid {widget ...}...{/widget} "
                    + "syntax (a missing or mismatched tag, most likely) — falling back to the default "
                    + "(\"{player}\") until this is fixed. Check nametag-format for an unclosed {widget...} or "
                    + "{/widget} tag, then run /nametags reload again.");
        }

        // Default: 64 blocks, vanilla's own fixed cutoff for rendering any
        // entity nametag regardless of server entity-tracking-range settings.
        this.nametagRenderDistance = cfg.getDouble("nametag-render-distance", 64.0);

        this.nametagDismountMode = DismountMode.parse(cfg.getString("nametag-dismount-mode", "AUTO"));

        long configuredTicks = cfg.getLong("dismount-duration-ticks", DEFAULT_DISMOUNT_DURATION_TICKS);
        this.dismountDurationTicks = Math.max(configuredTicks, MIN_DISMOUNT_DURATION_TICKS);

        this.notifyMultiversePassengerModeDefault = cfg.getBoolean("notify-multiverse-passenger-mode-default", true);

        List<List<String>> commands = new ArrayList<>();
        for (String raw : cfg.getStringList("dismount-commands")) {
            List<String> tokens = tokenizeCommand(raw);
            if (!tokens.isEmpty()) {
                commands.add(tokens);
            }
        }
        this.dismountCommands = commands;

        // These are pure fine-tuning deltas layered on top of the
        // always-applied BAKED_IN_BEDROCK_HEIGHT_ADJUST /
        // BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST /
        // BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST constants below (see
        // getBedrockHeightAdjust() / getBedrockSneakHeightAdjust() /
        // getBedrockPerLineHeightAdjust()) — the fallback here is
        // deliberately 0.0, meaning "no extra adjustment on top of the
        // built-in correction", not "no correction at all".
        this.bedrockHeightAdjustConfig = cfg.getDouble("bedrock-height-adjust", 0.0);
        this.bedrockSneakHeightAdjustConfig = cfg.getDouble("bedrock-sneak-height-adjust", 0.0);
        this.bedrockPerLineHeightAdjustConfig = cfg.getDouble("bedrock-per-line-height-adjust", 0.0);
        this.globalHeightAdjust = cfg.getDouble("global-nametag-height-adjust", DEFAULT_GLOBAL_HEIGHT_ADJUST);

        this.placeholderRefreshEnabled = cfg.getBoolean("enable-nametag-format-placeholder-refresh", true);
        long configuredRefreshTicks = cfg.getLong("nametag-format-placeholder-refresh-interval-ticks", 20L);
        this.placeholderRefreshIntervalTicks = Math.max(configuredRefreshTicks, 1L);

        this.nametagLineMaxCharacters = Math.max(cfg.getInt("nametag-line-max-characters", 64), 1);
        this.truncationEllipsisEnabled = cfg.getBoolean("nametag-truncate-indicator", true);
        this.widgetTruncationEllipsisEnabled = cfg.getBoolean("nametag-widget-truncate-indicator", true);

        this.crouchEffect = CrouchEffect.parse(cfg.getString("nametag-crouch-effect", "DEFAULT"));
        this.nametagsThroughWallsEnabled = cfg.getBoolean("enable-nametags-through-walls", true);
        this.stopThroughWallsWhileCrouching = cfg.getBoolean("stop-nametags-through-walls-while-crouching", true);

        // Default mirrors the bundled config.yml (false) — only reached if the
        // key was deleted from an existing file.
        this.enableSeparateBedrockGlobalFormat = cfg.getBoolean("enable-separate-bedrock-global-format", false);
        String rawBedrockFormat = TemplateMarkers.ensureWidgetIds(cfg.getString("bedrock-nametag-format", DEFAULT_FORMAT));
        this.bedrockGlobalFormatInvalidOnLastLoad = TemplateMarkers.hasMalformedWidgetTag(rawBedrockFormat);
        this.bedrockGlobalFormat = bedrockGlobalFormatInvalidOnLastLoad ? DEFAULT_FORMAT : rawBedrockFormat;
        if (bedrockGlobalFormatInvalidOnLastLoad) {
            plugin.getLogger().warning("config.yml's \"bedrock-nametag-format\" has invalid "
                    + "{widget ...}...{/widget} syntax (a missing or mismatched tag, most likely) — falling back "
                    + "to the default (\"{player}\") until this is fixed. Check bedrock-nametag-format for an "
                    + "unclosed {widget...} or {/widget} tag, then run /nametags reload again.");
        }
        this.bedrockNametagPrefix = cfg.getString("bedrock-nametag-prefix", "");
        this.bedrockNametagSuffix = cfg.getString("bedrock-nametag-suffix", "");
        this.bedrockAffixesOnGlobal = cfg.getBoolean("apply-bedrock-prefix-suffix-to-global-format", true);
        this.bedrockAffixesOnPlayer = cfg.getBoolean("apply-bedrock-prefix-suffix-to-player-format", true);
        this.bedrockAffixesOnGroup = cfg.getBoolean("apply-bedrock-prefix-suffix-to-group-format", true);
        this.separateBedrockGroupFormats = cfg.getBoolean("enable-separate-bedrock-group-formats", false);

        this.tablistFormatEnabled = cfg.getBoolean("enable-tablist-format", true);

        this.notifyMode = NotifyMode.parse(cfg.getString("nametag-format-notify-mode", "NOTIFY"));
        this.formatChangedNotify = cfg.getString("nametag-format-changed-notify", DEFAULT_FORMAT_CHANGED_NOTIFY);
        this.formatDisabledNotify = cfg.getString("nametag-format-disabled-notify", DEFAULT_FORMAT_DISABLED_NOTIFY);

        migrateWidgetSyntax(cfg.getString("nametag-format", DEFAULT_FORMAT),
                cfg.getString("bedrock-nametag-format", DEFAULT_FORMAT));
    }

    /**
     * One-time upgrade path for an existing install whose {@code config.yml}
     * still has {@code nametag-format}/{@code bedrock-nametag-format} with a
     * widget tag that's missing the {@code id=} attribute the widget-fill system now keys on
     * (see {@link TemplateMarkers#needsIdAssignment}) — e.g. a widget an
     * admin hand-typed, or one written by a version of the plugin that
     * predates ids. Rewrites just those two keys in place — same pattern as
     * {@link ConfigMigrator} — so a server owner opening the file sees
     * readable tags with ids. The in-memory values were already assigned by
     * {@link #load()}, so this only has to persist them. No-ops (and never
     * touches the file) if neither key needs a rewrite, which
     * is the case for every load after the first one following an upgrade.
     */
    private void migrateWidgetSyntax(String storedFormat, String storedBedrockFormat) {
        boolean needsFormat = TemplateMarkers.needsIdAssignment(storedFormat);
        boolean needsBedrockFormat = TemplateMarkers.needsIdAssignment(storedBedrockFormat);
        if (!needsFormat && !needsBedrockFormat) {
            return;
        }
        if (needsFormat) {
            plugin.getConfig().set("nametag-format", nametagFormat);
        }
        if (needsBedrockFormat) {
            plugin.getConfig().set("bedrock-nametag-format", bedrockGlobalFormat);
        }
        saveConfig();
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
     * Whether the most recent {@link #load()} had to fall {@link #getNametagFormat()} back to the
     * default because {@code nametag-format} in config.yml had malformed
     * {@code {widget ...}...{/widget}} syntax. {@code /nametags reload} checks this right after
     * calling {@link #load()} so the fallback is reported to whoever triggered the reload, not
     * just logged. Stays true until the next {@link #load()} with a valid format.
     */
    public boolean wasNametagFormatInvalidOnLastLoad() {
        return nametagFormatInvalidOnLastLoad;
    }

    /**
     * Sets the global {@code nametag-format} to {@code format}, persists it
     * to config.yml, and updates the in-memory value immediately (no reload
     * needed). Passing {@code null} or an empty string resets it back to the
     * default. Backs {@code /nametags format set global}.
     */
    public void setGlobalFormat(String format) {
        String raw = (format == null || format.isEmpty()) ? DEFAULT_FORMAT : format;
        // The GUI editor's serialized form and a format an admin typed directly (e.g. via
        // /nametags config) are now one and the same {widget ...}...{/widget} tag text, so this
        // stores exactly what it was given, assigning an id to any widget
        // that doesn't already have one (a hand-typed {widget...} tag, most likely).
        String value = TemplateMarkers.ensureWidgetIds(raw);
        this.nametagFormat = value;
        plugin.getConfig().set("nametag-format", value);
        saveConfig();
    }

    /**
     * Resets the global {@code nametag-format} back to the default
     * ({@code {player}}). Backs {@code /nametags format global reset}.
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
     * Raw {@code bedrock-height-adjust} value as configured in config.yml,
     * without {@link #BAKED_IN_BEDROCK_HEIGHT_ADJUST} added in. Exposed
     * only for the bStats custom chart in
     * {@link afx.customplayernametags.CustomPlayerNametags#onEnable()},
     * which wants to know what a server owner actually configured, not the
     * effective value that also includes the built-in constant.
     */
    public double getBedrockHeightAdjustConfig() {
        return bedrockHeightAdjustConfig;
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
     * Raw {@code bedrock-sneak-height-adjust} value as configured in
     * config.yml, without {@link #BAKED_IN_BEDROCK_SNEAK_HEIGHT_ADJUST}
     * added in. Exposed only for the bStats custom chart in
     * {@link afx.customplayernametags.CustomPlayerNametags#onEnable()} —
     * see {@link #getBedrockHeightAdjustConfig()}.
     */
    public double getBedrockSneakHeightAdjustConfig() {
        return bedrockSneakHeightAdjustConfig;
    }

    /**
     * Height (in blocks) subtracted once per line beyond a tag's first,
     * for Bedrock/Geyser viewers only, so a multi-line tag's bottom line
     * doesn't drift away from the configured height as more lines are
     * added.
     *
     * <p>Equal to {@link #BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST}
     * (currently {@code 0} — no correction applied by default) plus
     * whatever fine-tuning delta is configured via
     * {@code bedrock-per-line-height-adjust} in config.yml — see
     * {@link #getBedrockHeightAdjust()} for why this is additive rather
     * than a straight replacement.
     */
    public double getBedrockPerLineHeightAdjust() {
        return BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST + bedrockPerLineHeightAdjustConfig;
    }

    /**
     * Raw {@code bedrock-per-line-height-adjust} value as configured in
     * config.yml, without {@link #BAKED_IN_BEDROCK_PER_LINE_HEIGHT_ADJUST}
     * added in. Exposed only for the bStats custom chart in
     * {@link afx.customplayernametags.CustomPlayerNametags#onEnable()} —
     * see {@link #getBedrockHeightAdjustConfig()}.
     */
    public double getBedrockPerLineHeightAdjustConfig() {
        return bedrockPerLineHeightAdjustConfig;
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
     * Whether an admin should be warned in chat, on join, when
     * Multiverse-Core is installed and its own {@code teleport.passenger-mode}
     * config value is still left at the out-of-the-box {@code default}
     * setting. Configured via {@code notify-multiverse-passenger-mode-default}
     * in config.yml (default {@code true}); set to {@code false} to never
     * show that notice, e.g. once it's already been addressed or a server
     * owner doesn't want the reminder.
     */
    public boolean isNotifyMultiversePassengerModeDefault() {
        return notifyMultiversePassengerModeDefault;
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

    /** Whether the periodic nametag-format placeholder refresh task runs at all. Configured via {@code enable-nametag-format-placeholder-refresh}. */
    public boolean isPlaceholderRefreshEnabled() {
        return placeholderRefreshEnabled;
    }

    /** How often (in ticks) the placeholder refresh task re-checks every online player's nametag. Configured via {@code nametag-format-placeholder-refresh-interval-ticks}. Floored at 1. */
    public long getPlaceholderRefreshIntervalTicks() {
        return placeholderRefreshIntervalTicks;
    }

    /** Maximum characters allowed on an individual nametag line unless bypassed. */
    public int getNametagLineMaxCharacters() {
        return nametagLineMaxCharacters;
    }

    /**
     * Whether a plain white {@code "..."} is appended when a nametag line
     * (or a widget's own contents, if it has a character limit) gets cut
     * down to fit its character limit. Configured via
     * {@code nametag-truncate-indicator}; on by default. Turning this off
     * still truncates exactly the same text — just without the trailing
     * marker that something was cut.
     */
    public boolean isTruncationEllipsisEnabled() {
        return truncationEllipsisEnabled;
    }

    /**
     * Whether a Widget's own character-limit truncation (set via that
     * Widget's Characters item — see {@code widget-toggles-menu}'s
     * "Character Limit" button) appends the same plain white {@code "..."}
     * marker as {@link #isTruncationEllipsisEnabled()}, independently of
     * that setting. Configured via {@code nametag-widget-truncate-indicator};
     * on by default. Turning this off still truncates a Widget's contents
     * down to its limit exactly the same — just without the trailing
     * marker that something was cut.
     */
    public boolean isWidgetTruncationEllipsisEnabled() {
        return widgetTruncationEllipsisEnabled;
    }

    /** Which crouch-effect behavior is configured via {@code nametag-crouch-effect}. */
    public CrouchEffect getCrouchEffect() {
        return crouchEffect;
    }

    /** Whether nametags are allowed to render through walls at all (Java viewers only). Configured via {@code enable-nametags-through-walls}. */
    public boolean isNametagsThroughWallsEnabled() {
        return nametagsThroughWallsEnabled;
    }

    /** Whether crouching forces "through walls" rendering off even when {@link #isNametagsThroughWallsEnabled()} is true. Configured via {@code stop-nametags-through-walls-while-crouching}. */
    public boolean isStopThroughWallsWhileCrouching() {
        return stopThroughWallsWhileCrouching;
    }

    /** Whether Bedrock players use a separate global format instead of the normal global format. Configured via {@code enable-separate-bedrock-global-format}. Never overrides an individual/player or group format. */
    public boolean isSeparateBedrockGlobalFormatEnabled() {
        return enableSeparateBedrockGlobalFormat;
    }

    /**
     * The raw, unparsed Bedrock global format — exactly as
     * {@code bedrock-nametag-format} is written on disk, widgets included
     * (see {@link TemplateMarkers}).
     */
    public String getBedrockGlobalFormat() {
        return bedrockGlobalFormat;
    }

    /**
     * Same fallback-reporting as {@link #wasNametagFormatInvalidOnLastLoad()}, for
     * {@code bedrock-nametag-format} instead.
     */
    public boolean wasBedrockGlobalFormatInvalidOnLastLoad() {
        return bedrockGlobalFormatInvalidOnLastLoad;
    }

    /**
     * Sets the Bedrock global {@code bedrock-nametag-format} to {@code format}, persists it
     * to config.yml, and updates the in-memory value immediately. Passing {@code null} or an
     * empty string resets it back to the default.
     */
    public void setBedrockGlobalFormat(String format) {
        String raw = (format == null || format.isEmpty()) ? DEFAULT_FORMAT : format;
        String value = TemplateMarkers.ensureWidgetIds(raw);
        this.bedrockGlobalFormat = value;
        plugin.getConfig().set("bedrock-nametag-format", value);
        saveConfig();
    }

    /**
     * Resets the Bedrock global {@code bedrock-nametag-format} back to the default
     * ({@code {player}}). Backs {@code /nametags format global reset bedrock}. Mirrors
     * {@link #resetGlobalFormat()} for the Java-side {@code nametag-format}.
     */
    public void resetBedrockGlobalFormat() {
        setBedrockGlobalFormat(DEFAULT_FORMAT);
    }

    /**
     * Whether each player's tab list entry should be kept in sync with
     * their currently-applied nametag format (individual/group/Bedrock
     * global/global, same priority and same resolved text as the overhead
     * nametag). Configured via {@code enable-tablist-format}. When
     * disabled, the tab list is left completely alone (default vanilla
     * username entries).
     */
    public boolean isTablistFormatEnabled() {
        return tablistFormatEnabled;
    }

    public String getBedrockNametagPrefix() { return bedrockNametagPrefix; }
    public String getBedrockNametagSuffix() { return bedrockNametagSuffix; }
    public boolean isBedrockAffixAppliedToGlobal() { return bedrockAffixesOnGlobal; }
    public boolean isBedrockAffixAppliedToPlayer() { return bedrockAffixesOnPlayer; }
    public boolean isBedrockAffixAppliedToGroup() { return bedrockAffixesOnGroup; }
    public boolean isSeparateBedrockGroupFormatsEnabled() { return separateBedrockGroupFormats; }
    public void setSeparateBedrockGroupFormatsEnabled(boolean enabled) {
        this.separateBedrockGroupFormats = enabled;
        plugin.getConfig().set("enable-separate-bedrock-group-formats", enabled);
        saveConfig();
    }

    // ------------------------------------------------------------------
    // Runtime setters — back /nametags config <key> <value>. Each updates
    // the live in-memory field immediately and persists to config.yml, the
    // same pattern already used by setGlobalFormat()/setBedrockGlobalFormat()
    // above.
    // ------------------------------------------------------------------

    /** Sets {@code enable-nametag-format-placeholder-refresh} and persists it. */
    public void setPlaceholderRefreshEnabled(boolean enabled) {
        this.placeholderRefreshEnabled = enabled;
        plugin.getConfig().set("enable-nametag-format-placeholder-refresh", enabled);
        saveConfig();
    }

    /** Sets {@code nametag-format-placeholder-refresh-interval-ticks} (floored at 1) and persists it. */
    public void setPlaceholderRefreshIntervalTicks(long ticks) {
        this.placeholderRefreshIntervalTicks = Math.max(ticks, 1L);
        plugin.getConfig().set("nametag-format-placeholder-refresh-interval-ticks", this.placeholderRefreshIntervalTicks);
        saveConfig();
    }

    /** Sets {@code nametag-line-max-characters} (floored at 1) and persists it. */
    public void setNametagLineMaxCharacters(int limit) {
        this.nametagLineMaxCharacters = Math.max(limit, 1);
        plugin.getConfig().set("nametag-line-max-characters", this.nametagLineMaxCharacters);
        saveConfig();
    }

    /** Sets {@code nametag-truncate-indicator} and persists it. */
    public void setTruncationEllipsisEnabled(boolean enabled) {
        this.truncationEllipsisEnabled = enabled;
        plugin.getConfig().set("nametag-truncate-indicator", enabled);
        saveConfig();
    }

    /** Sets {@code nametag-widget-truncate-indicator} (see {@link #isWidgetTruncationEllipsisEnabled()}) and persists it. */
    public void setWidgetTruncationEllipsisEnabled(boolean enabled) {
        this.widgetTruncationEllipsisEnabled = enabled;
        plugin.getConfig().set("nametag-widget-truncate-indicator", enabled);
        saveConfig();
    }

    /** Sets {@code nametag-crouch-effect} and persists it. */
    public void setCrouchEffect(CrouchEffect effect) {
        this.crouchEffect = effect;
        plugin.getConfig().set("nametag-crouch-effect", effect.name());
        saveConfig();
    }

    /** Sets {@code enable-nametags-through-walls} and persists it. */
    public void setNametagsThroughWallsEnabled(boolean enabled) {
        this.nametagsThroughWallsEnabled = enabled;
        plugin.getConfig().set("enable-nametags-through-walls", enabled);
        saveConfig();
    }

    /** Sets {@code stop-nametags-through-walls-while-crouching} and persists it. */
    public void setStopThroughWallsWhileCrouching(boolean enabled) {
        this.stopThroughWallsWhileCrouching = enabled;
        plugin.getConfig().set("stop-nametags-through-walls-while-crouching", enabled);
        saveConfig();
    }

    /** Sets {@code enable-separate-bedrock-global-format} and persists it. */
    public void setSeparateBedrockGlobalFormatEnabled(boolean enabled) {
        this.enableSeparateBedrockGlobalFormat = enabled;
        plugin.getConfig().set("enable-separate-bedrock-global-format", enabled);
        saveConfig();
    }

    /** Sets {@code enable-tablist-format} and persists it. */
    public void setTablistFormatEnabled(boolean enabled) {
        this.tablistFormatEnabled = enabled;
        plugin.getConfig().set("enable-tablist-format", enabled);
        saveConfig();
    }

    /**
     * Whether/how a player is notified in chat when someone else changes
     * their individual nametag format. See {@link NotifyMode}.
     */
    public NotifyMode getNotifyMode() {
        return notifyMode;
    }

    /** Sets {@code nametag-format-notify-mode} and persists it. */
    public void setNotifyMode(NotifyMode mode) {
        this.notifyMode = mode;
        plugin.getConfig().set("nametag-format-notify-mode", mode.name());
        saveConfig();
    }

    /**
     * The unparsed {@code nametag-format-changed-notify} message template
     * sent to a player when someone else changes their individual nametag
     * format — see config.yml's own comment for the {@code {player}}/
     * {@code {format}} tokens and PlaceholderAPI support this is expected
     * to go through before being sent.
     */
    public String getFormatChangedNotifyFormat() {
        return formatChangedNotify;
    }

    /** Sets {@code nametag-format-changed-notify} and persists it. Passing {@code null} or an empty string resets it back to the default. */
    public void setFormatChangedNotifyFormat(String format) {
        String value = (format == null || format.isEmpty()) ? DEFAULT_FORMAT_CHANGED_NOTIFY : format;
        this.formatChangedNotify = value;
        plugin.getConfig().set("nametag-format-changed-notify", value);
        saveConfig();
    }

    /**
     * The unparsed {@code nametag-format-disabled-notify} message template
     * sent to a player when someone disables their individual nametag
     * format override — see config.yml's own comment for the
     * {@code {player}}/{@code {type}}/{@code {format}} tokens and
     * PlaceholderAPI support this is expected to go through before being
     * sent.
     */
    public String getFormatDisabledNotifyFormat() {
        return formatDisabledNotify;
    }

    /** Sets {@code nametag-format-disabled-notify} and persists it. Passing {@code null} or an empty string resets it back to the default. */
    public void setFormatDisabledNotifyFormat(String format) {
        String value = (format == null || format.isEmpty()) ? DEFAULT_FORMAT_DISABLED_NOTIFY : format;
        this.formatDisabledNotify = value;
        plugin.getConfig().set("nametag-format-disabled-notify", value);
        saveConfig();
    }

    /** Sets {@code nametag-dismount-mode} and persists it. */
    public void setNametagDismountMode(DismountMode mode) {
        this.nametagDismountMode = mode;
        plugin.getConfig().set("nametag-dismount-mode", mode.name());
        saveConfig();
    }

    /** Sets {@code dismount-duration-ticks} (floored at {@link #MIN_DISMOUNT_DURATION_TICKS}) and persists it. */
    public void setDismountDurationTicks(long ticks) {
        this.dismountDurationTicks = Math.max(ticks, MIN_DISMOUNT_DURATION_TICKS);
        plugin.getConfig().set("dismount-duration-ticks", this.dismountDurationTicks);
        saveConfig();
    }

    /** Sets {@code notify-multiverse-passenger-mode-default} and persists it. */
    public void setNotifyMultiversePassengerModeDefault(boolean enabled) {
        this.notifyMultiversePassengerModeDefault = enabled;
        plugin.getConfig().set("notify-multiverse-passenger-mode-default", enabled);
        saveConfig();
    }

    /** Sets {@code nametag-render-distance} and persists it. */
    public void setNametagRenderDistance(double distance) {
        this.nametagRenderDistance = distance;
        plugin.getConfig().set("nametag-render-distance", distance);
        saveConfig();
    }

    /** Sets {@code global-nametag-height-adjust} and persists it. */
    public void setGlobalHeightAdjust(double adjust) {
        this.globalHeightAdjust = adjust;
        plugin.getConfig().set("global-nametag-height-adjust", adjust);
        saveConfig();
    }

    /** Sets the fine-tuning delta for {@code bedrock-height-adjust} (see {@link #getBedrockHeightAdjust()}) and persists it. */
    public void setBedrockHeightAdjustConfig(double adjust) {
        this.bedrockHeightAdjustConfig = adjust;
        plugin.getConfig().set("bedrock-height-adjust", adjust);
        saveConfig();
    }

    /** Sets the fine-tuning delta for {@code bedrock-sneak-height-adjust} (see {@link #getBedrockSneakHeightAdjust()}) and persists it. */
    public void setBedrockSneakHeightAdjustConfig(double adjust) {
        this.bedrockSneakHeightAdjustConfig = adjust;
        plugin.getConfig().set("bedrock-sneak-height-adjust", adjust);
        saveConfig();
    }

    /** Sets the fine-tuning delta for {@code bedrock-per-line-height-adjust} (see {@link #getBedrockPerLineHeightAdjust()}) and persists it. */
    public void setBedrockPerLineHeightAdjustConfig(double adjust) {
        this.bedrockPerLineHeightAdjustConfig = adjust;
        plugin.getConfig().set("bedrock-per-line-height-adjust", adjust);
        saveConfig();
    }

    /** Matches a flat {@code key: 'value'} line — the shape Bukkit's own YAML serializer writes for any string value that needs quoting (this plugin's config.yml has no nested/indented keys, so this is safe to apply top-level only). Captures the {@code key: } prefix and the quoted value separately so the value's inner {@code ''}-escaped quotes can be un-escaped before being re-escaped for double quotes. */
    private static final Pattern SINGLE_QUOTED_SCALAR = Pattern.compile("^([^\\s#][^:]*:\\s+)'(.*)'$");

    /**
     * Matches a flat {@code key: value} line whose value is a bare, unquoted plain scalar — the
     * shape Bukkit's own YAML serializer writes for any string value that happens not to contain
     * anything YAML requires quoting for (no leading {@code {}, no leading {@code &}, no
     * {@code "to: "}-style colon-space, etc. — e.g. a {@code nametag-format} of
     * {@code Player: {player}} written without a leading brace). Deliberately excludes values
     * already starting with {@code '} or
     * {@code "}, which {@link #SINGLE_QUOTED_SCALAR} (or nothing at all, for values already
     * double-quoted) already covers. Only ever applied to keys already known to be STRING-typed
     * config fields (see {@link #stringConfigKeys()}), so numbers, booleans, enum values (like
     * {@code nametag-format-notify-mode: NOTIFY}, which is meant to stay bare) and anything else
     * in the file are never touched.
     */
    private static final Pattern PLAIN_SCALAR = Pattern.compile("^([^\\s#][^:]*:\\s+)([^\"'\\s].*)$");

    /**
     * {@code plugin.saveConfig()} persists through Bukkit's own YAML
     * serializer, which defaults to wrapping any string value that needs
     * quoting (anything with a {@code &}/MiniMessage tag, a colon, etc. —
     * i.e. most of what actually gets set here) in single quotes rather
     * than the double quotes every value in the bundled config.yml uses.
     * Every setter in this class persists through here instead of calling
     * {@code plugin.saveConfig()} directly, so a value it just wrote out
     * always comes back re-quoted to match the rest of the file — done as
     * a plain-text pass over the saved file (like {@link ConfigUpdater})
     * rather than a second YAML re-serialization, so nothing else about
     * the file's formatting is disturbed.
     *
     * <p>Before doing either of those things, this refuses to touch the
     * file at all unless {@link #configLooksIntact()} — see that method
     * for why: without this guard, a config.yml that's become invalid YAML
     * out from under the plugin (a bad hand-edit, or a crash mid-write)
     * gets silently treated by Bukkit as an empty config, and the very
     * next call through here — setting one single value — would then
     * overwrite the real file with just that one key, destroying every
     * other setting in it. This is the one place in the whole class that
     * can permanently overwrite config.yml, so it's the one place that
     * needs to check the file is actually in a state worth writing back.
     *
     * <p>{@code plugin.saveConfig()} itself never checks that what it just wrote can actually be
     * read back — it dumps straight to disk and returns. That's normally fine, but it means a
     * rare bad dump (see the width comment in {@link #load()}) would otherwise sit on disk as a
     * silent time bomb: nothing notices until the next thing tries to load config.yml, which
     * might not be until the server's next restart — at which point the pre-save file, and every
     * setting in it, is already gone. So this snapshots the file's current text first and, right
     * after {@code plugin.saveConfig()} runs, re-loads what actually landed on disk and checks it
     * round-trips ({@link #fileSavedCleanly}) before doing anything else with it. A failed
     * round-trip restores that snapshot instead of leaving the bad write in place — the one
     * change that was being saved is lost, but every other setting survives, and the server can
     * still boot.
     */
    private void saveConfig() {
        if (!configLooksIntact()) {
            plugin.getLogger().severe("Refusing to save config.yml — the loaded configuration is missing keys "
                    + "it should always have (e.g. \"config-version\"), which almost always means config.yml has "
                    + "become invalid YAML and Bukkit silently loaded it as empty. Nothing has been written. "
                    + "Check config.yml for a syntax mistake (an unclosed quote is the usual cause), fix or "
                    + "restore it, then run /nametags reload and try this change again.");
            return;
        }
        File file = new File(plugin.getDataFolder(), "config.yml");
        String before = readFileQuietly(file);
        plugin.saveConfig();
        if (!fileSavedCleanly(file)) {
            plugin.getLogger().severe("plugin.saveConfig() just wrote a config.yml that doesn't parse back "
                    + "cleanly (this has happened with certain long values — see the width comment in load()). "
                    + "The change you just made was NOT saved, and the previous config.yml has been restored so "
                    + "the server can still start up normally. Please report this to the plugin author.");
            if (before != null) {
                try {
                    Files.writeString(file.toPath(), before, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    plugin.getLogger().severe("Additionally failed to restore the previous config.yml: "
                            + e.getMessage() + " — config.yml on disk may now be broken; back it up or restore it "
                            + "by hand before the server's next restart.");
                }
            }
            return;
        }
        normalizeQuoting(file);
        warnIfStillSingleQuoted(file);
    }

    /**
     * {@code file}'s current full text, or {@code null} if it doesn't exist yet or can't be read
     * — the only case this is expected on a real install is the very first save of a brand-new
     * config.yml, immediately after {@code saveDefaultConfig()} first creates it. See
     * {@link #saveConfig()}, which treats {@code null} as "nothing to restore."
     */
    private String readFileQuietly(File file) {
        if (!file.exists()) {
            return null;
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether {@code file}, immediately after {@code plugin.saveConfig()} just wrote it, is
     * valid YAML that still has every key a real config.yml always has — the same
     * {@code "config-version"} canary {@link #configLooksIntact()} checks on the live in-memory
     * config, but re-checked here against a fresh {@link YamlConfiguration} loaded straight from
     * disk, since what matters at this point is specifically what actually landed in the file.
     */
    private boolean fileSavedCleanly(File file) {
        return YamlConfiguration.loadConfiguration(file).isSet("config-version");
    }

    /**
     * Every {@code config.yml} key {@code /nametags config} treats as a plain string — the ones
     * a server owner is actually likely to open this file and read/edit, e.g.
     * {@code nametag-format} and {@code nametag-format-changed-notify} — is meant to always end
     * up double-quoted, matching every other value in the bundled file. {@link #normalizeQuoting}
     * is what actually does that conversion, but it deliberately skips its own rewrite rather
     * than risk committing one that doesn't provably round-trip (see
     * {@link #rewriteRoundTrips}) — safe, but silent, and would otherwise leave a value quietly
     * sitting in Bukkit's default single-quoted style with nothing surfacing it. This re-scans
     * {@code file} right after {@link #normalizeQuoting} has had its chance and logs a clear,
     * specific warning for any of those keys still found in single quotes, or still found
     * completely unquoted, so that failure mode is never silent even though it's harmless to how
     * the plugin itself reads the value.
     */
    private void warnIfStillSingleQuoted(File file) {
        List<String> stringKeys = stringConfigKeys();
        if (stringKeys.isEmpty()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                Matcher matcher = SINGLE_QUOTED_SCALAR.matcher(line);
                String bareKey;
                String quoteDescription;
                if (matcher.matches()) {
                    bareKey = matcher.group(1).replaceFirst(":\\s+$", "");
                    quoteDescription = "in single quotes";
                } else {
                    Matcher plain = PLAIN_SCALAR.matcher(line);
                    if (!plain.matches()) {
                        continue;
                    }
                    bareKey = plain.group(1).replaceFirst(":\\s+$", "");
                    quoteDescription = "completely unquoted";
                }
                if (stringKeys.contains(bareKey)) {
                    plugin.getLogger().warning("config.yml's \"" + bareKey + "\" is saved " + quoteDescription
                            + " instead of double quotes — its value didn't provably round-trip through the "
                            + "usual re-quoting pass (see any warning just above this one from normalizeQuoting) "
                            + "so it was left as-is rather than risk rewriting it into something broken. The "
                            + "plugin reads it correctly either way; this only affects how it looks if you open "
                            + "config.yml by hand. Run /nametags reload after checking the value for anything "
                            + "unusual, then change it again via /nametags config to retry the conversion.");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not check config.yml's quote style: " + e.getMessage());
        }
    }

    /** The keys of every {@code config.yml} field {@link #configurableFields} tracks as a plain string (e.g. {@code nametag-format}, {@code nametag-format-changed-notify}) — the set {@link #normalizeQuoting} and {@link #warnIfStillSingleQuoted} both restrict their re-quoting/warning to, so enum/number/boolean fields are never touched. */
    private List<String> stringConfigKeys() {
        List<String> stringKeys = new ArrayList<>();
        for (ConfigField field : configurableFields.values()) {
            if (field.type() == ConfigValueType.STRING) {
                stringKeys.add(field.key());
            }
        }
        return stringKeys;
    }

    /**
     * Whether {@code plugin.getConfig()} looks like a real, fully-parsed
     * config.yml rather than the empty {@link FileConfiguration} Bukkit
     * substitutes when the on-disk file fails to parse as YAML (see
     * {@link #saveConfig()}). {@code config-version} specifically is a
     * good canary for this: it's written into every install's file by
     * {@code saveDefaultConfig()} on first run and force-kept in sync by
     * {@link ConfigMigrator} on every subsequent one (see
     * {@code forceBundledValue}), so its absence here means the file that
     * was actually on disk at the last {@code reloadConfig()} either had
     * no content at all or didn't parse — never "a server owner happened
     * not to set it", since nothing about it is meant to be user-editable.
     */
    private boolean configLooksIntact() {
        return plugin.getConfig().isSet("config-version");
    }

    /**
     * Rewrites every top-level {@code key: 'value'} line in {@code file} to
     * {@code key: "value"}, and every top-level unquoted {@code key: value}
     * line for a known string-typed key to {@code key: "value"} as well,
     * leaving already-double-quoted lines, list-item lines, and unquoted
     * lines for non-string keys untouched. No-ops (and never touches the
     * file) if nothing needed changing.
     *
     * <p>This is a plain-text, line-by-line pass — not a real YAML
     * re-serialization — so before committing its rewrite to disk it
     * re-parses the rewritten text and checks it resolves to the exact
     * same set of keys the original file had (see
     * {@link #rewriteRoundTrips}). A pass that thinks it only swapped
     * quote characters but actually mangled the file (say, by matching
     * across what was really a wrapped multi-line scalar, or mishandling
     * an escape sequence this method didn't anticipate) is exactly the
     * kind of bug that silently turns config.yml into something Bukkit
     * reads back as empty — see {@link #configLooksIntact()} for what
     * happens next if that's ever allowed onto disk. On a failed
     * round-trip this just skips the cosmetic rewrite and leaves Bukkit's
     * own (already-valid, just single-quoted) output in place, rather than
     * committing a rewrite that isn't provably safe.
     *
     * <p>Handles two shapes Bukkit's YAML serializer can produce for a string value that isn't
     * already double-quoted: single-quoted ({@link #SINGLE_QUOTED_SCALAR}, for anything YAML
     * requires quoting), and, for a string-typed key whose value happens not to need any quoting
     * at all (no leading {@code {}/{@code &}, no colon-space, etc.), a completely bare plain
     * scalar ({@link #PLAIN_SCALAR}) — e.g. a {@code nametag-format} of {@code Player: {player}}
     * typed without a leading brace. The plain-scalar case is restricted to
     * {@link #stringConfigKeys()} so enum/number/boolean values elsewhere in the file are never
     * wrapped in quotes they were never meant to have.
     */
    private void normalizeQuoting(File file) {
        try {
            if (!file.exists()) {
                return;
            }
            List<String> stringKeys = stringConfigKeys();
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> output = new ArrayList<>(lines.size());
            boolean changed = false;
            for (String line : lines) {
                Matcher singleQuoted = SINGLE_QUOTED_SCALAR.matcher(line);
                if (singleQuoted.matches()) {
                    String prefix = singleQuoted.group(1);
                    // YAML single-quoted strings escape an embedded ' as '' — undo that first, then
                    // apply double-quoted escaping (\ and ") before re-wrapping.
                    String value = singleQuoted.group(2).replace("''", "'");
                    value = value.replace("\\", "\\\\").replace("\"", "\\\"");
                    output.add(prefix + "\"" + value + "\"");
                    changed = true;
                    continue;
                }
                Matcher plain = PLAIN_SCALAR.matcher(line);
                if (plain.matches()) {
                    String prefix = plain.group(1);
                    String bareKey = prefix.replaceFirst(":\\s+$", "");
                    if (stringKeys.contains(bareKey)) {
                        // A bare plain scalar has no YAML escape sequences to undo — just add
                        // double-quoted escaping (\ and ") before wrapping it.
                        String value = plain.group(2).replace("\\", "\\\\").replace("\"", "\\\"");
                        output.add(prefix + "\"" + value + "\"");
                        changed = true;
                        continue;
                    }
                }
                output.add(line);
            }
            if (!changed) {
                return;
            }
            StringBuilder builder = new StringBuilder();
            for (String line : output) {
                builder.append(line).append('\n');
            }
            String rewritten = builder.toString();
            if (!rewriteRoundTrips(file, rewritten)) {
                plugin.getLogger().warning("Skipped re-quoting config.yml: the rewritten version didn't parse back "
                        + "to the same set of keys as the original, so it was left as Bukkit originally wrote it "
                        + "(still valid, just single-quoted) rather than risk writing something broken.");
                return;
            }
            Files.write(file.toPath(), rewritten.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not normalize config.yml quote style: " + e.getMessage());
        }
    }

    /** Whether re-parsing {@code rewritten} as YAML succeeds and yields the exact same set of keys (at every depth) that {@code originalFile}'s current on-disk content does — see {@link #normalizeQuoting} for why this gate exists. */
    private boolean rewriteRoundTrips(File originalFile, String rewritten) {
        YamlConfiguration before = YamlConfiguration.loadConfiguration(originalFile);
        YamlConfiguration after = new YamlConfiguration();
        try {
            after.loadFromString(rewritten);
        } catch (InvalidConfigurationException e) {
            return false;
        }
        return before.getKeys(true).equals(after.getKeys(true));
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