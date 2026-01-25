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
    private final Map<String, String> skinUrlCache = new ConcurrentHashMap<>();
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
        this.updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateExternalData, 0L, 3600L);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
        }
    }

    private void loadDataYml() {
        if (!dataFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : config.getKeys(false)) {
            externalCache.put(key, config.getString(key));
        }
    }

    private void updateExternalData() {
        HttpURLConnection connection = null;
        boolean changed = false;
        try {
            URL url = new URL(externalDatabaseUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == 200) {
                try (Scanner scanner = new Scanner(connection.getInputStream())) {
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
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update external database: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (changed) {
            saveDataYml();
        }
    }

    private void saveDataYml() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, String> entry : externalCache.entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    public String getDatabaseJson() {
        Map<String, String> userCacheMap = loadUserCache();
        JsonArray rootArray = new JsonArray();
        File accountsFile = new File(discordSrvFolder, "accounts.aof");

        if (accountsFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(accountsFile.toPath());
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        String discordId = parts[0];
                        String minecraftUuid = parts[1];

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

                        rootArray.add(playerData);
                    }
                }
            } catch (IOException e) {
                plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
                JsonObject errorObj = new JsonObject();
                errorObj.addProperty("error", "Failed to read database file");
                rootArray.add(errorObj);
            }
        } else {
            JsonObject errorObj = new JsonObject();
            errorObj.addProperty("error", "DiscordSRV accounts.aof not found");
            rootArray.add(errorObj);
        }

        return rootArray.toString();
    }

    private JsonObject getPlayerStatistics(OfflinePlayer player) {
        try {
            boolean isOnline = player.isOnline();

            if (player.hasPlayedBefore() || isOnline) {
                JsonObject stats = new JsonObject();
                long blocksBroken = 0;
                long blocksPlaced = 0;

                for (Material m : Material.values()) {
                    if (!m.isLegacy() && m.isBlock()) {
                        try {
                            blocksBroken += player.getStatistic(Statistic.MINE_BLOCK, m);
                        } catch (Exception ignored) {}

                        try {
                            blocksPlaced += player.getStatistic(Statistic.USE_ITEM, m);
                        } catch (Exception ignored) {}
                    }
                }

                stats.addProperty("blocks_broken", blocksBroken);
                stats.addProperty("blocks_placed", blocksPlaced);

                long playTimeTicks = 0;
                try {
                    playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
                } catch (Exception ignored) {}

                double playTimeHours = playTimeTicks / 20.0 / 3600.0;
                stats.addProperty("play_time_hours", Math.round(playTimeHours * 10.0) / 10.0);

                long deaths = 0;
                try { deaths = player.getStatistic(Statistic.DEATHS); } catch (Exception ignored) {}
                stats.addProperty("deaths", deaths);

                long playerKills = 0;
                try { playerKills = player.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception ignored) {}
                stats.addProperty("player_kills", playerKills);

                long mobKills = 0;
                try { mobKills = player.getStatistic(Statistic.MOB_KILLS); } catch (Exception ignored) {}
                stats.addProperty("mob_kills", mobKills);

                int leaves = 0;
                try { leaves = player.getStatistic(Statistic.LEAVE_GAME); } catch (Exception ignored) {}

                int joins = isOnline ? leaves + 1 : leaves;
                stats.addProperty("joins", joins);

                return stats;
            }
        } catch (Exception ignored) {}

        return getEmptyStats();
    }

    private JsonObject getEmptyStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("blocks_broken", 0);
        stats.addProperty("blocks_placed", 0);
        stats.addProperty("play_time_hours", 0);
        stats.addProperty("deaths", 0);
        stats.addProperty("player_kills", 0);
        stats.addProperty("mob_kills", 0);
        stats.addProperty("joins", 0);
        return stats;
    }

    private String getSkinUrlForPlayer(String playerName, String fallbackUuid) {
        if (playerName == null || playerName.equals("Unknown")) {
            return "https://mc-heads.net/avatar/MHF_Steve.png";
        }

        String cached = skinUrlCache.get(playerName);
        if (cached != null) {
            return cached;
        }

        String skinUrl = findCustomSkin(playerName);
        if (skinUrl == null) {
            skinUrl = "https://mc-heads.net/avatar/" + playerName + ".png";
        }

        skinUrlCache.put(playerName, skinUrl);
        return skinUrl;
    }

    private String findCustomSkin(String playerName) {
        File[] files = skinsRestorerFolder.listFiles((dir, name) -> name.endsWith(".playerskin"));
        if (files == null) {
            return null;
        }

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                if (json.has("lastKnownName")) {
                    String nameInFile = json.get("lastKnownName").getAsString();
                    if (nameInFile.equalsIgnoreCase(playerName)) {
                        if (json.has("value")) {
                            String valueBase64 = json.get("value").getAsString();
                            String decodedValue = new String(Base64.getDecoder().decode(valueBase64), StandardCharsets.UTF_8);
                            JsonObject textureJson = JsonParser.parseString(decodedValue).getAsJsonObject();

                            if (textureJson.has("textures")) {
                                JsonObject textures = textureJson.getAsJsonObject("textures");
                                if (textures.has("SKIN")) {
                                    String fullUrl = textures.getAsJsonObject("SKIN").get("url").getAsString();
                                    String textureId = fullUrl.substring(fullUrl.lastIndexOf("/") + 1);
                                    return "https://mc-heads.net/avatar/" + textureId + ".png";
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                continue;
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