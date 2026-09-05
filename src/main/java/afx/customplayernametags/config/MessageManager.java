package afx.customplayernametags.config;

import afx.customplayernametags.CustomPlayerNametags;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads every player-facing message from {@code messages.yml} and serves
 * them with '&' color codes translated and {@code {placeholder}} tokens
 * substituted. This is the single source of every message the plugin sends,
 * so server owners can reword or re-color anything without touching code.
 *
 * <p>If a key is missing from the on-disk {@code messages.yml} (e.g. an
 * owner deleted a line, or a plugin update added a new message), the
 * bundled default for that key is used instead, so a partially-edited file
 * never breaks — only the missing key falls back.
 */
public final class MessageManager {

    private final CustomPlayerNametags plugin;
    private final File file;

    private FileConfiguration messages;
    private FileConfiguration defaults;

    public MessageManager(CustomPlayerNametags plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    /** Loads (or reloads) messages.yml from disk, writing the bundled default file first if it doesn't exist yet. */
    public void load() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                this.defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load bundled default messages.yml: " + e.getMessage());
        }
    }

    /** Overwrites the on-disk messages.yml with the bundled default. Intended to be called once on server start, before {@link #load()}, so messages.yml resets every time the server starts. */
    public void resetToDefault() {
        plugin.saveResource("messages.yml", true);
    }

    private String raw(String key) {
        if (messages != null && messages.isString(key)) {
            return messages.getString(key);
        }
        if (defaults != null) {
            return defaults.getString(key);
        }
        return null;
    }

    private List<String> rawList(String key) {
        if (messages != null && messages.isList(key)) {
            return messages.getStringList(key);
        }
        if (defaults != null) {
            return defaults.getStringList(key);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the message for {@code key} with color codes translated and
     * {@code placeholders} substituted. {@code placeholders} is a flat list
     * of alternating token/value pairs, e.g.
     * {@code get("format-set-player-success", "{player}", target.getName())}.
     */
    public String get(String key, String... placeholders) {
        String message = raw(key);
        if (message == null) {
            // Missing from both the on-disk file and the bundled jar default
            // (a typo'd key) — surface it clearly instead of sending blank chat.
            return ChatColor.translateAlternateColorCodes('&', "&cMissing message: " + key);
        }
        return ChatColor.translateAlternateColorCodes('&', applyPlaceholders(message, placeholders));
    }

    /** Same as {@link #get(String, String...)} but for a multi-line usage block. */
    public List<String> getList(String key, String... placeholders) {
        List<String> lines = rawList(key);
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(line, placeholders)));
        }
        return result;
    }

    /** Sends the single-line message for {@code key} to {@code sender}. */
    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(get(key, placeholders));
    }

    /** Sends every line of the list message for {@code key} to {@code sender}. */
    public void sendList(CommandSender sender, String key, String... placeholders) {
        for (String line : getList(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    private static String applyPlaceholders(String message, String... placeholders) {
        String result = message;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace(placeholders[i], placeholders[i + 1]);
        }
        return result;
    }
}