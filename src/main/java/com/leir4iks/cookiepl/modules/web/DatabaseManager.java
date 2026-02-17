package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {

    private static final String SKIN_TEXTURE_PLACEHOLDER = "%skinsrestorer_texture_id_or_steve%";

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final File dataFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private final Map<String, String> externalCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinUrlCache = new ConcurrentHashMap<>();
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
        plugin.getFoliaLib().getScheduler().runAsync(task -> updateExternalData());
        this.updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateExternalData, 3600L, 3600L);
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

    private void updatePlayersCache() {
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

                    JsonObject playerData = createPlayerData(discordId, minecraftUuid, userCacheMap);
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
        updatePlayersCache();
        JsonArray rootArray = new JsonArray();
        for (JsonObject playerData : playersCache.values()) {
            rootArray.add(playerData);
        }
        return rootArray.toString();
    }

    public String getPlayerJsonById(String query) {
        updatePlayersCache();

        JsonObject direct = playersCache.get(query);
        if (direct != null) {
            return direct.toString();
        }

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (JsonObject playerData : playersCache.values()) {
            if (matchesQuery(playerData, normalizedQuery)) {
                return playerData.toString();
            }
        }

        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("error", "Player not found");
        return errorObj.toString();
    }

    private boolean matchesQuery(JsonObject playerData, String query) {
        if (query.isBlank()) {
            return false;
        }

        String id = playerData.has("id") ? playerData.get("id").getAsString() : "";
        String uuid = playerData.has("minecraft_uuid") ? playerData.get("minecraft_uuid").getAsString() : "";
        String name = playerData.has("minecraft_name") ? playerData.get("minecraft_name").getAsString() : "";

        return id.equalsIgnoreCase(query)
                || uuid.equalsIgnoreCase(query)
                || name.equalsIgnoreCase(query);
    }

    private JsonObject createPlayerData(String discordId, String minecraftUuid, Map<String, String> userCacheMap) {
        String minecraftName = "Unknown";

        if (externalCache.containsKey(discordId)) {
            minecraftName = externalCache.get(discordId);
        } else if (userCacheMap.containsKey(minecraftUuid)) {
            minecraftName = userCacheMap.get(minecraftUuid);
        }

        OfflinePlayer player = null;
        try {
            player = Bukkit.getOfflinePlayer(UUID.fromString(minecraftUuid));
        } catch (Exception ignored) {
        }

        String skinUrl = getSkinUrlForPlayer(player, minecraftUuid);

        JsonObject playerData = new JsonObject();
        playerData.addProperty("id", discordId);
        playerData.addProperty("minecraft_name", minecraftName);
        playerData.addProperty("minecraft_uuid", minecraftUuid);
        playerData.addProperty("skin_url", skinUrl);

        if (player != null) {
            playerData.addProperty("is_online", player.isOnline());
            playerData.add("stats", getPlayerStatistics(player));
        } else {
            playerData.addProperty("is_online", false);
            playerData.add("stats", getEmptyStats());
        }

        return playerData;
    }

    private JsonObject getPlayerStatistics(OfflinePlayer player) {
        try {
            boolean isOnline = player.isOnline();

            if (player.hasPlayedBefore() || isOnline) {
                JsonObject stats = new JsonObject();

                long playTimeTicks = getStatistic(player, Statistic.PLAY_ONE_MINUTE);
                double playTimeHours = playTimeTicks / 20.0 / 3600.0;
                stats.addProperty("play_time_hours", Math.round(playTimeHours * 10.0) / 10.0);
                stats.addProperty("joins", isOnline ? getStatistic(player, Statistic.LEAVE_GAME) + 1L : getStatistic(player, Statistic.LEAVE_GAME));
                stats.addProperty("deaths", getStatistic(player, Statistic.DEATHS));

                JsonObject kills = new JsonObject();
                kills.addProperty("players", getStatistic(player, Statistic.PLAYER_KILLS));
                kills.addProperty("mobs", getStatistic(player, Statistic.MOB_KILLS));
                stats.add("kills", kills);

                JsonObject damage = new JsonObject();
                damage.addProperty("dealt", getStatistic(player, Statistic.DAMAGE_DEALT));
                damage.addProperty("taken", getStatistic(player, Statistic.DAMAGE_TAKEN));
                stats.add("damage", damage);

                long walkCm = getStatistic(player, Statistic.WALK_ONE_CM);
                long flyCm = getStatistic(player, Statistic.AVIATE_ONE_CM);
                long swimCm = getStatistic(player, Statistic.SWIM_ONE_CM);
                long totalCm = walkCm
                        + flyCm
                        + swimCm
                        + getStatistic(player, Statistic.SPRINT_ONE_CM)
                        + getStatistic(player, Statistic.CROUCH_ONE_CM)
                        + getStatistic(player, Statistic.CLIMB_ONE_CM)
                        + getStatistic(player, Statistic.FALL_ONE_CM)
                        + getStatistic(player, Statistic.MINECART_ONE_CM)
                        + getStatistic(player, Statistic.BOAT_ONE_CM)
                        + getStatistic(player, Statistic.PIG_ONE_CM)
                        + getStatistic(player, Statistic.HORSE_ONE_CM)
                        + getStatistic(player, Statistic.STRIDER_ONE_CM);

                JsonObject distance = new JsonObject();
                distance.addProperty("total_km", toKilometers(totalCm));
                distance.addProperty("walk_km", toKilometers(walkCm));
                distance.addProperty("fly_km", toKilometers(flyCm));
                distance.addProperty("swim_km", toKilometers(swimCm));
                stats.add("distance", distance);

                long minedTotal = 0;
                long usedTotal = 0;
                long craftedTotal = 0;
                long pickedUpTotal = 0;
                long droppedTotal = 0;

                List<Map.Entry<String, Long>> topMined = new ArrayList<>();
                List<Map.Entry<String, Long>> topUsed = new ArrayList<>();

                for (Material material : Material.values()) {
                    if (material.isLegacy()) {
                        continue;
                    }

                    if (material.isBlock()) {
                        long mined = getStatistic(player, Statistic.MINE_BLOCK, material);
                        minedTotal += mined;
                        if (mined > 0) {
                            topMined.add(Map.entry(material.name().toLowerCase(Locale.ROOT), mined));
                        }
                    }

                    long used = getStatistic(player, Statistic.USE_ITEM, material);
                    usedTotal += used;
                    if (used > 0) {
                        topUsed.add(Map.entry(material.name().toLowerCase(Locale.ROOT), used));
                    }

                    craftedTotal += getStatistic(player, Statistic.CRAFT_ITEM, material);
                    pickedUpTotal += getStatistic(player, Statistic.PICKUP, material);
                    droppedTotal += getStatistic(player, Statistic.DROP, material);
                }

                JsonObject blocks = new JsonObject();
                blocks.addProperty("mined_total", minedTotal);
                stats.add("blocks", blocks);

                JsonObject items = new JsonObject();
                items.addProperty("used_total", usedTotal);
                items.addProperty("crafted_total", craftedTotal);
                items.addProperty("picked_up_total", pickedUpTotal);
                items.addProperty("dropped_total", droppedTotal);
                stats.add("items", items);

                JsonObject fun = new JsonObject();
                fun.addProperty("jumps", getStatistic(player, Statistic.JUMP));
                fun.addProperty("animals_bred", getStatistic(player, Statistic.ANIMALS_BRED));
                fun.addProperty("fish_caught", getStatistic(player, Statistic.FISH_CAUGHT));
                fun.addProperty("villager_trades", getStatistic(player, Statistic.TRADED_WITH_VILLAGER));
                fun.addProperty("enchantments", getStatistic(player, Statistic.ITEM_ENCHANTED));
                stats.add("fun", fun);

                topMined.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                topUsed.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

                JsonObject top = new JsonObject();
                top.add("mined", toTopArray(topMined));
                top.add("used", toTopArray(topUsed));
                stats.add("top", top);

                stats.addProperty("favorite_mined", topMined.isEmpty() ? "" : topMined.get(0).getKey());
                stats.addProperty("favorite_used", topUsed.isEmpty() ? "" : topUsed.get(0).getKey());

                return stats;
            }
        } catch (Exception ignored) {
        }

        return getEmptyStats();
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

    private String getSkinUrlForPlayer(OfflinePlayer player, String minecraftUuid) {
        String cacheKey = minecraftUuid == null ? "" : minecraftUuid;
        String cached = skinUrlCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String textureId = resolveTextureIdFromPlaceholder(player);
        if (textureId == null || textureId.isBlank()) {
            return "";
        }

        String skinUrl = "https://mc-heads.net/avatar/" + textureId + ".png";
        skinUrlCache.put(cacheKey, skinUrl);
        return skinUrl;
    }

    private String resolveTextureIdFromPlaceholder(OfflinePlayer player) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return null;
        }

        try {
            Class<?> placeholderApiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object value = placeholderApiClass
                    .getMethod("setPlaceholders", OfflinePlayer.class, String.class)
                    .invoke(null, player, SKIN_TEXTURE_PLACEHOLDER);

            if (value == null) {
                return null;
            }

            String textureId = String.valueOf(value).trim();
            if (textureId.isEmpty() || textureId.equalsIgnoreCase("Error") || textureId.equalsIgnoreCase(SKIN_TEXTURE_PLACEHOLDER)) {
                return null;
            }
            return textureId;
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to parse skin texture placeholder for " + player.getUniqueId() + ": " + e.getMessage());
            return null;
        }
    }

    private int getStatistic(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int getStatistic(OfflinePlayer player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double toKilometers(long centimeters) {
        double kilometers = centimeters / 100000.0;
        return Math.round(kilometers * 100.0) / 100.0;
    }

    private JsonArray toTopArray(List<Map.Entry<String, Long>> entries) {
        JsonArray array = new JsonArray();
        int limit = Math.min(entries.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("material", entry.getKey());
            obj.addProperty("count", entry.getValue());
            array.add(obj);
        }
        return array;
    }

    private Map<String, String> loadUserCache() {
        Map<String, String> map = new HashMap<>();
        if (userCacheFile.exists()) {
            try (FileReader reader = new FileReader(userCacheFile)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
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
