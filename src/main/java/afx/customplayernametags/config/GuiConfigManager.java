package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.storage.StorageFiles;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads every menu's layout — which slot each button sits in, the item
 * representing it, its display name, and its hover-tooltip lore — from
 * {@code gui.yml}. Follows the same "external file backed by a bundled
 * default" pattern {@link MessageManager} uses for chat messages: every
 * field is looked up independently, so a file that only overrides one
 * button's slot doesn't lose that button's name or lore, and a missing or
 * misspelled field just falls back to this plugin's built-in default for
 * that one field rather than breaking the whole menu.
 *
 * <p>This only covers each menu's fixed action buttons (Close, Confirm,
 * Back, and so on) — content that's generated dynamically from a player's
 * actual format (the chunk grid, the saved-nametag list, the online-player
 * list) has no fixed slot/material to configure and isn't part of this
 * file.
 */
public final class GuiConfigManager {

    private static final String FILE_NAME = "gui.yml";

    private final CustomPlayerNametags plugin;
    private final File file;
    private FileConfiguration gui;
    private FileConfiguration defaults;

    public GuiConfigManager(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.file = StorageFiles.file(plugin, FILE_NAME);
    }

    /**
     * Loads (or reloads) {@code storage/gui.yml} from disk, writing the
     * bundled default file if nothing exists at the destination yet.
     */
    public void load() {
        if (!file.exists()) {
            StorageFiles.saveResource(plugin, FILE_NAME, false);
        }
        this.gui = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource(FILE_NAME)) {
            if (in != null) {
                this.defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load bundled default gui.yml: " + e.getMessage());
        }
    }

    /**
     * Overwrites the on-disk storage/gui.yml with the bundled default.
     * Called once on server start, before {@link #load()}, so gui.yml is
     * deliberately reset to the shipped defaults every time the server
     * starts (matching {@link MessageManager#resetToDefault()}).
     */
    public void resetToDefault() {
        StorageFiles.saveResource(plugin, FILE_NAME, true);
    }

    /** One configured button: its slot, material, already-colored display name, and already-colored lore lines. */
    public record Button(int slot, Material material, String name, List<String> lore) {
        public String[] loreArray() {
            return lore.toArray(new String[0]);
        }
    }

    /** The colored title configured for {@code menuKey} (e.g. {@code "editor"}), or {@code fallback} if unset anywhere. */
    public String title(String menuKey, String fallback) {
        return color(string(menuKey + ".title", fallback));
    }

    /**
     * Just the slot configured at {@code menuKey.buttonKey}, without
     * building a full {@link Button}. Used for routing an inventory click
     * back to the right action — kept in sync with {@link #button} because
     * both read the exact same {@code menuKey.buttonKey.slot} path.
     */
    public int slot(String menuKey, String buttonKey, int fallbackSlot) {
        return integer(menuKey + "." + buttonKey + ".slot", fallbackSlot);
    }

    /**
     * Just the material configured at {@code menuKey.buttonKey}, without
     * building a full {@link Button}. Used for anything that only needs to
     * mirror a menu's configured item — e.g. the chunk-grid representation
     * of an already-added format piece, which should always show the exact
     * same material as the button that adds that kind of piece, without
     * also pulling in that button's own slot/name/lore.
     */
    public Material material(String menuKey, String buttonKey, Material fallback) {
        return materialAt(menuKey + "." + buttonKey + ".material", fallback);
    }

    /**
     * The button configured at {@code menuKey.buttonKey}. Each of
     * slot/material/name/lore falls back independently — first to
     * gui.yml's own bundled defaults, then to the literal
     * {@code fallback*} arguments passed here — so this never returns a
     * broken button even on a corrupt or half-written file.
     */
    public Button button(String menuKey, String buttonKey, int fallbackSlot, Material fallbackMaterial,
                          String fallbackName, String... fallbackLore) {
        return button(menuKey, buttonKey, fallbackSlot, fallbackMaterial, fallbackName, Map.of(), fallbackLore);
    }

    /**
     * Same as {@link #button(String, String, int, Material, String, String...)}, but
     * with {@code {token}}-style {@code placeholders} substituted into both
     * the resolved name and every resolved lore line (whether that text came
     * from {@code gui.yml} or from the {@code fallback*} literals) — e.g.
     * {@code Map.of("{limit}", "3")} turns a lore line of
     * {@code "You've reached your line limit ({limit})"} into
     * {@code "You've reached your line limit (3)"}. Without this, a
     * {@code {token}} left in gui.yml would never get filled in, since
     * {@link #string} and {@link #stringList} just return text verbatim.
     */
    public Button button(String menuKey, String buttonKey, int fallbackSlot, Material fallbackMaterial,
                          String fallbackName, Map<String, String> placeholders, String... fallbackLore) {
        String path = menuKey + "." + buttonKey;
        int slot = integer(path + ".slot", fallbackSlot);
        Material material = materialAt(path + ".material", fallbackMaterial);
        String name = color(applyPlaceholders(string(path + ".name", fallbackName), placeholders));
        List<String> lore = new ArrayList<>();
        for (String line : stringList(path + ".lore", Arrays.asList(fallbackLore))) {
            lore.add(color(applyPlaceholders(line, placeholders)));
        }
        // A lore field left out entirely (not present in the user's file, the
        // bundled default, or the fallback literals passed by the caller)
        // resolves to an empty list — the item is given no tooltip at all
        // rather than one synthesized from its name.
        return new Button(slot, material, name, lore);
    }

    private static String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (var entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String string(String path, String fallback) {
        if (gui != null && gui.isString(path)) {
            return gui.getString(path);
        }
        if (defaults != null && defaults.isString(path)) {
            return defaults.getString(path);
        }
        return fallback;
    }

    private int integer(String path, int fallback) {
        if (gui != null && gui.isInt(path)) {
            return gui.getInt(path);
        }
        if (defaults != null && defaults.isInt(path)) {
            return defaults.getInt(path);
        }
        return fallback;
    }

    private List<String> stringList(String path, List<String> fallback) {
        if (gui != null && gui.isList(path)) {
            return gui.getStringList(path);
        }
        if (defaults != null && defaults.isList(path)) {
            return defaults.getStringList(path);
        }
        return fallback;
    }

    private Material materialAt(String path, Material fallback) {
        String raw = string(path, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("gui.yml: unknown material '" + raw + "' at " + path + "; using the default instead.");
            return fallback;
        }
    }

    private static String color(String s) {
        return s == null ? null : ChatColor.translateAlternateColorCodes('&', s);
    }
}
