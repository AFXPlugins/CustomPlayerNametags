package afx.customplayernametags.manager;

import afx.customplayernametags.CustomPlayerNametags;
import afx.customplayernametags.config.ConfigManager;
import afx.customplayernametags.config.EditorPlaceholderStore;
import afx.customplayernametags.config.GroupFormatStore;
import afx.customplayernametags.config.PlayerFormatStore;
import afx.customplayernametags.format.NametagFormatPermissions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Builds the single JSON "snapshot" object POSTed to the web editor relay
 * (see {@link EditorRelayClient}) when an admin runs
 * {@code /nametags editor web}. This is a point-in-time export of exactly
 * the data the web editor's {@code RelayBridge} needs to render the whole
 * UI and let the admin build a set of {@code /nametags format ...}
 * commands — it is never written back to; the plugin never talks to the
 * relay again for this session after handing it off.
 *
 * <p>The shape here is a contract with the web editor's
 * {@code src/21-relay.js} — keep both in sync if either changes:
 * <pre>
 * {
 *   "meta":     { "pluginVersion", "serverName", "createdAt" },
 *   "settings": { same fields as MockBridge's `settings`, see 20-bridge.js },
 *   "placeholders": [ { "key", "value", "title" } ],
 *   "players":  [ { "uuid", "name", "group", "hasFormat", "limit" } ],
 *   "groups":   { "java": [name...], "bedrock": [name...] },
 *   "formats":  { "global": { "java", "bedrock" },
 *                 "group":  { "java": {name: format}, "bedrock": {name: format} },
 *                 "player": { uuid: format } },
 *   "previews": { uuid: { "name", "values": {key: value}, "lineLimit" } }
 * }
 * </pre>
 *
 * <p>Must only be called from the main thread — it reads Bukkit/LuckPerms/
 * PlaceholderAPI state, none of which is safe off it. {@link EditorRelayClient}
 * is what actually sends the result, asynchronously.
 */
public final class EditorSessionBuilder {

    private EditorSessionBuilder() {
    }

    public static JsonObject build(CustomPlayerNametags plugin, ConfigManager config, NametagManager nametagManager,
                                    PlayerFormatStore playerFormatStore, GroupFormatStore groupFormatStore,
                                    EditorPlaceholderStore placeholderStore) {
        JsonObject root = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("pluginVersion", plugin.getDescription().getVersion());
        meta.addProperty("serverName", Bukkit.getServer().getName());
        meta.addProperty("createdAt", System.currentTimeMillis());
        root.add("meta", meta);

        root.add("settings", buildSettings(config));
        root.add("placeholders", buildPlaceholders(placeholderStore));

        JsonArray players = new JsonArray();
        JsonObject previews = new JsonObject();
        JsonObject playerFormats = new JsonObject();
        boolean placeholderApiAvailable = nametagManager.isPlaceholderApiAvailable();

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            String rawOverride = playerFormatStore.get(uuid);

            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("uuid", uuid.toString());
            playerJson.addProperty("name", online.getName());
            playerJson.addProperty("group", nametagManager.getPrimaryGroupName(online));
            playerJson.addProperty("hasFormat", rawOverride != null);
            players.add(playerJson);

            if (rawOverride != null) {
                playerFormats.addProperty(uuid.toString(), rawOverride);
            }

            previews.add(uuid.toString(), buildPreview(config, online, placeholderStore, placeholderApiAvailable));
        }
        root.add("players", players);
        root.add("previews", previews);

        JsonObject groups = new JsonObject();
        JsonArray javaGroups = new JsonArray();
        for (String name : groupFormatStore.getGroupNames(false)) {
            javaGroups.add(name);
        }
        JsonArray bedrockGroups = new JsonArray();
        for (String name : groupFormatStore.getGroupNames(true)) {
            bedrockGroups.add(name);
        }
        groups.add("java", javaGroups);
        groups.add("bedrock", bedrockGroups);
        root.add("groups", groups);

        JsonObject formats = new JsonObject();

        JsonObject global = new JsonObject();
        global.addProperty("java", nullToEmpty(nametagManager.getGlobalRawFormat()));
        global.addProperty("bedrock", nullToEmpty(nametagManager.getBedrockGlobalRawFormat()));
        formats.add("global", global);

        JsonObject groupFormatsJava = new JsonObject();
        for (String name : groupFormatStore.getGroupNames(false)) {
            groupFormatsJava.addProperty(name, nullToEmpty(groupFormatStore.get(name, false)));
        }
        JsonObject groupFormatsBedrock = new JsonObject();
        for (String name : groupFormatStore.getGroupNames(true)) {
            groupFormatsBedrock.addProperty(name, nullToEmpty(groupFormatStore.get(name, true)));
        }
        JsonObject group = new JsonObject();
        group.add("java", groupFormatsJava);
        group.add("bedrock", groupFormatsBedrock);
        formats.add("group", group);

        formats.add("player", playerFormats);
        root.add("formats", formats);

        return root;
    }

    private static JsonObject buildSettings(ConfigManager config) {
        JsonObject settings = new JsonObject();
        settings.addProperty("lineMaxCharacters", config.getNametagLineMaxCharacters());
        settings.addProperty("truncateIndicator", config.isTruncationEllipsisEnabled());
        settings.addProperty("widgetTruncateIndicator", config.isWidgetTruncationEllipsisEnabled());
        settings.addProperty("crouchEffect", config.getCrouchEffect().name());
        settings.addProperty("separateBedrockGlobal", config.isSeparateBedrockGlobalFormatEnabled());
        settings.addProperty("separateBedrockGroups", config.isSeparateBedrockGroupFormatsEnabled());
        settings.addProperty("bedrockPrefix", nullToEmpty(config.getBedrockNametagPrefix()));
        settings.addProperty("bedrockSuffix", nullToEmpty(config.getBedrockNametagSuffix()));
        settings.addProperty("affixGlobal", config.isBedrockAffixAppliedToGlobal());
        settings.addProperty("affixPlayer", config.isBedrockAffixAppliedToPlayer());
        settings.addProperty("affixGroup", config.isBedrockAffixAppliedToGroup());
        return settings;
    }

    private static JsonArray buildPlaceholders(EditorPlaceholderStore store) {
        JsonArray placeholders = new JsonArray();
        for (EditorPlaceholderStore.Placeholder placeholder : store.getAll()) {
            JsonObject json = new JsonObject();
            json.addProperty("key", placeholder.key());
            json.addProperty("value", placeholder.value());
            json.addProperty("title", placeholder.title());
            placeholders.add(json);
        }
        return placeholders;
    }

    /**
     * A frozen snapshot of what {@code online}'s preview values look like
     * right now — the same role {@code previewContext(uuid)} plays in
     * {@code MockBridge}, just computed once up front instead of on demand,
     * since the relay session has no way to ask the server anything further
     * after this snapshot is sent.
     */
    private static JsonObject buildPreview(ConfigManager config, Player online, EditorPlaceholderStore store,
                                            boolean placeholderApiAvailable) {
        JsonObject preview = new JsonObject();
        preview.addProperty("name", online.getName());
        JsonObject values = new JsonObject();
        for (EditorPlaceholderStore.Placeholder placeholder : store.getAll()) {
            String resolved = placeholder.value();
            if (placeholderApiAvailable) {
                try {
                    resolved = PlaceholderAPI.setPlaceholders(online, placeholder.value());
                } catch (Throwable t) {
                    // Same tolerance as NametagManager#applyPlaceholderApi — a
                    // misbehaving expansion shouldn't stop the rest of the
                    // snapshot (or the command) from completing.
                    resolved = "";
                }
            }
            values.addProperty(placeholder.key(), resolved);
        }
        preview.add("values", values);
        preview.addProperty("lineLimit", NametagFormatPermissions.effectiveLineCharacterLimit(config, online));
        return preview;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
