package afx.customplayernametags;

import com.github.retrooper.packetevents.PacketEvents;
import dev.customplayernametags.command.NametagCommand;
import dev.customplayernametags.config.ConfigManager;
import dev.customplayernametags.listener.PlayerConnectionListener;
import dev.customplayernametags.manager.NametagDisplayManager;
import dev.customplayernametags.manager.NametagManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomPlayerNametags extends JavaPlugin {

    private static CustomPlayerNametags instance;

    private ConfigManager configManager;
    private NametagManager nametagManager;
    private NametagDisplayManager displayManager;

    @Override
    public void onLoad() {
        instance = this;
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .bStats(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.nametagManager = new NametagManager(this, configManager);
        this.displayManager = new NametagDisplayManager(this, configManager);
        this.nametagManager.setDisplayManager(displayManager);
        this.displayManager.setNametagManager(nametagManager);

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, nametagManager), this);

        var nametagCmd = getCommand("nametags");
        if (nametagCmd != null) {
            NametagCommand executor = new NametagCommand(this, configManager, nametagManager);
            nametagCmd.setExecutor(executor);
            nametagCmd.setTabCompleter(executor);
        }

        nametagManager.startRefreshTask();
        displayManager.start();

        getLogger().info("CustomPlayerNametags enabled (display-entity nametags for all players, no name spoofing).");
    }

    @Override
    public void onDisable() {
        if (nametagManager != null) {
            nametagManager.shutdown();
        }
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        getLogger().info("CustomPlayerNametags disabled.");
    }

    public static CustomPlayerNametags getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }
}