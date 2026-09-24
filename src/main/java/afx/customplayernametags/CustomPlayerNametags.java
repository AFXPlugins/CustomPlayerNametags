package afx.customplayernametags;

import com.github.retrooper.packetevents.PacketEvents;
import afx.customplayernametags.command.NametagCommand;
import afx.customplayernametags.command.PlayerNametagCommand;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.ConfigMigrator;
import afx.customplayernametags.config.EditorPlaceholderStore;
import afx.customplayernametags.config.GuiConfigManager;
import afx.customplayernametags.config.GroupFormatStore;
import afx.customplayernametags.config.MessageManager;
import afx.customplayernametags.config.NametagVisibilityStore;
import afx.customplayernametags.config.PlayerFormatStore;
import afx.customplayernametags.config.PlayerWidgetFillStore;
import afx.customplayernametags.format.TemplateMarkers;
import afx.customplayernametags.listener.NametagEditorListener;
import afx.customplayernametags.listener.PlayerConnectionListener;
import afx.customplayernametags.manager.EditorRelayClient;
import afx.customplayernametags.manager.MultiverseIntegration;
import afx.customplayernametags.manager.NametagDisplayManager;
import afx.customplayernametags.manager.NametagEditorManager;
import afx.customplayernametags.manager.NametagManager;
import afx.customplayernametags.placeholder.CustomPlayerNametagsExpansion;
import afx.customplayernametags.storage.InstallHistory;
import afx.customplayernametags.update.UpdateChecker;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

public final class CustomPlayerNametags extends JavaPlugin {

    private static CustomPlayerNametags instance;

    /**
     * Upper bound (in blocks) of each bucket used to report a nametag
     * height-adjust config value to bStats, ascending. {@code 0} itself is
     * its own bucket (handled separately in {@link #bucketHeightAdjust});
     * every other value is bucketed by its distance from {@code 0}, with
     * the sign folded back in afterward — so the same thresholds are
     * reused for both the positive and negative side of the range.
     */
    private static final double[] HEIGHT_ADJUST_BUCKET_THRESHOLDS =
            {0.05, 0.10, 0.15, 0.20, 0.30, 0.40, 0.50, 1.00, 2.00};

    /**
     * This plugin's ID on bStats (https://bstats.org) — the number in the
     * URL of this plugin's bStats page, NOT a version or build number.
     * Replace this placeholder with the real ID after registering the
     * plugin at https://bstats.org/what-is-my-plugin-id, or metrics will
     * either fail to report or report against the wrong plugin. Enter it
     * as a plain decimal number with no leading zero — a leading zero
     * makes Java read it as octal instead, silently changing the value.
     */
    private static final int BSTATS_PLUGIN_ID = 33912;

    private ConfigManager configManager;
    private GuiConfigManager guiConfigManager;
    private MessageManager messageManager;
    private PlayerFormatStore playerFormatStore;
    private PlayerWidgetFillStore playerWidgetFillStore;
    private GroupFormatStore groupFormatStore;
    private EditorPlaceholderStore editorPlaceholderStore;
    private NametagVisibilityStore nametagVisibilityStore;
    private NametagManager nametagManager;
    private NametagDisplayManager displayManager;
    private NametagEditorManager nametagEditorManager;
    private EditorRelayClient editorRelayClient;
    private UpdateChecker updateChecker;
    private Metrics metrics;
    private InstallHistory installHistory;

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

        // Must run before updateConfigFile()/saveDefaultConfig(): it tells an
        // update from a fresh install by whether config.yml already exists.
        this.installHistory = new InstallHistory(this);

        updateConfigFile();
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.messageManager = new MessageManager(this);
        this.messageManager.resetToDefault();
        this.messageManager.load();

        this.guiConfigManager = new GuiConfigManager(this);
        this.guiConfigManager.resetToDefault();
        this.guiConfigManager.load();

        this.playerFormatStore = new PlayerFormatStore(this);
        this.playerFormatStore.load();

        this.playerWidgetFillStore = new PlayerWidgetFillStore(this);
        this.playerWidgetFillStore.load();

        this.groupFormatStore = new GroupFormatStore(this);
        this.groupFormatStore.load();

        this.editorPlaceholderStore = new EditorPlaceholderStore(this);
        this.editorPlaceholderStore.load();

        this.nametagVisibilityStore = new NametagVisibilityStore(this);
        this.nametagVisibilityStore.load();

        this.nametagManager = new NametagManager(this, configManager, playerFormatStore, playerWidgetFillStore,
                groupFormatStore, nametagVisibilityStore);
        this.displayManager = new NametagDisplayManager(this, configManager);
        this.nametagManager.setDisplayManager(displayManager);
        this.displayManager.setNametagManager(nametagManager);

        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        registerMetricsCharts();

        if (nametagManager.isPlaceholderApiAvailable()) {
            new CustomPlayerNametagsExpansion(this, nametagManager).register();
        }

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, configManager, nametagManager, messageManager), this);

        this.nametagEditorManager = new NametagEditorManager(this, configManager, nametagManager, messageManager,
                editorPlaceholderStore, guiConfigManager);
        getServer().getPluginManager().registerEvents(new NametagEditorListener(nametagEditorManager), this);

        this.editorRelayClient = new EditorRelayClient(this);

        var nametagCmd = getCommand("nametags");
        if (nametagCmd != null) {
            NametagCommand executor = new NametagCommand(this, configManager, nametagManager, messageManager);
            nametagCmd.setExecutor(executor);
            nametagCmd.setTabCompleter(executor);
        }

        // "nametag" (singular) — a completely separate command/permission
        // from "nametags" (see plugin.yml), for a player managing only
        // their own individual nametag.
        var nametagSelfCmd = getCommand("nametag");
        if (nametagSelfCmd != null) {
            PlayerNametagCommand selfExecutor = new PlayerNametagCommand(nametagEditorManager, messageManager);
            nametagSelfCmd.setExecutor(selfExecutor);
            nametagSelfCmd.setTabCompleter(selfExecutor);
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
     * Adds this plugin's custom bStats charts on top of the default charts
     * {@link Metrics} already reports for free (player count, server
     * software, MC/Java version, etc.). Each supplier below is polled by
     * bStats on its own schedule, not called from here — so it always
     * reflects live state, not a value frozen at startup.
     */
    private void registerMetricsCharts() {
        metrics.addCustomChart(new SimplePie("dismount_mode",
                () -> configManager.getNametagDismountMode().name()));
        metrics.addCustomChart(new SimplePie("placeholderapi_installed",
                () -> nametagManager.isPlaceholderApiAvailable() ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("geyser_floodgate_installed",
                () -> getServer().getPluginManager().isPluginEnabled("floodgate") ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("bedrock_height_adjust_value",
                () -> bucketHeightAdjust(configManager.getBedrockHeightAdjustConfig())));
        metrics.addCustomChart(new SimplePie("bedrock_sneak_height_adjust_value",
                () -> bucketHeightAdjust(configManager.getBedrockSneakHeightAdjustConfig())));
        metrics.addCustomChart(new SimplePie("bedrock_per_line_height_adjust_value",
                () -> bucketHeightAdjust(configManager.getBedrockPerLineHeightAdjustConfig())));
        metrics.addCustomChart(new SimplePie("global_height_adjust_value",
                () -> bucketHeightAdjust(configManager.getGlobalHeightAdjust())));
        metrics.addCustomChart(new SimplePie("customadvancementmessages_installed",
                () -> getServer().getPluginManager().isPluginEnabled("CustomAdvancementMessages") ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("luckperms_installed",
                () -> getServer().getPluginManager().isPluginEnabled("LuckPerms") ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("multiverse_installed",
                () -> getServer().getPluginManager().isPluginEnabled("Multiverse-Core") ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("multiverse_portals_installed",
                () -> MultiverseIntegration.isPortalsAvailable() ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("widget_item_in_use",
                () -> isWidgetItemInUse() ? "Yes" : "No"));
        // "Yes" = this version was reached by updating from an earlier one;
        // "No" = fresh install. See InstallHistory.
        metrics.addCustomChart(new SimplePie("updated_from_previous_version",
                () -> installHistory.wasUpdatedFromPreviousVersion() ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie("widget_truncate_indicator_enabled",
                () -> configManager.isWidgetTruncationEllipsisEnabled() ? "Yes" : "No"));
    }

    /**
     * Whether any format currently active anywhere on the server — the global
     * format, the Bedrock global format, any per-group format, or any
     * per-player override — contains a Widget item. Every one of those is
     * stored as the same {@code {widget ...}...{/widget}} tag text (see
     * {@link ConfigManager#getNametagFormat()},
     * {@link GroupFormatStore#get(String, boolean)}, and
     * {@link PlayerFormatStore#getAllFormats()}), so
     * {@link TemplateMarkers#containsWidget(String)} can be checked
     * directly against each without first re-parsing anything. Short-circuits
     * on the first match found, cheapest formats first, since this is polled
     * repeatedly on bStats' own schedule rather than cached.
     */
    private boolean isWidgetItemInUse() {
        if (TemplateMarkers.containsWidget(configManager.getNametagFormat())
                || TemplateMarkers.containsWidget(configManager.getBedrockGlobalFormat())) {
            return true;
        }
        for (boolean bedrock : new boolean[] {false, true}) {
            for (String group : groupFormatStore.getGroupNames(bedrock)) {
                if (TemplateMarkers.containsWidget(groupFormatStore.get(group, bedrock))) {
                    return true;
                }
            }
        }
        for (String format : playerFormatStore.getAllFormats()) {
            if (TemplateMarkers.containsWidget(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Buckets a raw, as-configured nametag height-adjust value (never one
     * with a built-in constant already folded in — see
     * {@link ConfigManager#getBedrockHeightAdjustConfig()} and
     * {@link ConfigManager#getBedrockSneakHeightAdjustConfig()}) into one
     * of a fixed set of ranges for bStats reporting. A SimplePie chart is
     * meant for a handful of categories; reporting the raw double would
     * instead create a near-unique slice per server, so nearby values are
     * grouped together. The same {@link #HEIGHT_ADJUST_BUCKET_THRESHOLDS}
     * are mirrored on the negative side.
     */
    private static String bucketHeightAdjust(double value) {
        if (value == 0.0) {
            return "0";
        }
        boolean positive = value > 0;
        double abs = Math.abs(value);
        double lower = 0.0;
        for (double threshold : HEIGHT_ADJUST_BUCKET_THRESHOLDS) {
            if (abs <= threshold) {
                return positive
                        ? ">" + formatHeightAdjustBound(lower) + "&<=" + formatHeightAdjustBound(threshold)
                        : "<" + formatHeightAdjustBound(-lower) + "&>=" + formatHeightAdjustBound(-threshold);
            }
            lower = threshold;
        }
        double last = HEIGHT_ADJUST_BUCKET_THRESHOLDS[HEIGHT_ADJUST_BUCKET_THRESHOLDS.length - 1];
        return positive
                ? ">" + formatHeightAdjustBound(last)
                : "<" + formatHeightAdjustBound(-last);
    }

    /** {@code 0} prints as a bare "0"; every other bound prints to 2 decimal places. */
    private static String formatHeightAdjustBound(double value) {
        return value == 0.0 ? "0" : String.format(Locale.ROOT, "%.2f", value);
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
        if (playerFormatStore != null) {
            playerFormatStore.close();
        }
        if (playerWidgetFillStore != null) {
            playerWidgetFillStore.close();
        }
        if (nametagVisibilityStore != null) {
            nametagVisibilityStore.close();
        }
        try {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable t) {
            // PacketEvents never finished loading (e.g. the plugin failed to
            // enable earlier on) — nothing to terminate, and a shutdown path
            // must never throw.
            getLogger().warning("PacketEvents could not be terminated cleanly: " + t.getMessage());
        }
        getLogger().info("CustomPlayerNametags disabled.");
    }

    public static CustomPlayerNametags getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public GuiConfigManager getGuiConfigManager() {
        return guiConfigManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public PlayerFormatStore getPlayerFormatStore() {
        return playerFormatStore;
    }

    public PlayerWidgetFillStore getPlayerWidgetFillStore() {
        return playerWidgetFillStore;
    }

    public GroupFormatStore getGroupFormatStore() {
        return groupFormatStore;
    }

    public EditorPlaceholderStore getEditorPlaceholderStore() {
        return editorPlaceholderStore;
    }

    public NametagEditorManager getNametagEditorManager() {
        return nametagEditorManager;
    }

    public EditorRelayClient getEditorRelayClient() {
        return editorRelayClient;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}