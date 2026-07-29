package afx.customplayernametags.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Isolated in its own class so the {@code FloodgateApi} reference is only
 * classloaded when this class is actually touched. Callers do not need to
 * check whether "floodgate" is enabled themselves — {@link #isBedrockPlayer}
 * does that internally and simply returns {@code false} if it's absent.
 */
final class BedrockDetector {

    private BedrockDetector() {
    }

    static boolean isBedrockPlayer(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }
}
