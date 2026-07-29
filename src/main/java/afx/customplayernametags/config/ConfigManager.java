package dev.customplayernametags.config;

import dev.customplayernametags.CustomPlayerNametags;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigManager {

    private static final String DEFAULT_FORMAT = "%luckperms_prefix%%essentials_nickname%";

    private final CustomPlayerNametags plugin;

    private String nametagFormat;
    private double nametagHeightOffset;

    public ConfigManager(CustomPlayerNametags plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.nametagFormat = cfg.getString("nametag-format", DEFAULT_FORMAT);
        this.nametagHeightOffset = cfg.getDouble("nametag-height-offset", 2.1);
    }

    public String getNametagFormat() {
        return nametagFormat;
    }

    public double getNametagHeightOffset() {
        return nametagHeightOffset;
    }
}
