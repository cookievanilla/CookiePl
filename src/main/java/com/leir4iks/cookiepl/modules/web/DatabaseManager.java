package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final File dataFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private final Map<String, String> externalCache = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> playersCache = new ConcurrentHashMap<>();
    private WrappedTask updateTask;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void start() {
        loadDataYml();

        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            updatePlayersCache(false);
            updateExternalData();
            updatePlayersCache(true);
        });

        this.updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(() -> {
            updateExternalData();
        }, 3600L, 3600L);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
        }
    }

    private void loadDataYml() {
        if (!dataFile.exists()) return;
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
            for (String key : config.getKeys(false)) {
                externalCache.put(key, config.getString(key));
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to load data.yml: " + e.getMessage());
        }
    }

    private void updateExternalData() {
        try {
            URL url = new URL(externalDatabaseUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == 200) {
                try (Scanner scanner = new Scanner(connection.getInputStream())) {
                    boolean changed = false;
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        if (line.trim().isEmpty()) continue;

                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            String discordId = parts[0];
                            String nick = parts[1];

                            if (!nick.equals(externalCache.get(discordId))) {
                                externalCache.put(discordId, nick);
                                changed = true;
                            }
                        }
                    }

                    if (changed) {
                        saveDataYml();
                    }
                }
            } else {
                plugin.getLogManager().warn("External database returned: " + connection.getResponseCode());
            }
            connection.disconnect();
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update external database: " + e.getMessage());
        }
    }

    private void saveDataYml() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, String> entry : externalCache.entrySet()) {
                config.set(entry.getKey(), entry.getValue());
            }
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    private void updatePlayersCache(boolean includeStats) {
        Map<String, String> userCacheMap = loadUserCache();
        File accountsFile = new File(discordSrvFolder, "accounts.aof");

        if (!accountsFile.exists()) {
            playersCache.clear();
            return;
        }

        Map<String, JsonObject> newCache = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(accountsFile.toPath());
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    String discordId = parts[0];
                    String minecraftUuid = parts[1];

                    JsonObject playerData = createPlayerData(discordId, minecraftUuid, userCacheMap, includeStats);
                    if (playerData != null) {
                        newCache.put(discordId, playerData);
                    }
                }
            }
            playersCache.clear();
            playersCache.putAll(newCache);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
        }
    }

    public String getDatabaseJson() {
        if (playersCache.isEmpty()) {
            updatePlayersCache(false);
        }

        JsonArray rootArray = new JsonArray();
        for (JsonObject playerData : playersCache.values()) {
            rootArray.add(playerData);
        }
        return rootArray.toString();
    }

    public String getPlayerJsonById(String discordId) {
        if (playersCache.isEmpty()) {
            updatePlayersCache(false);
        }

        Map<String, String> userCacheMap = loadUserCache();

        if (playersCache.containsKey(discordId)) {
            JsonObject cachedPlayer = playersCache.get(discordId);
            String minecraftUuid = cachedPlayer.has("minecraft_uuid") ? cachedPlayer.get("minecraft_uuid").getAsString() : "";
            JsonObject enriched = createPlayerData(discordId, minecraftUuid, userCacheMap, true);
            playersCache.put(discordId, enriched);
            return enriched.toString();
        }

        for (Map.Entry<String, JsonObject> entry : playersCache.entrySet()) {
            JsonObject playerData = entry.getValue();
            String minecraftName = playerData.has("minecraft_name") ? playerData.get("minecraft_name").getAsString() : "";
            String minecraftUuid = playerData.has("minecraft_uuid") ? playerData.get("minecraft_uuid").getAsString() : "";
            if (discordId.equalsIgnoreCase(minecraftName) || discordId.equalsIgnoreCase(minecraftUuid)) {
                JsonObject enriched = createPlayerData(entry.getKey(), minecraftUuid, userCacheMap, true);
                playersCache.put(entry.getKey(), enriched);
                return enriched.toString();
            }
        }

        File accountsFile = new File(discordSrvFolder, "accounts.aof");

        if (accountsFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(accountsFile.toPath());
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[0].equals(discordId)) {
                        JsonObject playerData = createPlayerData(parts[0], parts[1], userCacheMap, true);
                        if (playerData != null) {
                            playersCache.put(discordId, playerData);
                            return playerData.toString();
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
            }
        }

        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("error", "Player not found");
        return errorObj.toString();
    }

    private JsonObject createPlayerData(String discordId, String minecraftUuid, Map<String, String> userCacheMap, boolean includeStats) {
        String minecraftName = "Unknown";

        if (externalCache.containsKey(discordId)) {
            minecraftName = externalCache.get(discordId);
        } else if (userCacheMap.containsKey(minecraftUuid)) {
            minecraftName = userCacheMap.get(minecraftUuid);
        }

        String skinUrl = getSkinUrlForPlayer(minecraftName, minecraftUuid);

        JsonObject playerData = new JsonObject();
        playerData.addProperty("id", discordId);
        playerData.addProperty("minecraft_name", minecraftName);
        playerData.addProperty("minecraft_uuid", minecraftUuid);
        playerData.addProperty("skin_url", skinUrl);

        UUID uuid;
        try {
            uuid = UUID.fromString(minecraftUuid);
        } catch (Exception e) {
            playerData.addProperty("is_online", false);
            playerData.add("stats", getEmptyStats());
            return playerData;
        }

        playerData.addProperty("is_online", Bukkit.getPlayer(uuid) != null);
        playerData.add("stats", includeStats ? getPlayerStatistics(minecraftUuid) : getEmptyStats());
        return playerData;
    }

    private JsonObject getPlayerStatistics(String minecraftUuid) {
        File statsFile = findStatsFile(minecraftUuid);
        if (statsFile == null || !statsFile.exists()) {
            return getEmptyStats();
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(statsFile.toPath())).getAsJsonObject();
            JsonObject statsRoot = root.has("stats") && root.get("stats").isJsonObject()
                    ? root.getAsJsonObject("stats") : new JsonObject();
            JsonObject custom = getStatCategory(statsRoot, "minecraft:custom");
            JsonObject mined = getStatCategory(statsRoot, "minecraft:mined");
            JsonObject used = getStatCategory(statsRoot, "minecraft:used");
            JsonObject crafted = getStatCategory(statsRoot, "minecraft:crafted");
            JsonObject pickedUp = getStatCategory(statsRoot, "minecraft:picked_up");
            JsonObject dropped = getStatCategory(statsRoot, "minecraft:dropped");

            JsonObject stats = new JsonObject();
            stats.addProperty("play_time_hours", roundOne(getLong(custom, "minecraft:play_time") / 20.0 / 3600.0));
            stats.addProperty("joins", getLong(custom, "minecraft:leave_game"));
            stats.addProperty("deaths", getLong(custom, "minecraft:deaths"));

            JsonObject kills = new JsonObject();
            kills.addProperty("players", getLong(custom, "minecraft:player_kills"));
            kills.addProperty("mobs", getLong(custom, "minecraft:mob_kills"));
            stats.add("kills", kills);

            JsonObject damage = new JsonObject();
            damage.addProperty("dealt", getLong(custom, "minecraft:damage_dealt"));
            damage.addProperty("taken", getLong(custom, "minecraft:damage_taken"));
            stats.add("damage", damage);

            long walkCm = getLong(custom, "minecraft:walk_one_cm")
                    + getLong(custom, "minecraft:sprint_one_cm")
                    + getLong(custom, "minecraft:crouch_one_cm")
                    + getLong(custom, "minecraft:climb_one_cm");
            long flyCm = getLong(custom, "minecraft:aviate_one_cm") + getLong(custom, "minecraft:fly_one_cm");
            long swimCm = getLong(custom, "minecraft:swim_one_cm");
            long totalCm = walkCm + flyCm + swimCm
                    + getLong(custom, "minecraft:boat_one_cm")
                    + getLong(custom, "minecraft:minecart_one_cm")
                    + getLong(custom, "minecraft:horse_one_cm")
                    + getLong(custom, "minecraft:pig_one_cm")
                    + getLong(custom, "minecraft:strider_one_cm");

            JsonObject distance = new JsonObject();
            distance.addProperty("total_km", roundOne(cmToKm(totalCm)));
            distance.addProperty("walk_km", roundOne(cmToKm(walkCm)));
            distance.addProperty("fly_km", roundOne(cmToKm(flyCm)));
            distance.addProperty("swim_km", roundOne(cmToKm(swimCm)));
            stats.add("distance", distance);

            JsonObject blocks = new JsonObject();
            blocks.addProperty("mined_total", sumCategory(mined));
            stats.add("blocks", blocks);

            JsonObject items = new JsonObject();
            items.addProperty("used_total", sumCategory(used));
            items.addProperty("crafted_total", sumCategory(crafted));
            items.addProperty("picked_up_total", sumCategory(pickedUp));
            items.addProperty("dropped_total", sumCategory(dropped));
            stats.add("items", items);

            JsonObject fun = new JsonObject();
            fun.addProperty("jumps", getLong(custom, "minecraft:jump"));
            fun.addProperty("animals_bred", getLong(custom, "minecraft:animals_bred"));
            fun.addProperty("fish_caught", getLong(custom, "minecraft:fish_caught"));
            fun.addProperty("villager_trades", getLong(custom, "minecraft:traded_with_villager"));
            fun.addProperty("enchantments", getLong(custom, "minecraft:item_enchanted"));
            stats.add("fun", fun);

            JsonArray topMined = getTopStatKeys(mined);
            JsonArray topUsed = getTopStatKeys(used);
            JsonObject top = new JsonObject();
            top.add("mined", topMined);
            top.add("used", topUsed);
            stats.add("top", top);

            stats.addProperty("favorite_mined", getFavoriteMaterialName(topMined));
            stats.addProperty("favorite_used", getFavoriteMaterialName(topUsed));
            return stats;
        } catch (Exception e) {
            return getEmptyStats();
        }
    }

    private JsonObject getEmptyStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("play_time_hours", 0);
        stats.addProperty("joins", 0);
        stats.addProperty("deaths", 0);

        JsonObject kills = new JsonObject();
        kills.addProperty("players", 0);
        kills.addProperty("mobs", 0);
        stats.add("kills", kills);

        JsonObject damage = new JsonObject();
        damage.addProperty("dealt", 0);
        damage.addProperty("taken", 0);
        stats.add("damage", damage);

        JsonObject distance = new JsonObject();
        distance.addProperty("total_km", 0);
        distance.addProperty("walk_km", 0);
        distance.addProperty("fly_km", 0);
        distance.addProperty("swim_km", 0);
        stats.add("distance", distance);

        JsonObject blocks = new JsonObject();
        blocks.addProperty("mined_total", 0);
        stats.add("blocks", blocks);

        JsonObject items = new JsonObject();
        items.addProperty("used_total", 0);
        items.addProperty("crafted_total", 0);
        items.addProperty("picked_up_total", 0);
        items.addProperty("dropped_total", 0);
        stats.add("items", items);

        JsonObject fun = new JsonObject();
        fun.addProperty("jumps", 0);
        fun.addProperty("animals_bred", 0);
        fun.addProperty("fish_caught", 0);
        fun.addProperty("villager_trades", 0);
        fun.addProperty("enchantments", 0);
        stats.add("fun", fun);

        JsonObject top = new JsonObject();
        top.add("mined", new JsonArray());
        top.add("used", new JsonArray());
        stats.add("top", top);

        stats.addProperty("favorite_mined", "");
        stats.addProperty("favorite_used", "");

        return stats;
    }

    private String getSkinUrlForPlayer(String playerName, String minecraftUuid) {
        String placeholder = "%skinsrestorer_texture_id_or_steve%";
        String textureId = placeholder;

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                UUID uuid = UUID.fromString(minecraftUuid);
                org.bukkit.entity.Player onlinePlayer = Bukkit.getPlayer(uuid);
                if (onlinePlayer != null) {
                    textureId = PlaceholderAPI.setPlaceholders(onlinePlayer, placeholder);
                } else {
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                    textureId = PlaceholderAPI.setPlaceholders(offlinePlayer, placeholder);
                }
            } catch (Exception ignored) {
            }
        }

        if (textureId == null || textureId.isBlank() || textureId.contains("%")) {
            textureId = "6d3b06c38504ffc0229b9492147c69fcf59fd2ed7885f78502152f77b4d50de1";
        }

        return "https://mc-heads.net/avatar/" + textureId + ".png";
    }

    private String getFavoriteMaterialName(JsonArray topMaterials) {
        if (topMaterials.isEmpty()) {
            return "";
        }

        JsonObject favorite = topMaterials.get(0).getAsJsonObject();
        return favorite.has("material") ? favorite.get("material").getAsString() : "";
    }

    private double roundOne(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double cmToKm(long centimeters) {
        return centimeters / 100000.0;
    }


    private JsonObject getStatCategory(JsonObject statsRoot, String key) {
        if (statsRoot.has(key) && statsRoot.get(key).isJsonObject()) {
            return statsRoot.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private long getLong(JsonObject object, String key) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                return object.get(key).getAsLong();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private long sumCategory(JsonObject category) {
        long total = 0;
        for (Map.Entry<String, JsonElement> entry : category.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                try {
                    total += entry.getValue().getAsLong();
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    private JsonArray getTopStatKeys(JsonObject category) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : category.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                try {
                    entries.add(Map.entry(stripNamespace(entry.getKey()), entry.getValue().getAsLong()));
                } catch (Exception ignored) {
                }
            }
        }

        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JsonArray result = new JsonArray();
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("material", entries.get(i).getKey());
            item.addProperty("count", entries.get(i).getValue());
            result.add(item);
        }
        return result;
    }

    private String stripNamespace(String key) {
        int idx = key.indexOf(':');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private File findStatsFile(String minecraftUuid) {
        File worldContainer = plugin.getServer().getWorldContainer();
        File[] worlds = worldContainer.listFiles(File::isDirectory);
        if (worlds == null) {
            return null;
        }

        String uuidFile = minecraftUuid + ".json";
        for (File worldDir : worlds) {
            File statsDir = new File(worldDir, "stats");
            File statsFile = new File(statsDir, uuidFile);
            if (statsFile.exists()) {
                return statsFile;
            }
        }
        return null;
    }

    private Map<String, String> loadUserCache() {
        Map<String, String> map = new HashMap<>();
        if (userCacheFile.exists()) {
            try (FileReader reader = new FileReader(userCacheFile)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element.isJsonArray()) {
                    com.google.gson.JsonArray array = element.getAsJsonArray();
                    for (JsonElement entry : array) {
                        if (entry.isJsonObject()) {
                            JsonObject obj = entry.getAsJsonObject();
                            if (obj.has("uuid") && obj.has("name")) {
                                String uuid = obj.get("uuid").getAsString();
                                String name = obj.get("name").getAsString();
                                map.put(uuid, name);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogManager().warn("Failed to read usercache.json: " + e.getMessage());
            }
        }
        return map;
    }
}
