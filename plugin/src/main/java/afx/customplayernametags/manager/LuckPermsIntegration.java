package afx.customplayernametags.manager;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * Isolated in its own class — mirroring {@link BedrockDetector} — so the
 * {@code net.luckperms.api} classes are only classloaded when this class is
 * actually touched. Callers do not need to check whether the "LuckPerms"
 * plugin is enabled themselves; every method here does that internally and
 * simply returns an empty/false result if it's absent, rather than throwing
 * {@link NoClassDefFoundError}. LuckPerms is a soft dependency: group
 * nametag formats (feature) simply never apply if it isn't installed, and
 * every other format tier (individual, Bedrock global, global) is
 * completely unaffected.
 */
final class LuckPermsIntegration {

    private LuckPermsIntegration() {
    }

    /** Whether the LuckPerms plugin is present and enabled. */
    static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    /**
     * The group names {@code player} currently belongs to (including
     * inherited groups), ordered from highest LuckPerms weight to lowest.
     * A group with no configured weight is treated as weight {@code 0}.
     * Returns an empty list if LuckPerms isn't installed, the player has no
     * cached LuckPerms user data yet, or lookup otherwise fails — callers
     * fall through to the next-lower format tier in that case.
     */
    static List<String> getGroupsByWeightDesc(Player player) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                return Collections.emptyList();
            }
            Collection<Group> groups = user.getInheritedGroups(user.getQueryOptions());
            List<Group> sorted = new ArrayList<>(groups);
            sorted.sort((a, b) -> Integer.compare(weightOf(b), weightOf(a)));

            List<String> names = new ArrayList<>(sorted.size());
            for (Group group : sorted) {
                names.add(group.getName());
            }
            return names;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Every group name currently defined on the server (regardless of
     * whether any online player belongs to it), for tab completion of
     * {@code /nametags format groups <create|edit> <group>}. Returns an
     * empty list if LuckPerms isn't installed.
     */
    static List<String> getAllGroupNames() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            Collection<Group> groups = api.getGroupManager().getLoadedGroups();
            List<String> names = new ArrayList<>(groups.size());
            for (Group group : groups) {
                names.add(group.getName());
            }
            return names;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static int weightOf(Group group) {
        OptionalInt weight = group.getWeight();
        return weight.isPresent() ? weight.getAsInt() : 0;
    }
}
