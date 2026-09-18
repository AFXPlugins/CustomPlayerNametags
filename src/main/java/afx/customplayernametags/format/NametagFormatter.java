package afx.customplayernametags.format;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw nametag/tablist format string (already {@code {player}}- and
 * PlaceholderAPI-resolved by the caller) into a MiniMessage source string,
 * and that source string into a rendered {@link Component}.
 *
 * <p>This is the single place both {@code NametagManager} (nametags,
 * tablist) and {@code NametagDisplayManager} (the actual TextDisplay
 * rendering) go through to turn text into a {@link Component}, so both
 * always render identically.
 *
 * <h2>Format coexistence</h2>
 * <p>A format string may freely mix legacy {@code &}-style color codes
 * (including {@code &#RRGGBB} hex) with native MiniMessage tags such as
 * {@code <gradient:...>}, {@code <rainbow>}, {@code <bold>}, etc. — both are
 * supported in the same string. Legacy codes are converted into their
 * MiniMessage tag equivalents first (see {@link #convertLegacyToMiniMessage}),
 * so by the time the string reaches {@link MiniMessage#deserialize} it is
 * pure MiniMessage markup.
 *
 * <p>Placeholder-supplied text (e.g. from PlaceholderAPI) is not
 * independently escaped before reaching MiniMessage. A placeholder whose
 * resolved value happens to contain {@code <}/{@code >} characters could in
 * principle be interpreted as MiniMessage markup. This mirrors the same
 * trust boundary the plugin already had for {@code &} codes and is a
 * documented, accepted tradeoff shared by most MiniMessage-based nametag
 * plugins, rather than something this class attempts to fully sandbox.
 */
public final class NametagFormatter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** Matches a legacy hex color code, e.g. {@code &#FF00AA}. */
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    /** Matches a single legacy formatting/color code, e.g. {@code &c}, {@code &l}, {@code &r}. */
    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private NametagFormatter() {
    }

    /**
     * Converts {@code resolved} (a string that has already had
     * {@code {player}} substituted and PlaceholderAPI placeholders
     * resolved, but is otherwise raw) into a self-contained MiniMessage
     * source string: legacy {@code &} codes become MiniMessage tags.
     */
    public static String toMiniMessageSource(String resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return "";
        }
        return convertLegacyToMiniMessage(resolved);
    }

    /**
     * Deserializes a string already produced by
     * {@link #toMiniMessageSource(String)} (or otherwise known to be valid
     * MiniMessage markup) into a renderable {@link Component}.
     */
    public static Component toComponent(String miniMessageSource) {
        if (miniMessageSource == null || miniMessageSource.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(miniMessageSource);
        } catch (Exception e) {
            // A malformed tag (e.g. a stray '<' from placeholder output, or
            // an admin typo) should never crash rendering or leave a player
            // with no nametag at all — fall back to the raw text as a plain
            // (uncolored) component instead of throwing.
            return Component.text(miniMessageSource);
        }
    }

    /**
     * Splits a single, already-deserialized {@code nametag} {@link Component}
     * into one {@link Component} per line (at every literal {@code '\n'} in
     * its text), with each returned line carrying its own fully-resolved
     * (inherited-and-merged) color/decoration/font — <em>not</em> just
     * whatever style happened to be set directly on the text run containing
     * that newline.
     *
     * <p>This matters because a color or decoration opened before a line
     * break in MiniMessage markup (e.g. {@code "<red>Line1\nLine2"}) applies
     * to every line until it's closed, the same way it applies to every word
     * — the actual overhead nametag (a single {@link Component} handed
     * straight to a TextDisplay, newline and all) renders this correctly on
     * its own. An item's lore, however, is a {@code List<Component>} of
     * independent top-level lines with no shared parent to inherit style
     * from, so simply splitting the raw MiniMessage <em>source string</em>
     * on {@code '\n'} before deserializing each piece on its own would
     * silently drop any style that was only opened on an earlier line —
     * this method exists so callers building lore (see
     * {@code NametagEditorManager#savedNametagItem}) don't have to do that
     * and instead always get lore matching the real nametag exactly.
     */
    public static List<Component> splitLines(Component component) {
        List<Component> lines = new ArrayList<>();
        List<Component> currentLine = new ArrayList<>();
        collectLines(component, rootStyle(), currentLine, lines);
        lines.add(joinSegments(currentLine));
        return lines;
    }

    /**
     * The style every line starts inheriting from before any of the
     * nametag's own colors/decorations are merged in — explicit white with
     * every decoration explicitly off, rather than {@link Style#empty()}
     * (nothing set at all).
     *
     * <p>This matters specifically because the {@link Component Components}
     * returned by {@link #splitLines} are handed straight to item lore
     * (see {@code NametagEditorManager#savedNametagItem} and
     * {@code #placeholderItem}), and the client applies its own defaults —
     * {@code dark_purple} and italic — to any lore line/segment that leaves
     * color or a decoration completely unset (see Mojang bug MC-124458).
     * That default has nothing to do with how the format actually renders
     * as a real nametag (plain white, no decorations), so without this,
     * any part of a format that never explicitly set a color (e.g. plain
     * text before the first color tag) would preview as purple italic in a
     * tooltip while rendering as plain white above the player's head —
     * exactly the mismatch this method exists to prevent. Starting every
     * line's inherited style here, rather than empty, means
     * {@link #mergeStyle} always has an explicit color/decoration to fall
     * back on, so the final merged style — and therefore the resulting
     * lore JSON — never leaves anything unset for the client to guess at.
     */
    private static Style rootStyle() {
        Style.Builder builder = Style.style().color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        for (TextDecoration decoration : TextDecoration.values()) {
            builder.decoration(decoration, false);
        }
        return builder.build();
    }

    /**
     * Walks {@code component} depth-first in reading order (own text, then
     * children — matching how Adventure itself renders a tree), resolving
     * each text run's fully-merged style as it goes, and appends completed
     * lines to {@code lines} every time a {@code '\n'} is found. Any
     * trailing segments after the last newline are left in
     * {@code currentLine} for the caller to flush.
     */
    private static void collectLines(Component component, Style inherited,
                                      List<Component> currentLine, List<Component> lines) {
        Style effective = mergeStyle(component.style(), inherited);

        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            String[] parts = text.content().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    currentLine.add(Component.text(parts[i]).style(effective));
                }
                if (i < parts.length - 1) {
                    lines.add(joinSegments(currentLine));
                    currentLine.clear();
                }
            }
        }

        for (Component child : component.children()) {
            collectLines(child, effective, currentLine, lines);
        }
    }

    /** Concatenates already-styled segments into a single line component. */
    private static Component joinSegments(List<Component> segments) {
        if (segments.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        for (Component segment : segments) {
            result = result.append(segment);
        }
        return result;
    }

    /**
     * Resolves the fully-merged style for a text run: {@code own}'s
     * explicitly-set color/decorations/font win, falling back to
     * {@code inherited} (the merged style of every ancestor up to the
     * whole-nametag root) for anything {@code own} doesn't itself set —
     * exactly like how nested MiniMessage tags/Adventure components
     * actually render.
     */
    private static Style mergeStyle(Style own, Style inherited) {
        Style.Builder builder = Style.style();
        builder.color(own.color() != null ? own.color() : inherited.color());
        for (TextDecoration decoration : TextDecoration.values()) {
            TextDecoration.State state = own.decoration(decoration);
            if (state == TextDecoration.State.NOT_SET) {
                state = inherited.decoration(decoration);
            }
            builder.decoration(decoration, state);
        }
        builder.font(own.font() != null ? own.font() : inherited.font());
        return builder.build();
    }

    /**
     * Replaces every legacy {@code &}-style code in {@code text} with its
     * MiniMessage tag equivalent, leaving any existing MiniMessage tags
     * (e.g. {@code <gradient:...>}, {@code <rainbow>}) completely untouched
     * so both styles can coexist in the same format string.
     */
    private static String convertLegacyToMiniMessage(String text) {
        String result = LEGACY_HEX.matcher(text).replaceAll("<#$1>");
        StringBuilder out = new StringBuilder(result.length());
        Matcher matcher = LEGACY_CODE.matcher(result);
        int last = 0;
        while (matcher.find()) {
            out.append(result, last, matcher.start());
            out.append(legacyTag(matcher.group(1).charAt(0)));
            last = matcher.end();
        }
        out.append(result, last, result.length());
        return out.toString();
    }

    /** MiniMessage tag equivalent of a single legacy formatting character (case-insensitive). */
    private static String legacyTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }

    /**
     * Concatenates every visible character in {@code component}'s tree —
     * its own text plus every child's, in reading order — discarding all
     * color/decoration/hover/click metadata. This is "what the player
     * actually reads": used by the character-limit checks so a heavily
     * colored/placeholder-derived line isn't penalized (or under-penalized)
     * for markup that never appears on screen.
     */
    public static String plainText(Component component) {
        StringBuilder builder = new StringBuilder();
        appendPlainText(component, builder);
        return builder.toString();
    }

    private static void appendPlainText(Component component, StringBuilder builder) {
        if (component instanceof TextComponent text) {
            builder.append(text.content());
        }
        for (Component child : component.children()) {
            appendPlainText(child, builder);
        }
    }

    /** Re-serializes an already-built {@link Component} back into MiniMessage source markup. */
    public static String serialize(Component component) {
        return MINI_MESSAGE.serialize(component);
    }

    /**
     * If {@code line}'s visible text (see {@link #plainText}) is longer than
     * {@code limit} characters, cuts it down to exactly {@code limit}
     * visible characters (preserving each kept character's own
     * color/decoration) and appends a plain white {@code "..."}. Returns
     * {@code line} completely unchanged — same object — if it's already
     * within the limit, or if {@code limit} is negative (no limit).
     */
    public static Component truncateLineWithEllipsis(Component line, int limit) {
        if (limit < 0 || plainText(line).length() <= limit) {
            return line;
        }
        int[] remaining = {limit};
        Component truncated = truncateVisible(line, remaining);
        return truncated.append(Component.text("...", net.kyori.adventure.text.format.NamedTextColor.WHITE));
    }

    /**
     * Cuts {@code line} down to exactly {@code limit} visible characters
     * (see {@link #plainText}), preserving each kept character's own
     * color/decoration — same truncation {@link #truncateLineWithEllipsis}
     * does, but without appending a trailing {@code "..."}. Meant for
     * input that's being constrained to a limit rather than display text
     * that's being cut off (e.g. a widget's per-item character limit,
     * enforced while the player is still typing it in — an ellipsis there
     * would itself eat into the very limit it's supposed to respect).
     * Returns {@code line} completely unchanged — same object — if it's
     * already within the limit, or if {@code limit} is negative (no
     * limit).
     */
    public static Component truncateVisibleCharacters(Component line, int limit) {
        if (limit < 0 || plainText(line).length() <= limit) {
            return line;
        }
        int[] remaining = {limit};
        return truncateVisible(line, remaining);
    }

    private static Component truncateVisible(Component component, int[] remaining) {
        Component result = Component.empty().style(component.style());
        if (remaining[0] > 0 && component instanceof TextComponent text && !text.content().isEmpty()) {
            String content = text.content();
            if (content.length() <= remaining[0]) {
                result = result.append(Component.text(content));
                remaining[0] -= content.length();
            } else {
                result = result.append(Component.text(content.substring(0, remaining[0])));
                remaining[0] = 0;
            }
        }
        for (Component child : component.children()) {
            if (remaining[0] <= 0) {
                break;
            }
            result = result.append(truncateVisible(child, remaining));
        }
        return result;
    }
}
