package afx.customplayernametags.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a plugin's on-disk {@code config.yml} in sync with the version bundled
 * in the jar across updates, without discarding a server owner's existing
 * values or comments.
 * <p>
 * Runs in two steps, in order:
 * <ol>
 *   <li>{@link #renameKey}: fixes up any keys that were renamed between
 *   releases (e.g. {@code plugin-version} &rarr; {@code config-version}) by
 *   rewriting just that line in place, so the value, comments, and
 *   formatting around it are untouched.</li>
 *   <li>{@link ConfigUpdater#update}: adds any keys present in the bundled
 *   default but missing from the user's file (new options introduced by an
 *   update), again preserving comments and every existing value.</li>
 * </ol>
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /**
     * Brings {@code configFile} up to date against the {@code resourceName}
     * bundled in {@code plugin}'s jar. Safe to call every {@code onEnable()};
     * it's effectively a no-op once the file is already current.
     *
     * @param plugin       the owning plugin, used to read the bundled default and for logging
     * @param configFile   the live file on disk, e.g. {@code new File(getDataFolder(), "config.yml")}
     * @param resourceName the bundled resource path, e.g. {@code "config.yml"}
     */
    public static void update(JavaPlugin plugin, File configFile, String resourceName) {
        if (!configFile.exists()) {
            // First install — saveDefaultConfig() will lay down a fresh copy
            // that's already current, nothing to migrate or merge.
            return;
        }

        try {
            // Step 1: key renames. Add one renameKey(...) call per rename
            // that's ever shipped, oldest first, so a server that skipped
            // several updates still catches up correctly.
            renameKey(configFile, "plugin-version", "config-version");

            // Step 1b: key removals. bedrock-dismount-height-adjust and
            // bedrock-line-height-adjust were removed — the former is now
            // always computed as the opposite of bedrock-height-adjust
            // internally, and the latter was removed entirely (Bedrock/Geyser
            // already keeps a multi-line tag's bottom line in the right
            // place on its own). Strip any leftover lines from existing
            // installs so they don't linger in config.yml doing nothing.
            removeKey(configFile, "bedrock-dismount-height-adjust");
            removeKey(configFile, "bedrock-line-height-adjust");

            // Step 2: add anything new. ignoredSections is empty here since
            // nothing in this config needs to be left alone during merges;
            // pass section names (e.g. "messages") if you ever want the
            // updater to leave a whole block as the user configured it.
            List<String> ignoredSections = Collections.emptyList();
            ConfigUpdater.update(plugin, resourceName, configFile, ignoredSections);

            // Step 3: config-version is a "please don't touch" marker, not
            // a user-tunable option — ConfigUpdater otherwise treats every
            // key the same way and carries an existing value straight
            // through, which would leave this permanently stuck at
            // whatever it was the very first time a server owner's file
            // was generated. Force it to the bundled jar's value every
            // time instead, same as version markers in other AFX plugins.
            forceBundledValue(plugin, resourceName, configFile, "config-version");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not update " + configFile.getName() + ": " + e.getMessage()
                    + ". Delete the file to regenerate it, or fix it manually.");
        }
    }

    /**
     * Overwrites a single top-level {@code key:} line in {@code configFile}
     * with that same key's line from the bundled {@code resourceName}
     * inside the jar, regardless of what the on-disk file currently has.
     * Unlike everything else this class does, this deliberately discards
     * the user's existing value — only appropriate for a marker the plugin
     * itself owns (like {@code config-version}), never for an actual
     * setting. No-ops if either file is missing the key.
     */
    private static void forceBundledValue(JavaPlugin plugin, String resourceName, File configFile, String key) throws IOException {
        String bundledLine = null;
        try (java.io.InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                return;
            }
            List<String> bundledLines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(java.util.stream.Collectors.toList());
            Pattern pattern = Pattern.compile("^" + Pattern.quote(key) + "\\s*:.*$");
            for (String line : bundledLines) {
                if (pattern.matcher(line).matches()) {
                    bundledLine = line;
                    break;
                }
            }
        }

        if (bundledLine == null) {
            return;
        }

        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile("^" + Pattern.quote(key) + "\\s*:.*$");
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).matches() && !lines.get(i).equals(bundledLine)) {
                lines.set(i, bundledLine);
                changed = true;
            }
        }

        if (changed) {
            Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
        }
    }

    /**
     * Renames a top-level {@code oldKey:} to {@code newKey:} by rewriting
     * only the matching line, so the value, any trailing inline comment, and
     * every other line (including comments/blank lines/indented children)
     * are byte-for-byte unchanged. No-ops if {@code oldKey} isn't present or
     * {@code newKey} is already there (already migrated).
     */
    static void renameKey(File configFile, String oldKey, String newKey) throws IOException {
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);

        // Matches a top-level (unindented) "oldKey:" possibly followed by a
        // value and/or an inline "# comment" on the same line. Group 1 keeps
        // everything after the key name (the ": value  # comment" part) so
        // it's carried over unchanged.
        Pattern pattern = Pattern.compile("^" + Pattern.quote(oldKey) + "(\\s*:.*)$");

        boolean foundOld = false;
        boolean foundNew = false;
        int matchIndex = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(newKey + ":")) {
                foundNew = true;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                foundOld = true;
                matchIndex = i;
            }
        }

        if (!foundOld || foundNew) {
            // Nothing to rename, or the user's file was already migrated
            // (or hand-edited to have both) — leave it alone either way.
            return;
        }

        Matcher matcher = pattern.matcher(lines.get(matchIndex));
        if (matcher.matches()) {
            lines.set(matchIndex, newKey + matcher.group(1));
            Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
        }
    }

    /**
     * Deletes a top-level {@code key:} line (and its value) from
     * {@code configFile} entirely, for an option removed in a later release.
     * No-ops if the key isn't present. Only ever removes the single matching
     * line itself — any surrounding comments are left as-is (harmless
     * leftover documentation), which keeps this safe to run against
     * hand-edited files without guessing which comment block belongs to it.
     */
    static void removeKey(File configFile, String key) throws IOException {
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile("^" + Pattern.quote(key) + "\\s*:.*$");

        boolean changed = false;
        List<String> updated = new java.util.ArrayList<>(lines.size());
        for (String line : lines) {
            if (pattern.matcher(line).matches()) {
                changed = true;
                continue;
            }
            updated.add(line);
        }

        if (changed) {
            Files.write(configFile.toPath(), updated, StandardCharsets.UTF_8);
        }
    }
}