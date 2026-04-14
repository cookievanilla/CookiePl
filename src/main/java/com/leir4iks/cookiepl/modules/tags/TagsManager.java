package com.leir4iks.cookiepl.modules.tags;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class TagsManager {
    public static final String REQUIRED_GUILD_ID = "1213833397731074048";

    private final CookiePl plugin;
    private final File storageFile;

    private volatile Map<String, TagDefinition> definitions = Map.of();
    private volatile Map<String, List<String>> rolesByDiscordId = Map.of();
    private volatile String separator;
    private volatile String syncedAt = "";

    public TagsManager(CookiePl plugin) {
        this.plugin = plugin;
        String storageName = plugin.getConfig().getString("modules.tags.storage-file", "tags.yml");
        this.storageFile = new File(plugin.getDataFolder(), storageName);
        this.separator = plugin.getConfig().getString("modules.tags.separator", " ");
    }

    public synchronized void load() {
        if (!storageFile.exists()) {
            saveQuietly();
            return;
        }
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(storageFile);
            String loadedSeparator = cfg.getString("separator", plugin.getConfig().getString("modules.tags.separator", " "));
            ConfigurationSection definitionsSection = cfg.getConfigurationSection("definitions");
            ConfigurationSection membersSection = cfg.getConfigurationSection("members");
            HashMap<String, TagDefinition> newDefinitions = new HashMap<>();
            HashMap<String, List<String>> newRolesByDiscordId = new HashMap<>();

            if (definitionsSection != null) {
                for (String key : definitionsSection.getKeys(false)) {
                    String path = "definitions." + key + ".";
                    String name = cfg.getString(path + "name", key);
                    String discordRoleId = cfg.getString(path + "discord-role-id", "");
                    String symbol = cfg.getString(path + "symbol", "");
                    int order = cfg.getInt(path + "order", 0);
                    if (isBlank(key) || isBlank(symbol)) {
                        continue;
                    }
                    newDefinitions.put(key, new TagDefinition(key, name, discordRoleId, symbol, order));
                }
            }

            if (membersSection != null) {
                for (String discordId : membersSection.getKeys(false)) {
                    if (isBlank(discordId)) {
                        continue;
                    }
                    List<String> storedRoles = cfg.getStringList("members." + discordId);
                    ArrayList<String> normalized = normalizeRoles(storedRoles, newDefinitions);
                    if (!normalized.isEmpty()) {
                        newRolesByDiscordId.put(discordId, List.copyOf(normalized));
                    }
                }
            }

            this.separator = loadedSeparator == null ? " " : loadedSeparator;
            this.syncedAt = cfg.getString("synced-at", "");
            this.definitions = Map.copyOf(newDefinitions);
            this.rolesByDiscordId = Map.copyOf(newRolesByDiscordId);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to load tags storage: " + e.getMessage());
        }
    }

    public synchronized SyncResult applySync(String body) {
        JsonElement element;
        try {
            element = JsonParser.parseString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Body must be a JSON object");
        }
        JsonObject root = element.getAsJsonObject();
        String guildId = asString(root.get("guild_id"));
        if (!REQUIRED_GUILD_ID.equals(guildId)) {
            throw new IllegalArgumentException("Unsupported guild_id");
        }
        String mode = asString(root.get("mode"));
        if (!isBlank(mode) && !"replace".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Unsupported sync mode");
        }

        String newSeparator = asString(root.get("separator"));
        if (newSeparator == null) {
            newSeparator = plugin.getConfig().getString("modules.tags.separator", " ");
        }

        JsonElement definitionsElement = root.get("definitions");
        if (definitionsElement == null || !definitionsElement.isJsonArray()) {
            throw new IllegalArgumentException("definitions must be an array");
        }
        JsonElement membersElement = root.get("members");
        if (membersElement == null || !membersElement.isJsonArray()) {
            throw new IllegalArgumentException("members must be an array");
        }

        HashMap<String, TagDefinition> newDefinitions = new HashMap<>();
        for (JsonElement defElement : definitionsElement.getAsJsonArray()) {
            if (defElement == null || !defElement.isJsonObject()) {
                continue;
            }
            JsonObject defObj = defElement.getAsJsonObject();
            String key = asString(defObj.get("key"));
            String symbol = asString(defObj.get("symbol"));
            if (isBlank(key) || isBlank(symbol)) {
                continue;
            }
            String name = asString(defObj.get("name"));
            String discordRoleId = asString(defObj.get("discord_role_id"));
            int order = asInt(defObj.get("order"), 0);
            newDefinitions.put(key, new TagDefinition(key, isBlank(name) ? key : name, nz(discordRoleId), symbol, order));
        }

        HashMap<String, List<String>> newRolesByDiscordId = new HashMap<>();
        for (JsonElement memberElement : membersElement.getAsJsonArray()) {
            if (memberElement == null || !memberElement.isJsonObject()) {
                continue;
            }
            JsonObject memberObj = memberElement.getAsJsonObject();
            String discordId = asString(memberObj.get("discord_id"));
            if (isBlank(discordId)) {
                continue;
            }
            JsonElement rolesElement = memberObj.get("roles");
            if (rolesElement == null || !rolesElement.isJsonArray()) {
                continue;
            }
            ArrayList<String> roleKeys = new ArrayList<>();
            for (JsonElement roleElement : rolesElement.getAsJsonArray()) {
                String roleKey = asString(roleElement);
                if (!isBlank(roleKey)) {
                    roleKeys.add(roleKey);
                }
            }
            ArrayList<String> normalized = normalizeRoles(roleKeys, newDefinitions);
            if (!normalized.isEmpty()) {
                newRolesByDiscordId.put(discordId, List.copyOf(normalized));
            }
        }

        this.separator = newSeparator == null ? " " : newSeparator;
        this.syncedAt = nz(asString(root.get("synced_at")));
        this.definitions = Map.copyOf(newDefinitions);
        this.rolesByDiscordId = Map.copyOf(newRolesByDiscordId);
        saveQuietly();
        return new SyncResult(newDefinitions.size(), newRolesByDiscordId.size(), this.syncedAt);
    }

    public String getRenderedTagsForPlayer(Player player) {
        if (player == null) {
            return "";
        }
        DatabaseManager db = plugin.getWebDatabaseManager();
        if (db == null) {
            return "";
        }
        String discordId = db.getDiscordIdByMinecraftUuid(player.getUniqueId());
        return getRenderedTagsForDiscordId(discordId);
    }

    public String getRenderedTagsForDiscordId(String discordId) {
        List<TagDefinition> assigned = getAssignedDefinitions(discordId);
        if (assigned.isEmpty()) {
            return "";
        }
        ArrayList<String> symbols = new ArrayList<>(assigned.size());
        for (TagDefinition definition : assigned) {
            if (!isBlank(definition.symbol())) {
                symbols.add(definition.symbol());
            }
        }
        if (symbols.isEmpty()) {
            return "";
        }
        return String.join(separator == null ? " " : separator, symbols);
    }

    public JsonObject buildPlayerTagsJson(String discordId) {
        JsonObject out = new JsonObject();
        out.addProperty("guild_id", REQUIRED_GUILD_ID);
        out.addProperty("separator", separator == null ? " " : separator);
        out.addProperty("synced_at", nz(syncedAt));

        List<TagDefinition> assigned = getAssignedDefinitions(discordId);
        List<TagDefinition> configured = getConfiguredDefinitions();

        out.addProperty("count", assigned.size());
        out.addProperty("rendered", getRenderedTagsForDiscordId(discordId));
        out.add("roles", toJsonArray(assigned));
        out.add("configured_roles", toJsonArray(configured));
        return out;
    }

    private List<TagDefinition> getAssignedDefinitions(String discordId) {
        if (isBlank(discordId)) {
            return List.of();
        }
        List<String> keys = rolesByDiscordId.get(discordId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        ArrayList<TagDefinition> out = new ArrayList<>();
        Map<String, TagDefinition> currentDefinitions = definitions;
        for (String key : keys) {
            TagDefinition definition = currentDefinitions.get(key);
            if (definition != null) {
                out.add(definition);
            }
        }
        out.sort(this::compareDefinitions);
        return List.copyOf(out);
    }

    private List<TagDefinition> getConfiguredDefinitions() {
        ArrayList<TagDefinition> out = new ArrayList<>(definitions.values());
        out.sort(this::compareDefinitions);
        return List.copyOf(out);
    }

    private JsonArray toJsonArray(List<TagDefinition> definitionsList) {
        JsonArray array = new JsonArray();
        for (TagDefinition definition : definitionsList) {
            JsonObject role = new JsonObject();
            role.addProperty("key", definition.key());
            role.addProperty("name", definition.name());
            role.addProperty("discord_role_id", definition.discordRoleId());
            role.addProperty("symbol", definition.symbol());
            role.addProperty("order", definition.order());
            array.add(role);
        }
        return array;
    }

    private ArrayList<String> normalizeRoles(List<String> roles, Map<String, TagDefinition> defs) {
        if (roles == null || roles.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String role : roles) {
            String key = nz(role).trim();
            if (!isBlank(key) && defs.containsKey(key)) {
                unique.add(key);
            }
        }
        ArrayList<String> normalized = new ArrayList<>(unique);
        normalized.sort((left, right) -> compareDefinitions(defs.get(left), defs.get(right)));
        return normalized;
    }

    private int compareDefinitions(TagDefinition left, TagDefinition right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int orderCompare = Integer.compare(left.order(), right.order());
        if (orderCompare != 0) {
            return orderCompare;
        }
        return left.key().toLowerCase(Locale.ROOT).compareTo(right.key().toLowerCase(Locale.ROOT));
    }

    private synchronized void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            plugin.getLogManager().warn("Failed to save tags storage: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        if (!storageFile.getParentFile().exists() && !storageFile.getParentFile().mkdirs()) {
            throw new IOException("Failed to create tags storage directory");
        }
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("guild-id", REQUIRED_GUILD_ID);
        cfg.set("separator", separator == null ? " " : separator);
        cfg.set("synced-at", nz(syncedAt));
        cfg.set("definitions", null);
        cfg.set("members", null);

        for (TagDefinition definition : getConfiguredDefinitions()) {
            String path = "definitions." + definition.key() + ".";
            cfg.set(path + "name", definition.name());
            cfg.set(path + "discord-role-id", definition.discordRoleId());
            cfg.set(path + "symbol", definition.symbol());
            cfg.set(path + "order", definition.order());
        }

        ArrayList<String> discordIds = new ArrayList<>(rolesByDiscordId.keySet());
        Collections.sort(discordIds);
        for (String discordId : discordIds) {
            cfg.set("members." + discordId, rolesByDiscordId.getOrDefault(discordId, List.of()));
        }

        cfg.save(storageFile);
    }

    private String asString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().getAsString();
        }
        return element.toString();
    }

    private int asInt(JsonElement element, int fallback) {
        String value = asString(element);
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record TagDefinition(String key, String name, String discordRoleId, String symbol, int order) {}

    public record SyncResult(int definitionsCount, int membersCount, String syncedAt) {}
}
