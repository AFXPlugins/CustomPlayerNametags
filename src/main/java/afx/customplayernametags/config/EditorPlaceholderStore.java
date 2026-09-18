package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.storage.StorageFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The configurable preset placeholders shown by the player editor. */
public final class EditorPlaceholderStore {
    /**
     * @param title the hover-tooltip title/name shown for this placeholder
     *              item in the {@code /nametag editor}'s placeholder page
     *              (see {@code NametagEditorManager}). {@code null} means
     *              "not explicitly set" — {@link #displayTitle()} falls back
     *              to the placeholder's key in that case.
     */
    public record Placeholder(String key, String value, String title) {
        /** The title actually shown: {@link #title()} if set, otherwise the placeholder's key. */
        public String displayTitle() {
            return (title == null || title.isBlank()) ? key : title;
        }
    }
    private static final String FILE_NAME = "editor-placeholders.yml";

    private final CustomPlayerNametags plugin;
    private final File file;
    private final Map<String, Placeholder> placeholders = new LinkedHashMap<>();

    public EditorPlaceholderStore(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.file = StorageFiles.file(plugin, FILE_NAME);
    }

    public void load() {
        placeholders.clear();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("placeholders");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String path = "placeholders." + key;
                String value = yaml.isConfigurationSection(path) ? yaml.getString(path + ".placeholder", "%" + key + "%") : "%" + key + "%";
                String title = yaml.isConfigurationSection(path) ? yaml.getString(path + ".name") : null;
                if (value != null && !value.isBlank()) placeholders.put(key, new Placeholder(key, value, clean(title)));
            }
            return;
        }
        // Upgrade the former list format without losing administrator entries.
        for (String value : yaml.getStringList("placeholders")) if (valid(value)) addInMemory(value, null);
        if (!placeholders.isEmpty()) save();
    }

    public List<Placeholder> getAll() { return Collections.unmodifiableList(new ArrayList<>(placeholders.values())); }
    public boolean contains(String value) { return find(value) != null; }
    public Placeholder find(String value) {
        for (Placeholder placeholder : placeholders.values()) if (placeholder.value().equals(value)) return placeholder;
        return null;
    }
    public boolean add(String value, String title) {
        if (!valid(value) || contains(value)) return false;
        addInMemory(value, title); save(); return true;
    }
    public boolean remove(String value) {
        Placeholder placeholder = find(value);
        if (placeholder == null) return false;
        placeholders.remove(placeholder.key()); save(); return true;
    }
    /**
     * Updates the tooltip title/name of an already-configured placeholder.
     * Returns {@code false} if {@code value} isn't a currently-configured
     * placeholder.
     */
    public boolean edit(String value, String title) {
        Placeholder existing = find(value);
        if (existing == null) return false;
        placeholders.put(existing.key(), new Placeholder(existing.key(), existing.value(), clean(title)));
        save();
        return true;
    }
    private void addInMemory(String value, String title) {
        String key = value.substring(1, value.length() - 1);
        placeholders.put(key, new Placeholder(key, value, clean(title)));
    }
    private static boolean valid(String value) { return value != null && value.length() >= 3 && value.startsWith("%") && value.endsWith("%"); }
    private static String clean(String title) { return title == null || title.isBlank() ? null : title.trim(); }
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Placeholder placeholder : placeholders.values()) {
            String path = "placeholders." + placeholder.key();
            // Always materialize the section itself first. When the value is
            // the implicit "%<key>%" default AND there's no title, neither
            // of the yaml.set(...) calls below would otherwise fire, which
            // used to mean the entry never made it into the YAML at all —
            // it would "succeed" in memory/in-game but
            // silently vanish on save/reload. createSection() guarantees the
            // key is persisted either way.
            yaml.createSection(path);
            // The key is the placeholder name. The normal %<key>% value is implicit.
            if (!placeholder.value().equals("%" + placeholder.key() + "%")) yaml.set(path + ".placeholder", placeholder.value());
            if (placeholder.title() != null) yaml.set(path + ".name", placeholder.title());
        }
        try { yaml.save(file); } catch (IOException e) { plugin.getLogger().warning("Failed to save storage/editor-placeholders.yml: " + e.getMessage()); }
    }
}
