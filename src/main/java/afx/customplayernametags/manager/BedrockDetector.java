package afx.customplayernametags.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated in its own class so the {@code FloodgateApi} reference is only
 * classloaded when this class is actually touched. Callers do not need to
 * check whether "floodgate" is enabled themselves — {@link #isBedrockPlayer}
 * does that internally and simply returns {@code false} if it's absent.
 *
 * <h2>Per-session result cache</h2>
 * <p>{@link #isBedrockPlayer} is called from some of the hottest paths in
 * the plugin — once per online viewer, per owner, every time visibility is
 * recalculated ({@code NametagDisplayManager#applyViewerVisibility}), and
 * again every {@code SEE_THROUGH_REFRESH_INTERVAL_TICKS} for every standing
 * owner's {@code anyJavaViewerOccluded} scan. On a populated server that
 * adds up to an O(players^2) number of calls every few ticks, and each
 * uncached call previously paid for a {@code Bukkit.getPluginManager()}
 * lookup plus a Floodgate API call.
 *
 * <p>Whether a given online player is a Bedrock/Geyser (Floodgate) player is
 * fixed for the entire length of their session — it's decided once at the
 * network handshake, long before any Bukkit event fires, and cannot change
 * while they're connected. That makes the result trivially safe to cache
 * per {@link UUID} and only ever needs to be computed once per join,
 * eliminating the repeated lookups without changing behavior at all. The
 * cache entry is cleared on quit (see {@link #forget(UUID)}) so it can
 * never leak or go stale across a reconnect with a different client.
 */
final class BedrockDetector {

    /** UUID -> whether that (currently or formerly online) player is a Floodgate/Bedrock player. */
    private static final Map<UUID, Boolean> CACHE = new ConcurrentHashMap<>();

    private BedrockDetector() {
    }

    static boolean isBedrockPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        Boolean cached = CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        boolean result = computeIsBedrockPlayer(player);
        CACHE.put(uuid, result);
        return result;
    }

    private static boolean computeIsBedrockPlayer(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Clears the cached result for {@code uuid}. Must be called on player
     * quit so a future rejoin (potentially from a different client type)
     * is recomputed instead of reusing a stale cached value.
     */
    static void forget(UUID uuid) {
        CACHE.remove(uuid);
    }

    /**
     * Clears every cached result. Called on plugin shutdown purely so the
     * cache doesn't hold references across a {@code /reload} of the server
     * itself; not required for correctness during normal operation since
     * entries are already removed per-player on quit.
     */
    static void clearAll() {
        CACHE.clear();
    }
}