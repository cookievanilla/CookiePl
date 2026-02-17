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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File skinsRestorerFolder;
    private final File userCacheFile;
    private final File dataFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private final Map<String, String> externalCache = new ConcurrentHashMap<>();
    private static final String STEVE_TEXTURE_FALLBACK = "6d3b06c38504ffc0229b9492147c69fcf59fd2ed7885f78502152f77b4d50de1";

    private final Map<String, String> skinUrlCache = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> playersCache = new ConcurrentHashMap<>();
    private WrappedTask updateTask;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.skinsRestorerFolder = new File(plugin.getDataFolder().getParentFile(), "SkinsRestorer/skins");
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

        if (playersCache.containsKey(discordId)) {
            return playersCache.get(discordId).toString();
        }

        for (JsonObject playerData : playersCache.values()) {
            String minecraftName = playerData.has("minecraft_name") ? playerData.get("minecraft_name").getAsString() : "";
            String minecraftUuid = playerData.has("minecraft_uuid") ? playerData.get("minecraft_uuid").getAsString() : "";
            if (discordId.equalsIgnoreCase(minecraftName) || discordId.equalsIgnoreCase(minecraftUuid)) {
                return playerData.toString();
            }
        }

        Map<String, String> userCacheMap = loadUserCache();
        File accountsFile = new File(discordSrvFolder, "accounts.aof");

        if (accountsFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(accountsFile.toPath());
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[0].equals(discordId)) {
                        JsonObject playerData = createPlayerData(parts[0], parts[1], userCacheMap, false);
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

        if (includeStats) {
            OfflinePlayer player = null;
            try {
                player = Bukkit.getOfflinePlayer(UUID.fromString(minecraftUuid));
            } catch (Exception ignored) {}

            if (player != null) {
                playerData.addProperty("is_online", player.isOnline());
                playerData.add("stats", getPlayerStatistics(player));
            } else {
                playerData.addProperty("is_online", false);
                playerData.add("stats", getEmptyStats());
            }
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
                Map<Material, Integer> minedPerMaterial = new HashMap<>();
                Map<Material, Integer> usedPerMaterial = new HashMap<>();
                long blocksMinedTotal = 0;
                long itemsUsedTotal = 0;
                long itemsCraftedTotal = 0;
                long itemsPickedUpTotal = 0;
                long itemsDroppedTotal = 0;

                for (Material m : Material.values()) {
                    if (m.isLegacy()) {
                        continue;
                    }

                    int mined = getMaterialStatistic(player, Statistic.MINE_BLOCK, m);
                    int used = getMaterialStatistic(player, Statistic.USE_ITEM, m);
                    int crafted = getMaterialStatistic(player, Statistic.CRAFT_ITEM, m);
                    int pickedUp = getMaterialStatistic(player, Statistic.PICKUP, m);
                    int dropped = getMaterialStatistic(player, Statistic.DROP, m);

                    if (m.isBlock() && mined > 0) {
                        minedPerMaterial.put(m, mined);
                        blocksMinedTotal += mined;
                    }

                    if (used > 0) {
                        usedPerMaterial.put(m, used);
                        itemsUsedTotal += used;
                    }

                    itemsCraftedTotal += crafted;
                    itemsPickedUpTotal += pickedUp;
                    itemsDroppedTotal += dropped;
                }

                long playTimeTicks = 0;
                try {
                    playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
                } catch (Exception ignored) {}

                double playTimeHours = playTimeTicks / 20.0 / 3600.0;
                stats.addProperty("play_time_hours", Math.round(playTimeHours * 10.0) / 10.0);

                long deaths = 0;
                try { deaths = player.getStatistic(Statistic.DEATHS); } catch (Exception ignored) {}
                long playerKills = 0;
                try { playerKills = player.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception ignored) {}

                long mobKills = 0;
                try { mobKills = player.getStatistic(Statistic.MOB_KILLS); } catch (Exception ignored) {}

                int leaves = 0;
                try { leaves = player.getStatistic(Statistic.LEAVE_GAME); } catch (Exception ignored) {}

                int joins = isOnline ? leaves + 1 : leaves;
                stats.addProperty("joins", joins);

                stats.addProperty("deaths", deaths);

                JsonObject kills = new JsonObject();
                kills.addProperty("players", playerKills);
                kills.addProperty("mobs", mobKills);
                stats.add("kills", kills);

                JsonObject damage = new JsonObject();
                damage.addProperty("dealt", getSimpleStatistic(player, Statistic.DAMAGE_DEALT));
                damage.addProperty("taken", getSimpleStatistic(player, Statistic.DAMAGE_TAKEN));
                stats.add("damage", damage);

                JsonObject distance = new JsonObject();
                long walkCm = getSimpleStatistic(player, Statistic.WALK_ONE_CM)
                        + getSimpleStatistic(player, Statistic.SPRINT_ONE_CM)
                        + getSimpleStatistic(player, Statistic.CROUCH_ONE_CM)
                        + getSimpleStatistic(player, Statistic.CLIMB_ONE_CM);
                long flyCm = getSimpleStatistic(player, Statistic.AVIATE_ONE_CM)
                        + getSimpleStatistic(player, Statistic.FLY_ONE_CM);
                long swimCm = getSimpleStatistic(player, Statistic.SWIM_ONE_CM);
                long totalCm = walkCm + flyCm + swimCm
                        + getSimpleStatistic(player, Statistic.BOAT_ONE_CM)
                        + getSimpleStatistic(player, Statistic.MINECART_ONE_CM)
                        + getSimpleStatistic(player, Statistic.HORSE_ONE_CM)
                        + getSimpleStatistic(player, Statistic.PIG_ONE_CM)
                        + getSimpleStatistic(player, Statistic.STRIDER_ONE_CM);
                distance.addProperty("total_km", roundOne(cmToKm(totalCm)));
                distance.addProperty("walk_km", roundOne(cmToKm(walkCm)));
                distance.addProperty("fly_km", roundOne(cmToKm(flyCm)));
                distance.addProperty("swim_km", roundOne(cmToKm(swimCm)));
                stats.add("distance", distance);

                JsonObject blocks = new JsonObject();
                blocks.addProperty("mined_total", blocksMinedTotal);
                stats.add("blocks", blocks);

                JsonObject items = new JsonObject();
                items.addProperty("used_total", itemsUsedTotal);
                items.addProperty("crafted_total", itemsCraftedTotal);
                items.addProperty("picked_up_total", itemsPickedUpTotal);
                items.addProperty("dropped_total", itemsDroppedTotal);
                stats.add("items", items);

                JsonObject fun = new JsonObject();
                fun.addProperty("jumps", getSimpleStatistic(player, Statistic.JUMP));
                fun.addProperty("animals_bred", getSimpleStatistic(player, Statistic.ANIMALS_BRED));
                fun.addProperty("fish_caught", getSimpleStatistic(player, Statistic.FISH_CAUGHT));
                fun.addProperty("villager_trades", getSimpleStatistic(player, Statistic.TRADED_WITH_VILLAGER));
                fun.addProperty("enchantments", getSimpleStatistic(player, Statistic.ITEM_ENCHANTED));
                stats.add("fun", fun);

                JsonObject top = new JsonObject();
                JsonArray topMined = getTopMaterials(minedPerMaterial);
                JsonArray topUsed = getTopMaterials(usedPerMaterial);
                top.add("mined", topMined);
                top.add("used", topUsed);
                stats.add("top", top);

                stats.addProperty("favorite_mined", getFavoriteMaterialName(topMined));
                stats.addProperty("favorite_used", getFavoriteMaterialName(topUsed));

                return stats;
            }
        } catch (Exception ignored) {}

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

    private String getSkinUrlForPlayer(String playerName, String minecraftUuid) {
        String cacheKey = minecraftUuid == null ? String.valueOf(playerName) : minecraftUuid;
        String cached = skinUrlCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String textureId = findCustomSkinTextureId(playerName, minecraftUuid);
        if (textureId == null || textureId.isBlank()) {
            textureId = STEVE_TEXTURE_FALLBACK;
        }

        String skinUrl = "https://mc-heads.net/avatar/" + textureId + ".png";

        skinUrlCache.put(cacheKey, skinUrl);
        return skinUrl;
    }

    private String findCustomSkinTextureId(String playerName, String minecraftUuid) {
        File[] files = skinsRestorerFolder.listFiles((dir, name) -> name.endsWith(".playerskin"));
        if (files == null) {
            return null;
        }

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                boolean nameMatches = false;
                if (json.has("lastKnownName") && playerName != null) {
                    String nameInFile = json.get("lastKnownName").getAsString();
                    nameMatches = nameInFile.equalsIgnoreCase(playerName);
                }

                boolean uuidMatches = false;
                if (json.has("playerUniqueId") && minecraftUuid != null) {
                    String uuidInFile = json.get("playerUniqueId").getAsString();
                    uuidMatches = uuidInFile.equalsIgnoreCase(minecraftUuid);
                }

                if ((nameMatches || uuidMatches) && json.has("value")) {
                    String valueBase64 = json.get("value").getAsString();
                    String decodedValue = new String(Base64.getDecoder().decode(valueBase64), StandardCharsets.UTF_8);
                    JsonObject textureJson = JsonParser.parseString(decodedValue).getAsJsonObject();

                    if (textureJson.has("textures")) {
                        JsonObject textures = textureJson.getAsJsonObject("textures");
                        if (textures.has("SKIN")) {
                            String fullUrl = textures.getAsJsonObject("SKIN").get("url").getAsString();
                            String textureId = fullUrl.substring(fullUrl.lastIndexOf("/") + 1);
                            return textureId;
                        }
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    private int getSimpleStatistic(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int getMaterialStatistic(OfflinePlayer player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private JsonArray getTopMaterials(Map<Material, Integer> source) {
        JsonArray top = new JsonArray();
        source.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("material", entry.getKey().name().toLowerCase(Locale.ROOT));
                    item.addProperty("count", entry.getValue());
                    top.add(item);
                });
        return top;
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
