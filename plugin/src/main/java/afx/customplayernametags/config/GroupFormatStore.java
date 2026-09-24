package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.format.TemplateMarkers;
import afx.customplayernametags.storage.StorageFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists custom nametag formats assigned to LuckPerms groups (set via
 * {@code /nametags format groups set/remove}) to a
 * {@code storage/group-formats.yml} file in the plugin's data folder — kept
 * entirely separate from {@code config.yml} and the per-player stores
 * so it can grow independently and survives plugin reloads/server
 * restarts.
 *
 * <p>File layout:
 * <pre>
 * formats:
 *   vip: '&amp;6[VIP] &amp;f{player}'
 *   admin: '&lt;gradient:red:gold&gt;{player}&lt;/gradient&gt;'
 * </pre>
 * Keyed by lowercased LuckPerms group name, matching how LuckPerms itself
 * treats group names as case-insensitive.
 *
 * <p>A value may also contain
 * {@code {widget colors=true placeholders=true text=false limit=12}%some_placeholder%{/widget}}
 * tag syntax (see {@link afx.customplayernametags.format.TemplateMarkers}),
 * which is stored on disk and returned by {@link #get} in exactly that
 * form — there's no separate internal encoding to convert to or from.
 */
public final class GroupFormatStore {

    private static final String FILE_NAME = "group-formats.yml";
    private static final String EMPTY_FILE_HEADER = "formats: {}\n";

    private final CustomPlayerNametags plugin;
    private final File file;
    private final Map<String, String> formats = new ConcurrentHashMap<>();
    private final Map<String, String> bedrockFormats = new ConcurrentHashMap<>();

    public GroupFormatStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.file = StorageFiles.file(plugin, FILE_NAME);
    }

    /**
     * Loads {@code storage/group-formats.yml} from disk into memory,
     * replacing whatever was previously held. If nothing exists at the
     * destination yet, an empty one is generated on the spot.
     */
    public void load() {
        formats.clear();
        bedrockFormats.clear();
        if (!file.exists()) {
            createEmptyFile();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("formats");

        if (section != null) {
            for (String key : section.getKeys(false)) {
                String format = section.getString(key);
                if (format != null && !format.isEmpty()) {
                    // Assign stable IDs to manually created widget tags.
                    formats.put(key.toLowerCase(Locale.ROOT),
                            TemplateMarkers.ensureWidgetIds(format));
                }
            }
        }
        ConfigurationSection bedrock = yaml.getConfigurationSection("bedrock-formats");
        if (bedrock != null) for (String key : bedrock.getKeys(false)) {
            String format = bedrock.getString(key);
            if (format != null && !format.isEmpty()) {
                bedrockFormats.put(key.toLowerCase(Locale.ROOT),
                        TemplateMarkers.ensureWidgetIds(format));
            }
        }

    }

    private void createEmptyFile() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(EMPTY_FILE_HEADER);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create storage/group-formats.yml: " + e.getMessage());
        }
    }

    /** The stored format for {@code group} (case-insensitive), or {@code null} if there isn't one. */
    public String get(String group) {
        return get(group, false);
    }
    /** Gets the Java or Bedrock-specific group format. */
    public String get(String group, boolean bedrock) {
        if (group == null) {
            return null;
        }
        return (bedrock ? bedrockFormats : formats).get(group.toLowerCase(Locale.ROOT));
    }

    /** Whether a custom format is currently stored for {@code group} (case-insensitive). */
    public boolean contains(String group) {
        return get(group) != null;
    }
    public boolean contains(String group, boolean bedrock) { return get(group, bedrock) != null; }

    /**
     * Sets (or, if {@code format} is {@code null}/blank, clears) the stored
     * format for {@code group} (case-insensitive) and immediately writes
     * the whole store back to {@code group-formats.yml}.
     */
    public void set(String group, String format) {
        set(group, format, false);
    }
    public void set(String group, String format, boolean bedrock) {
        if (group == null) {
            return;
        }
        String key = group.toLowerCase(Locale.ROOT);
        Map<String, String> values = bedrock ? bedrockFormats : formats;
        if (format == null || format.isEmpty()) {
            values.remove(key);
        } else {
            // The GUI editor's serialized form and a format an admin typed
            // directly (via /nametags format groups set) are one and the same
            // tag text, assigning an id to any widget that doesn't already
            // have one (a hand-typed {widget...} tag, most likely).
            values.put(key, TemplateMarkers.ensureWidgetIds(format));
        }
        save();
    }

    /** Clears the stored format for {@code group}, if any, and saves. Equivalent to {@code set(group, null)}. */
    public void remove(String group) {
        set(group, null);
    }
    public void remove(String group, boolean bedrock) { set(group, null, bedrock); }

    /** Every group name (lowercased) that currently has a stored custom format, for tab completion. */
    public List<String> getGroupNames() {
        return new ArrayList<>(formats.keySet());
    }
    public List<String> getGroupNames(boolean bedrock) { return new ArrayList<>((bedrock ? bedrockFormats : formats).keySet()); }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> entry : formats.entrySet()) {
            // formats/bedrockFormats already hold exactly what belongs on
            // disk (see load()/set() above) — widgets included, as tag text.
            yaml.set("formats." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : bedrockFormats.entrySet()) {
            yaml.set("bedrock-formats." + entry.getKey(), entry.getValue());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save storage/group-formats.yml: " + e.getMessage());
        }
    }
}
