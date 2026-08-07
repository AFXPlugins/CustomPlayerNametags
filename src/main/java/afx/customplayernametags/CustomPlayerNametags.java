package afx.customplayernametags;

import com.github.retrooper.packetevents.PacketEvents;
import afx.customplayernametags.command.NametagCommand;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.ConfigMigrator;
import afx.customplayernametags.config.MessageManager;
import afx.customplayernametags.config.PlayerFormatStore;
import afx.customplayernametags.listener.PlayerConnectionListener;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagManager;
import afx.customplayernametags.placeholder.CustomPlayerNametagsExpansion;
import afx.customplayernametags.update.UpdateChecker;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class CustomPlayerNametags extends JavaPlugin {

    private static CustomPlayerNametags instance;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private PlayerFormatStore playerFormatStore;
    private NametagManager nametagManager;
    private NametagDisplayManager displayManager;
    private UpdateChecker updateChecker;

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

        updateConfigFile();
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.messageManager = new MessageManager(this);
        this.messageManager.load();

        this.playerFormatStore = new PlayerFormatStore(this);
        this.playerFormatStore.load();

        this.nametagManager = new NametagManager(this, configManager, playerFormatStore);
        this.displayManager = new NametagDisplayManager(this, configManager);
        this.nametagManager.setDisplayManager(displayManager);
        this.displayManager.setNametagManager(nametagManager);

        if (nametagManager.isPlaceholderApiAvailable()) {
            new CustomPlayerNametagsExpansion(this, nametagManager).register();
        }

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, configManager, nametagManager, messageManager), this);

        var nametagCmd = getCommand("nametags");
        if (nametagCmd != null) {
            NametagCommand executor = new NametagCommand(this, configManager, nametagManager, messageManager);
            nametagCmd.setExecutor(executor);
            nametagCmd.setTabCompleter(executor);
        }

        nametagManager.startRefreshTask();
        displayManager.start();

        this.updateChecker = new UpdateChecker(this);
        updateChecker.check(this::logUpdateCheckResult);

        getLogger().info("CustomPlayerNametags enabled.");
    }

    /**
     * Brings an existing {@code config.yml} up to date with whatever this
     * jar version bundles — renaming keys that changed name (e.g. the old
     * {@code plugin-version} field, now {@code config-version}) and adding
     * any new keys introduced since the server owner last updated — while
     * preserving their existing values and comments. No-op on a fresh
     * install, since there's no file yet for {@link #saveDefaultConfig()}
     * to touch.
     */
    public void updateConfigFile() {
        ConfigMigrator.update(this, new File(getDataFolder(), "config.yml"), "config.yml");
    }

    /**
     * Logs the outcome of an {@link UpdateChecker} run to console. Used
     * both for the automatic startup check and for {@code /nametags update}
     * when it's run from the console.
     */
    public void logUpdateCheckResult(UpdateChecker.Result result) {
        if (!result.isSuccess()) {
            getLogger().warning("Could not check for CustomPlayerNametags updates: "
                    + result.getFailureReason());
            return;
        }
        if (result.isUpdateAvailable()) {
            getLogger().warning("A new version of CustomPlayerNametags is available: v"
                    + result.getLatestVersion() + " (currently running v"
                    + getDescription().getVersion() + "). Get it here: " + result.getReleaseUrl());
        } else {
            getLogger().info("CustomPlayerNametags is up to date (v"
                    + getDescription().getVersion() + ").");
        }
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

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public PlayerFormatStore getPlayerFormatStore() {
        return playerFormatStore;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}