package afx.customplayernametags.config;

import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Brings a server owner's on-disk {@code config.yml} up to date against the
 * version bundled in the plugin's jar: every key the jar's default defines
 * ends up present in the on-disk file, using the owner's existing value
 * where they already have one and the jar's default otherwise, while the
 * jar's comments, headers, and key ordering become the file's new structure.
 *
 * <p>This intentionally only understands a <b>flat</b> config: top-level
 * {@code key: value} lines, top-level {@code key:} lines immediately
 * followed by an indented {@code - item} list, comments, and blank lines.
 * That's the entirety of what this plugin's config.yml actually contains —
 * it has no nested sections. Earlier versions of this class vendored a
 * much larger third-party library (see the removed {@code NOTICE.txt}) that
 * handled arbitrary nested YAML via a from-scratch YAML re-serializer and a
 * line-scanning heuristic to re-attach comments to keys. That generality
 * bought a long list of failure modes this plugin never needed: comments
 * silently detaching from the wrong key, numeric/boolean values getting
 * rewritten with SnakeYAML's own quoting and tag conventions instead of the
 * owner's, list items losing their indentation, and a couple of code paths
 * (a missing bundled resource, a missing on-disk file) throwing exceptions
 * that weren't actually {@link IOException} and so slipped past the
 * {@code catch (IOException e)} around every call site — meaning an update
 * could fail silently, leaving keys stale with no obvious error explaining
 * why. This class reads and writes the file as plain text with no
 * re-serialization step, so an owner's existing value is copied through
 * byte-for-byte and the bundled file's formatting is never altered.
 */
public final class ConfigUpdater {

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([^\\s#][^:]*):(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*-.*$");

    private ConfigUpdater() {
    }

    /**
     * @param plugin       the plugin instance, used to read the bundled default out of the jar.
     * @param resourceName the path to the default file inside the jar, e.g. {@code "config.yml"}.
     * @param toUpdate     the on-disk file to bring up to date. Must already exist.
     * @param ignoredKeys  top-level keys to leave completely untouched — the on-disk file's
     *                     existing line(s) for that key are kept exactly as they are, without
     *                     even adopting the bundled file's comment for it.
     * @throws IOException if {@code toUpdate} doesn't exist, the bundled resource can't be
     *                      found in the jar, or reading/writing either file fails.
     */
    public static void update(Plugin plugin, String resourceName, File toUpdate, String... ignoredKeys) throws IOException {
        update(plugin, resourceName, toUpdate, Arrays.asList(ignoredKeys));
    }

    /**
     * @param plugin       the plugin instance, used to read the bundled default out of the jar.
     * @param resourceName the path to the default file inside the jar, e.g. {@code "config.yml"}.
     * @param toUpdate     the on-disk file to bring up to date. Must already exist.
     * @param ignoredKeys  top-level keys to leave completely untouched — the on-disk file's
     *                     existing line(s) for that key are kept exactly as they are, without
     *                     even adopting the bundled file's comment for it.
     * @throws IOException if {@code toUpdate} doesn't exist, the bundled resource can't be
     *                      found in the jar, or reading/writing either file fails.
     */
    public static void update(Plugin plugin, String resourceName, File toUpdate, List<String> ignoredKeys) throws IOException {
        if (!toUpdate.exists()) {
            // Previously an IllegalArgumentException via Preconditions.checkArgument,
            // which is NOT an IOException — every call site only catches
            // IOException, so this used to be able to crash straight past
            // that handling instead of being logged and recovered from.
            throw new IOException("Cannot update a config file that doesn't exist: " + toUpdate.getPath());
        }

        List<String> ignored = ignoredKeys == null ? Collections.emptyList() : ignoredKeys;
        List<String> defaultLines = readBundledLines(plugin, resourceName);
        List<String> currentLines = Files.readAllLines(toUpdate.toPath(), StandardCharsets.UTF_8);

        Map<String, String> currentScalarValues = new LinkedHashMap<>();
        Map<String, List<String>> currentListValues = new LinkedHashMap<>();
        parseTopLevelValues(currentLines, currentScalarValues, currentListValues);

        List<String> output = new ArrayList<>(defaultLines.size());
        int i = 0;
        while (i < defaultLines.size()) {
            String line = defaultLines.get(i);
            Matcher keyMatcher = TOP_LEVEL_KEY.matcher(line);

            if (!keyMatcher.matches()) {
                // Comment, blank line, or (shouldn't happen in this flat
                // config, but handled safely anyway) an indented line not
                // immediately following a list-valued key.
                output.add(line);
                i++;
                continue;
            }

            String key = unquote(keyMatcher.group(1).trim());
            String trailing = keyMatcher.group(2).trim();

            // Always scan past the default's own list items (if any) here,
            // whether or not we end up using them, so the loop's index
            // stays correct regardless of which branch below fires.
            int next = i + 1;
            List<String> defaultListItems = new ArrayList<>();
            if (trailing.isEmpty()) {
                while (next < defaultLines.size() && LIST_ITEM.matcher(defaultLines.get(next)).matches()) {
                    defaultListItems.add(defaultLines.get(next));
                    next++;
                }
            }

            if (ignored.contains(key)) {
                output.add(line);
                output.addAll(defaultListItems);
                i = next;
                continue;
            }

            if (!defaultListItems.isEmpty()) {
                // List-valued key: keep the owner's own list if they have
                // one, otherwise fall back to the bundled default's.
                List<String> ownList = currentListValues.get(key);
                output.add(key + ":");
                output.addAll(ownList != null ? ownList : defaultListItems);
                i = next;
                continue;
            }

            if (trailing.isEmpty()) {
                // A key with nothing after its colon and no list following
                // it. Not a shape this plugin's config actually uses, but
                // there's nothing sensible to substitute — keep the
                // bundled line as-is rather than guessing.
                output.add(line);
                i = next;
                continue;
            }

            // The common case: a single-line "key: value" pair. Keep the
            // owner's own value if they have one, otherwise the bundled
            // default's.
            String currentValue = currentScalarValues.get(key);
            output.add(key + ": " + (currentValue != null ? currentValue : trailing));
            i = next;
        }

        String newContent = joinWithTrailingNewline(output);
        String oldContent = joinWithTrailingNewline(currentLines);
        if (!newContent.equals(oldContent)) {
            Files.write(toUpdate.toPath(), newContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Scans {@code lines} for top-level keys and records each one's value:
     * into {@code scalars} for a plain {@code key: value} line, or into
     * {@code lists} for a {@code key:} line immediately followed by one or
     * more indented {@code - item} lines. A key with neither (an empty
     * value and no list following it) is recorded in neither map, which is
     * correct — there is nothing for {@link #update} to carry over for it.
     */
    private static void parseTopLevelValues(List<String> lines, Map<String, String> scalars,
                                            Map<String, List<String>> lists) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher keyMatcher = TOP_LEVEL_KEY.matcher(line);
            if (!keyMatcher.matches()) {
                i++;
                continue;
            }

            String key = unquote(keyMatcher.group(1).trim());
            String trailing = keyMatcher.group(2).trim();
            int next = i + 1;

            if (trailing.isEmpty()) {
                List<String> items = new ArrayList<>();
                while (next < lines.size() && LIST_ITEM.matcher(lines.get(next)).matches()) {
                    items.add(lines.get(next));
                    next++;
                }
                if (!items.isEmpty()) {
                    lists.put(key, items);
                }
            } else {
                scalars.put(key, trailing);
            }

            i = next;
        }
    }

    private static List<String> readBundledLines(Plugin plugin, String resourceName) throws IOException {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                // Previously a NullPointerException from wrapping this null
                // straight into an InputStreamReader — again, not an
                // IOException, so it wasn't caught by call sites either.
                throw new IOException("Bundled resource not found in jar: " + resourceName);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.toList());
            }
        }
    }

    private static String unquote(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static String joinWithTrailingNewline(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }
}