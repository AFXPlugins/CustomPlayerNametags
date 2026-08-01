package afx.customplayernametags;

import com.github.retrooper.packetevents.PacketEvents;
import afx.customplayernametags.command.NametagCommand;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.PlayerFormatStore;
import afx.customplayernametags.listener.PlayerConnectionListener;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomPlayerNametags extends JavaPlugin {

    private static CustomPlayerNametags instance;

    private ConfigManager configManager;
    private PlayerFormatStore playerFormatStore;
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

        this.playerFormatStore = new PlayerFormatStore(this);
        this.playerFormatStore.load();

        this.nametagManager = new NametagManager(this, configManager, playerFormatStore);
        this.displayManager = new NametagDisplayManager(this, configManager);
        this.nametagManager.setDisplayManager(displayManager);
        this.displayManager.setNametagManager(nametagManager);

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, configManager, nametagManager), this);

        var nametagCmd = getCommand("nametags");
        if (nametagCmd != null) {
            NametagCommand executor = new NametagCommand(this, configManager, nametagManager);
            nametagCmd.setExecutor(executor);
            nametagCmd.setTabCompleter(executor);
        }

        nametagManager.startRefreshTask();
        displayManager.start();

        getLogger().info("CustomPlayerNametags enabled.");
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

    public PlayerFormatStore getPlayerFormatStore() {
        return playerFormatStore;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }
}
