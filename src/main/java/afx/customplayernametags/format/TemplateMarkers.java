package afx.customplayernametags.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes/decodes the {@code Characters} and {@code Widget} admin-template
 * chunks that {@link afx.customplayernametags.manager.NametagEditorManager}
 * can embed directly in an otherwise-plain nametag format string, so the
 * same {@code String} that's already persisted to {@code config.yml},
 * {@code player-formats.db}, and {@code group-formats.yml} carries this
 * metadata without needing a parallel storage format.
 *
 * <p>Both chunks are plain, printable, human-readable tag text — exactly
 * the same form in memory, on disk, and in the GUI editor:
 * <pre>
 * {widget colors=true placeholders=true text=false limit=12}%player_name%{/widget}
 * {chars 16}
 * </pre>
 * There is deliberately no second, internal representation: an earlier
 * version of this class wrapped both chunks in Private Use Area codepoints
 * (U+E000 range) and converted to/from the tag syntax at every load/save
 * boundary, but PUA codepoints don't survive the Bedrock (Geyser) text
 * pipeline intact, so they could leak into or corrupt what a Bedrock
 * player actually sees. Storing the tags as-is removes that whole class of
 * problem, and removes the encode/decode boundary with it.
 *
 * <p>{@link #strip} is the single choke point that guarantees none of this
 * metadata ever leaks into an actual rendered nametag — every caller that
 * turns a raw stored format into display text (see
 * {@code NametagManager#parseFormat}) runs it through here first. Because
 * the tags are ordinary text a player could in principle type, text
 * collected from players is run through {@link #stripTagText} first.
 *
 * <p>Widgets do not nest, and a widget's inner content cannot itself
 * contain the literal text {@code {/widget}} — matching the non-nesting
 * behavior the old encoding had.
 */
public final class TemplateMarkers {
    private TemplateMarkers() {
    }

    /** Opening text of a Widget tag; a complete open tag is this plus optional attributes plus {@code }}. */
    public static final String WIDGET_OPEN_PREFIX = "{widget";
    /** Closing tag that ends a Widget's inner content. */
    public static final String WIDGET_CLOSE_TAG = "{/widget}";
    /** Opening text of a Characters tag, e.g. {@code {chars 16}}. */
    public static final String CHARS_OPEN_PREFIX = "{chars";

    /**
     * Matches one whole tag as a single unit. Meant to be added as an
     * alternative inside {@code NametagEditorManager}'s own chunk
     * tokenizer pattern, so the editor's {@code parse()}/{@code serialize()}
     * round-trips these exactly like any other chunk.
     */
    public static final Pattern MARKER = Pattern.compile(
            "\\{chars\\s+[0-9]+\\}"
                    + "|\\{widget(?:\\s[^}]*)?\\}(?s:.*?)\\{/widget\\}");

    /** One {@code key=value} attribute of a Widget open tag, value optionally single/double-quoted. */
    private static final Pattern ATTR = Pattern.compile(
            "(\\w+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s}]+)");

    /** Any literal Widget/Characters tag text, however malformed — see {@link #stripTagText}. */
    private static final Pattern TAG_TEXT = Pattern.compile(
            "(?i)\\{\\s*/?\\s*(?:widget|chars)\\b[^}]*\\}?");

    // Purely transient, in-memory markers used only within a single NametagManager#parseFormat
    // call to carry a limited widget's character limit through {player}/PlaceholderAPI
    // substitution to truncateMarkedWidgets() — never written to disk, sent to a client, or
    // otherwise visible outside that one call, unlike the legacy PUA encoding above (which
    // persisted long-term and had to survive the Bedrock/Geyser pipeline intact). See
    // stripForRendering()/truncateMarkedWidgets().
    private static final char WIDGET_LIMIT_MARK_OPEN = '\uE010';
    private static final char WIDGET_LIMIT_MARK_SEP = '\uE011';
    private static final char WIDGET_LIMIT_MARK_CLOSE = '\uE012';

    /**
     * One Widget's settings and inner content, as parsed back out of its
     * tag — see {@link #parseWidget}.
     *
     * @param id a short, stable identifier for this particular widget slot,
     *           unique within one format string, used to key a player's
     *           saved fill-in to the specific widget they filled rather
     *           than to its position in the format (see
     *           {@link afx.customplayernametags.config.PlayerWidgetFillStore}
     *           and {@link #overlayWidgetFills}) — {@code ""} if the tag had
     *           no {@code id=} attribute (shouldn't happen for anything that's
     *           been through {@link #ensureWidgetIds}).
     * @param characterLimit maximum visible characters allowed in any one Text item typed into
     *                       this widget, or {@code -1} for no limit.
     */
    public record Widget(String id, boolean colors, boolean placeholders, boolean text, int characterLimit, String inner) {
    }

    /** A located, well-formed Widget tag inside some larger string. */
    private record WidgetBlock(int contentStart, int contentEnd, int endExclusive, String attributes) {
    }

    /** A located, well-formed Characters tag inside some larger string. */
    private record CharsTag(int limit, int endExclusive) {
    }

    public static String encodeCharacters(int limit) {
        return CHARS_OPEN_PREFIX + " " + Math.max(0, limit) + "}";
    }

    /**
     * @param id an id for this widget — see {@link Widget#id()}. Should
     *           normally come from {@link #generateWidgetId()} for a
     *           brand-new widget, or be carried over unchanged from
     *           whatever the widget already had.
     * @param characterLimit maximum visible characters allowed in any one Text item typed into this widget, or {@code -1} for no limit.
     */
    public static String encodeWidget(String id, boolean colors, boolean placeholders, boolean text, int characterLimit, String innerRaw) {
        return widgetOpenTag(id, colors, placeholders, text, characterLimit)
                + (innerRaw == null ? "" : innerRaw)
                + WIDGET_CLOSE_TAG;
    }

    /**
     * Just the {@code {widget ...}} open tag. A character limit of
     * {@code -1} (unlimited) is written by simply omitting {@code limit=}
     * entirely, rather than as a magic {@code -1} an admin reading the file
     * would have to interpret. {@code id} is likewise omitted entirely when
     * blank, though in practice every widget written by this version of the
     * plugin always has one — see {@link #ensureWidgetIds}.
     */
    public static String widgetOpenTag(String id, boolean colors, boolean placeholders, boolean text, int characterLimit) {
        StringBuilder tag = new StringBuilder(WIDGET_OPEN_PREFIX);
        if (id != null && !id.isEmpty()) {
            tag.append(" id=").append(id);
        }
        tag.append(" colors=").append(colors);
        tag.append(" placeholders=").append(placeholders);
        tag.append(" text=").append(text);
        if (characterLimit >= 0) {
            tag.append(" limit=").append(characterLimit);
        }
        tag.append('}');
        return tag.toString();
    }

    /**
     * Mints a short, random, printable widget id — an 8-character lowercase
     * hex string sliced off a random UUID. Not guaranteed globally unique,
     * but collision odds within the handful of widgets any one format
     * realistically has are negligible; {@link #ensureWidgetIds} only ever
     * needs uniqueness within a single format string anyway.
     */
    public static String generateWidgetId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Renders {@code raw} exactly as it should actually be displayed: every
     * Characters tag is dropped entirely (it carries no visible content of
     * its own), and every Widget tag is replaced with just its own inner
     * content, unwrapped. Safe to call on a format with no tags at all —
     * returned unchanged.
     */
    public static String strip(String raw) {
        if (!mayContainTag(raw)) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    out.append(raw, widget.contentStart(), widget.contentEnd());
                    i = widget.endExclusive();
                    continue;
                }
                CharsTag chars = charsAt(raw, i);
                if (chars != null) {
                    i = chars.endExclusive();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Like {@link #strip}, except a Widget with a positive character limit
     * (its {@code limit=} attribute) has its inner content wrapped in a
     * transient marker instead of being spliced in bare — see the {@code
     * WIDGET_LIMIT_MARK_*} constants. {@code NametagManager#parseFormat}
     * uses this instead of {@link #strip} so that, after {@code {player}}/
     * PlaceholderAPI substitution has actually resolved the widget's
     * contents, {@link #truncateMarkedWidgets} can still find exactly
     * where each limited widget's content starts and ends and cut it down
     * to its own limit — necessary because a widget can hold a Placeholder
     * item, whose resolved length isn't known until substitution actually
     * happens (same reasoning {@code NametagManager#truncateToCharacterLimit}
     * already documents for the per-line limit). A widget with no limit
     * ({@code -1}) is spliced in bare, exactly like {@link #strip} already
     * does, since there's nothing to enforce later. Safe to call on a
     * format with no tags at all — returned unchanged.
     */
    public static String stripForRendering(String raw) {
        if (!mayContainTag(raw)) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    int limit = attributeLimit(widget.attributes());
                    if (limit >= 0) {
                        out.append(WIDGET_LIMIT_MARK_OPEN).append(limit).append(WIDGET_LIMIT_MARK_SEP);
                        out.append(raw, widget.contentStart(), widget.contentEnd());
                        out.append(WIDGET_LIMIT_MARK_CLOSE);
                    } else {
                        out.append(raw, widget.contentStart(), widget.contentEnd());
                    }
                    i = widget.endExclusive();
                    continue;
                }
                CharsTag chars = charsAt(raw, i);
                if (chars != null) {
                    i = chars.endExclusive();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Resolves every marker {@link #stripForRendering} left behind: the
     * marked widget's content is cut down to its own {@code limit=}
     * (measured the same "parsed"/visible way as everywhere else — see
     * {@link NametagFormatter#plainText} — not raw string length) if it's
     * over, with a plain white {@code "..."} appended only if {@code
     * ellipsis} is {@code true}, and the markers themselves are removed
     * either way. Content within its limit passes through completely
     * unchanged. Safe to call on text with no markers at all (returned
     * unchanged) — e.g. a format with no limited widgets never pays for
     * any of this.
     */
    public static String truncateMarkedWidgets(String resolved, boolean ellipsis) {
        if (resolved == null || resolved.indexOf(WIDGET_LIMIT_MARK_OPEN) < 0) {
            return resolved;
        }
        StringBuilder out = new StringBuilder(resolved.length());
        int i = 0;
        while (i < resolved.length()) {
            char c = resolved.charAt(i);
            if (c == WIDGET_LIMIT_MARK_OPEN) {
                int sep = resolved.indexOf(WIDGET_LIMIT_MARK_SEP, i + 1);
                int close = sep < 0 ? -1 : resolved.indexOf(WIDGET_LIMIT_MARK_CLOSE, sep + 1);
                if (sep > i && close > sep) {
                    int limit;
                    try {
                        limit = Integer.parseInt(resolved.substring(i + 1, sep));
                    } catch (NumberFormatException e) {
                        limit = -1;
                    }
                    String inner = resolved.substring(sep + 1, close);
                    out.append(truncateWidgetContent(inner, limit, ellipsis));
                    i = close + 1;
                    continue;
                }
                // Malformed/truncated marker (shouldn't happen) — drop just the stray marker
                // character rather than let it leak into the rendered nametag.
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** One marked widget's already-resolved inner text, cut down to {@code limit} visible characters if it's over. */
    private static String truncateWidgetContent(String inner, int limit, boolean ellipsis) {
        if (limit < 0 || inner.isEmpty()) {
            return inner;
        }
        var component = NametagFormatter.toComponent(NametagFormatter.toMiniMessageSource(inner));
        if (NametagFormatter.plainText(component).length() <= limit) {
            return inner;
        }
        var truncated = ellipsis
                ? NametagFormatter.truncateLineWithEllipsis(component, limit)
                : NametagFormatter.truncateVisibleCharacters(component, limit);
        return NametagFormatter.serialize(truncated);
    }

    /**
     * Per logical line (split on real and literal {@code \n},
     * <em>before</em> {@link #strip} is applied), the Characters-item
     * override in effect for that line, or {@code -1} if none is present.
     * Index {@code n} of the returned array is the (0-based) n-th line of
     * {@code raw}, and lines up with {@code NametagFormatter#splitLines}
     * applied to {@code strip(raw)} parsed and rendered, since tags never
     * introduce or remove line breaks of their own.
     */
    public static int[] lineCharacterOverrides(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new int[]{-1};
        }
        String normalized = raw.replace("\\n", "\n");
        List<Integer> overrides = new ArrayList<>();
        overrides.add(-1);
        int i = 0;
        while (i < normalized.length()) {
            char c = normalized.charAt(i);
            if (c == '\n') {
                overrides.add(-1);
                i++;
                continue;
            }
            if (c == '{') {
                WidgetBlock widget = widgetAt(normalized, i);
                if (widget != null) {
                    // A widget's inner content can't hold a Characters tag, so skip the whole block.
                    i = widget.endExclusive();
                    continue;
                }
                CharsTag chars = charsAt(normalized, i);
                if (chars != null) {
                    overrides.set(overrides.size() - 1, chars.limit());
                    i = chars.endExclusive();
                    continue;
                }
            }
            i++;
        }
        int[] result = new int[overrides.size()];
        for (int n = 0; n < result.length; n++) {
            result[n] = overrides.get(n);
        }
        return result;
    }

    /**
     * Per logical line of {@code raw} (same line-splitting as
     * {@link #lineCharacterOverrides} — real and literal {@code \n},
     * <em>before</em> {@link #strip} is applied), whether that line
     * contains at least one well-formed Widget tag. Used by
     * {@link #dropEmptyWidgetLines} to tell a line that's blank because an
     * optional Widget slot on it hasn't been filled in from a line that's
     * blank for any other reason (a deliberate empty "New Line" chunk).
     */
    private static boolean[] widgetLines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new boolean[]{false};
        }
        String normalized = raw.replace("\\n", "\n");
        List<Boolean> lines = new ArrayList<>();
        lines.add(false);
        int i = 0;
        while (i < normalized.length()) {
            char c = normalized.charAt(i);
            if (c == '\n') {
                lines.add(false);
                i++;
                continue;
            }
            if (c == '{') {
                WidgetBlock widget = widgetAt(normalized, i);
                if (widget != null) {
                    lines.set(lines.size() - 1, true);
                    i = widget.endExclusive();
                    continue;
                }
                CharsTag chars = charsAt(normalized, i);
                if (chars != null) {
                    i = chars.endExclusive();
                    continue;
                }
            }
            i++;
        }
        boolean[] result = new boolean[lines.size()];
        for (int n = 0; n < result.length; n++) {
            result[n] = lines.get(n);
        }
        return result;
    }

    /**
     * Drops every line of {@code resolved} — the fully strip()ped,
     * {@code {player}}/PlaceholderAPI-resolved text {@code NametagManager
     * #parseFormat} builds from {@code raw}, with real {@code \n} line
     * breaks already applied — whose corresponding line in {@code raw}
     * contained a Widget tag (see {@link #widgetLines}) and which renders
     * with no visible characters at all once colors/MiniMessage markup are
     * accounted for. This is what keeps an unfilled (or emptied-out)
     * optional Widget slot from leaving a blank gap in the actual rendered
     * nametag: the line — and its line break — simply disappears, rather
     * than rendering as an empty line the way any other line's text would.
     *
     * <p>{@code resolved} and {@code raw} are guaranteed to have the same
     * number of lines in the same order, since none of strip()/{@code
     * {player}}/PlaceholderAPI substitution ever add or remove a line
     * break — matching the same guarantee {@link #lineCharacterOverrides}
     * already relies on.
     *
     * <p>A line that's blank for any <em>other</em> reason — a deliberate
     * blank "New Line" chunk with nothing on it, or one with a Widget
     * alongside literal text that just happens to be all-whitespace — is
     * left completely untouched; only a line whose entire visible content
     * came from a Widget disappears. Safe to call on a {@code raw}/{@code
     * resolved} pair with no widgets at all (returned unchanged).
     */
    public static String dropEmptyWidgetLines(String raw, String resolved) {
        if (resolved == null || resolved.isEmpty() || !containsWidget(raw)) {
            return resolved;
        }
        boolean[] widgetLine = widgetLines(raw);
        String[] lines = resolved.split("\n", -1);
        StringBuilder out = new StringBuilder(resolved.length());
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            if (i < widgetLine.length && widgetLine[i] && isVisiblyBlank(lines[i])) {
                continue;
            }
            if (!first) {
                out.append('\n');
            }
            out.append(lines[i]);
            first = false;
        }
        return out.toString();
    }

    /** Whether {@code line} renders with no visible characters once legacy/MiniMessage color and style markup is accounted for. */
    private static boolean isVisiblyBlank(String line) {
        if (line.isEmpty()) {
            return true;
        }
        String visible = NametagFormatter.plainText(NametagFormatter.toComponent(NametagFormatter.toMiniMessageSource(line)));
        return visible.trim().isEmpty();
    }

    /**
     * This format's Widgets' inner content, keyed by each widget's own
     * {@code id=} attribute (see {@link Widget#id()}) rather than anything
     * about the surrounding template or the widget's position in it. Used
     * to capture what a player just filled into a widget (or several) so
     * it can be persisted independently of the admin-authored template it
     * was filled into; see
     * {@link afx.customplayernametags.config.PlayerWidgetFillStore} and
     * {@link #overlayWidgetFills}. A widget with no id (shouldn't happen
     * for anything that's been through {@link #ensureWidgetIds}) is simply
     * skipped, since there'd be nothing stable to key its fill by. Returns
     * an empty map for a format with no (id-bearing) widgets at all.
     */
    public static Map<String, String> extractWidgetContents(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!mayContainTag(raw)) {
            return result;
        }
        int i = 0;
        while (i < raw.length()) {
            if (raw.charAt(i) == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    String id = attributeId(widget.attributes());
                    if (id != null && !id.isEmpty()) {
                        result.put(id, raw.substring(widget.contentStart(), widget.contentEnd()));
                    }
                    i = widget.endExclusive();
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    /**
     * Returns {@code raw} with each of its Widgets' inner content replaced
     * by {@code fills}' entry for that widget's own {@code id=} attribute
     * (see {@link Widget#id()}), for every id {@code fills} has an entry
     * for. A widget whose id isn't in {@code fills} — or that has no id at
     * all — is left exactly as {@code raw} already had it; its attributes
     * are always preserved either way, only the inner content between the
     * tags can change. Matching by id (rather than by position, as an
     * earlier version of this did) is what keeps a player's fill attached
     * to the specific widget they filled even if an admin reorders, adds,
     * or removes other widgets in the template afterwards. This is what
     * lets a player's stored widget fills (see
     * {@link afx.customplayernametags.config.PlayerWidgetFillStore}) be
     * re-applied on top of whichever global/group template currently
     * applies to them, instead of ever needing their own frozen copy of
     * that whole template. Safe to call with an empty/{@code null}
     * {@code fills} (returns {@code raw} unchanged) or a {@code raw} with
     * no widgets at all.
     */
    public static String overlayWidgetFills(String raw, Map<String, String> fills) {
        if (!mayContainTag(raw) || fills == null || fills.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    String id = attributeId(widget.attributes());
                    String fill = id == null ? null : fills.get(id);
                    out.append(raw, i, widget.contentStart());
                    out.append(fill != null ? fill : raw.substring(widget.contentStart(), widget.contentEnd()));
                    out.append(WIDGET_CLOSE_TAG);
                    i = widget.endExclusive();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Every widget id present in {@code raw} (see {@link Widget#id()}), in
     * order of appearance, skipping any widget that has none. Used for
     * reachability checks — a stored fill/keyed entry whose id isn't in
     * this set for any format that currently applies is orphaned and safe
     * to discard; see
     * {@link afx.customplayernametags.manager.NametagManager#pruneOrphanedWidgetFills}
     * and {@link afx.customplayernametags.config.PlayerWidgetFillStore#pruneOrphans}.
     */
    public static Set<String> widgetIds(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (!mayContainTag(raw)) {
            return result;
        }
        int i = 0;
        while (i < raw.length()) {
            if (raw.charAt(i) == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    String id = attributeId(widget.attributes());
                    if (id != null && !id.isEmpty()) {
                        result.add(id);
                    }
                    i = widget.endExclusive();
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    /**
     * Whether {@code raw} contains at least one well-formed Widget tag with
     * no {@code id=} attribute (or a blank one) — i.e. one that predates
     * this plugin version's id system, or that an admin hand-typed without
     * one. {@code ConfigManager}/{@code GroupFormatStore}/
     * {@code PlayerFormatStore} check this on every load (and on every
     * direct setter) to decide whether {@link #ensureWidgetIds} needs to run.
     */
    public static boolean needsIdAssignment(String raw) {
        if (!mayContainTag(raw)) {
            return false;
        }
        int i = raw.indexOf(WIDGET_OPEN_PREFIX);
        while (i >= 0) {
            WidgetBlock widget = widgetAt(raw, i);
            if (widget != null) {
                String id = attributeId(widget.attributes());
                if (id == null || id.isEmpty()) {
                    return true;
                }
            }
            i = raw.indexOf(WIDGET_OPEN_PREFIX, i + 1);
        }
        return false;
    }

    /**
     * Assigns a freshly-minted {@link #generateWidgetId()} to every well-formed
     * Widget tag in {@code raw} that doesn't already have one, leaving
     * every other id untouched and every non-widget character of
     * {@code raw} exactly as it was. Safe to call on a string with no
     * widgets, or where every widget already has an id (returned
     * unchanged) — so a caller can run it unconditionally over whatever it
     * just loaded/was given and persist the result if
     * {@link #needsIdAssignment} said it changed.
     */
    public static String ensureWidgetIds(String raw) {
        if (!needsIdAssignment(raw)) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    String id = attributeId(widget.attributes());
                    if (id == null || id.isEmpty()) {
                        Widget parsed = parseWidget(widget.attributes(), raw.substring(widget.contentStart(), widget.contentEnd()));
                        out.append(encodeWidget(generateWidgetId(), parsed.colors(), parsed.placeholders(),
                                parsed.text(), parsed.characterLimit(), parsed.inner()));
                    } else {
                        // Already has an id — copy the widget through untouched.
                        out.append(raw, i, widget.endExclusive());
                    }
                    i = widget.endExclusive();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The {@code id=} attribute out of one widget's raw attribute text, or {@code null} if there isn't one. */
    private static String attributeId(String rawAttributes) {
        Matcher attrMatcher = ATTR.matcher(rawAttributes);
        while (attrMatcher.find()) {
            if (attrMatcher.group(1).equalsIgnoreCase("id")) {
                return stripQuotes(attrMatcher.group(2));
            }
        }
        return null;
    }

    /** The {@code limit=} attribute out of one widget's raw attribute text, or {@code -1} if there isn't one (or it doesn't parse). */
    private static int attributeLimit(String rawAttributes) {
        Matcher attrMatcher = ATTR.matcher(rawAttributes);
        while (attrMatcher.find()) {
            if (attrMatcher.group(1).equalsIgnoreCase("limit")) {
                try {
                    return Math.max(0, Integer.parseInt(stripQuotes(attrMatcher.group(2)).trim()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Parses one whole {@code {widget ...}...{/widget}} token — as matched
     * by {@link #MARKER} — back into its settings and inner content. Every
     * attribute is optional and may appear in any order;
     * {@code colors}/{@code placeholders}/{@code text} default to
     * {@code true} and a missing {@code limit} means unlimited. An unknown
     * attribute, an unparseable {@code limit}, or a token that isn't a
     * well-formed widget at all is tolerated rather than rejected, so a
     * single typo in config.yml can't take a whole format down.
     */
    public static Widget parseWidget(String token) {
        WidgetBlock block = token == null ? null : widgetAt(token, 0);
        if (block == null) {
            return new Widget("", true, true, true, -1, "");
        }
        return parseWidget(block.attributes(), token.substring(block.contentStart(), block.contentEnd()));
    }

    private static Widget parseWidget(String rawAttributes, String inner) {
        String id = "";
        boolean colors = true;
        boolean placeholders = true;
        boolean text = true;
        int limit = -1;

        Matcher attrMatcher = ATTR.matcher(rawAttributes);
        while (attrMatcher.find()) {
            String key = attrMatcher.group(1).toLowerCase(Locale.ROOT);
            String value = stripQuotes(attrMatcher.group(2));
            switch (key) {
                case "id" -> id = value;
                case "colors" -> colors = Boolean.parseBoolean(value);
                case "placeholders" -> placeholders = Boolean.parseBoolean(value);
                case "text" -> text = Boolean.parseBoolean(value);
                case "limit" -> {
                    try {
                        limit = Math.max(0, Integer.parseInt(value.trim()));
                    } catch (NumberFormatException ignored) {
                        // Unparseable limit= value — leave the widget unlimited rather than reject the whole format.
                    }
                }
                default -> {
                    // Unknown attribute — ignore rather than fail the whole format over a typo/future attribute.
                }
            }
        }
        return new Widget(id, colors, placeholders, text, limit, inner);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Whether {@code raw} contains at least one well-formed Widget tag. */
    public static boolean containsWidget(String raw) {
        if (!mayContainTag(raw)) {
            return false;
        }
        int i = raw.indexOf(WIDGET_OPEN_PREFIX);
        while (i >= 0) {
            if (widgetAt(raw, i) != null) {
                return true;
            }
            i = raw.indexOf(WIDGET_OPEN_PREFIX, i + 1);
        }
        return false;
    }

    /**
     * Whether {@code raw} contains literal {@code {widget ...}}/{@code
     * {/widget}} tag text that isn't part of a complete, correctly-typed
     * widget — a missing close tag, a stray capital {@code {Widget}}, and
     * so on. Such text would otherwise render as-is on every nametag,
     * which is confusing and easy for an admin not to notice until a player
     * reports it, so {@code ConfigManager#load()} uses this to detect and
     * fall back an invalid {@code nametag-format}/{@code
     * bedrock-nametag-format} instead.
     */
    public static boolean hasMalformedWidgetTag(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        StringBuilder remainder = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                WidgetBlock widget = widgetAt(raw, i);
                if (widget != null) {
                    // Well-formed — its own inner content is still scanned, since a stray
                    // {widget in there is just as broken as one outside.
                    remainder.append(raw, widget.contentStart(), widget.contentEnd());
                    i = widget.endExclusive();
                    continue;
                }
            }
            remainder.append(c);
            i++;
        }
        String lower = remainder.toString().toLowerCase(Locale.ROOT);
        return lower.contains(WIDGET_OPEN_PREFIX) || lower.contains(WIDGET_CLOSE_TAG);
    }

    /**
     * Removes any literal Widget/Characters tag text from {@code text},
     * used on anything a player types in (sign input in the GUI editor) so
     * a player can't hand-craft or break a widget just by typing its tag
     * as ordinary nametag text. Unlike the old Private Use Area encoding,
     * these tags are typeable, so this is what keeps them trustworthy.
     */
    public static String stripTagText(String text) {
        if (text == null || text.isEmpty() || text.indexOf('{') < 0) {
            return text;
        }
        return TAG_TEXT.matcher(text).replaceAll("");
    }

    /** Cheap pre-check: no {@code {} at all means there can't be a tag of either kind. */
    private static boolean mayContainTag(String raw) {
        return raw != null && !raw.isEmpty() && raw.indexOf('{') >= 0;
    }

    /**
     * Locates the complete Widget tag starting at {@code i}, or {@code null}
     * if there isn't a well-formed one there. Matching is case-sensitive
     * and requires the open tag to be followed by whitespace or {@code }},
     * so ordinary format text like {@code {player}} — or a typo'd
     * {@code {Widget}} — is never mistaken for one.
     */
    private static WidgetBlock widgetAt(String raw, int i) {
        if (!raw.startsWith(WIDGET_OPEN_PREFIX, i)) {
            return null;
        }
        int afterPrefix = i + WIDGET_OPEN_PREFIX.length();
        if (afterPrefix >= raw.length()) {
            return null;
        }
        char next = raw.charAt(afterPrefix);
        if (next != '}' && !Character.isWhitespace(next)) {
            return null;
        }
        int tagClose = raw.indexOf('}', afterPrefix);
        if (tagClose < 0) {
            return null;
        }
        int close = raw.indexOf(WIDGET_CLOSE_TAG, tagClose + 1);
        if (close < 0) {
            return null;
        }
        return new WidgetBlock(tagClose + 1, close, close + WIDGET_CLOSE_TAG.length(),
                raw.substring(afterPrefix, tagClose));
    }

    /** Locates the complete Characters tag starting at {@code i}, or {@code null} if there isn't a well-formed one there. */
    private static CharsTag charsAt(String raw, int i) {
        if (!raw.startsWith(CHARS_OPEN_PREFIX, i)) {
            return null;
        }
        int afterPrefix = i + CHARS_OPEN_PREFIX.length();
        if (afterPrefix >= raw.length() || !Character.isWhitespace(raw.charAt(afterPrefix))) {
            return null;
        }
        int tagClose = raw.indexOf('}', afterPrefix);
        if (tagClose < 0) {
            return null;
        }
        String digits = raw.substring(afterPrefix, tagClose).trim();
        if (digits.isEmpty()) {
            return null;
        }
        for (int n = 0; n < digits.length(); n++) {
            if (digits.charAt(n) < '0' || digits.charAt(n) > '9') {
                return null;
            }
        }
        try {
            return new CharsTag(Integer.parseInt(digits), tagClose + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
