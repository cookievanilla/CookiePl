package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
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
        JsonObject rootObject = new JsonObject();
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

                        String skinUrl = findSkinUrlByPlayerName(minecraftName, minecraftUuid);

                        JsonObject playerData = new JsonObject();
                        playerData.addProperty("minecraft_name", minecraftName);
                        playerData.addProperty("minecraft_uuid", minecraftUuid);
                        playerData.addProperty("skin_url", skinUrl);

                        rootObject.add(discordId, playerData);
                    }
                }
            } catch (IOException e) {
                plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
                rootObject.addProperty("error", "Failed to read database file");
            }
        } else {
            rootObject.addProperty("error", "DiscordSRV accounts.aof not found");
        }

        return rootObject.toString();
    }

    private String findSkinUrlByPlayerName(String playerName, String fallbackUuid) {
        if (playerName == null || playerName.equals("Unknown")) {
            return "https://mc-heads.net/avatar/" + fallbackUuid.replace("-", "") + ".png";
        }

        File[] files = skinsRestorerFolder.listFiles((dir, name) -> name.endsWith(".playerskin"));

        if (files != null) {
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
        }

        return "https://mc-heads.net/avatar/" + fallbackUuid.replace("-", "") + ".png";
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