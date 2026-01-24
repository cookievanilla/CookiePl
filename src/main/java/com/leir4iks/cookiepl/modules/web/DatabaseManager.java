package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final File skinsRestorerFolder;
    private final File dataFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private volatile String cachedJsonResponse = "{}";
    private final Map<String, String> externalMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.skinsRestorerFolder = new File(plugin.getDataFolder().getParentFile(), "SkinsRestorer/skins");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");

        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void startTask() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::updateDatabase, 0, 3, TimeUnit.MINUTES);
    }

    public void stopTask() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    public String getCachedJson() {
        return cachedJsonResponse;
    }

    private void updateDatabase() {
        try {
            syncExternalData();

            Map<String, String> userCacheMap = loadUserCache();
            JsonObject rootObject = new JsonObject();
            File accountsFile = new File(discordSrvFolder, "accounts.aof");

            if (accountsFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(accountsFile.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;

                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            String discordId = parts[0];
                            String minecraftUuid = parts[1];
                            String minecraftName = "Unknown";

                            if (externalMap.containsKey(discordId)) {
                                minecraftName = externalMap.get(discordId);
                            } else if (userCacheMap.containsKey(minecraftUuid)) {
                                minecraftName = userCacheMap.get(minecraftUuid);
                            }

                            String skinUrl = getSkinUrl(minecraftName, minecraftUuid);

                            JsonObject playerData = new JsonObject();
                            playerData.addProperty("minecraft_name", minecraftName);
                            playerData.addProperty("minecraft_uuid", minecraftUuid);
                            playerData.addProperty("skin_url", skinUrl);

                            rootObject.add(discordId, playerData);
                        }
                    }
                } catch (IOException e) {
                    rootObject.addProperty("error", "Failed to read database file");
                }
            } else {
                rootObject.addProperty("error", "DiscordSRV accounts.aof not found");
            }

            this.cachedJsonResponse = rootObject.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getSkinUrl(String name, String uuid) {
        String fallbackUrl;
        if (name == null || name.equalsIgnoreCase("Unknown")) {
            fallbackUrl = "https://mc-heads.net/avatar/" + uuid + ".png";
        } else {
            fallbackUrl = "https://mc-heads.net/avatar/" + name + ".png";
        }

        File skinFile = new File(skinsRestorerFolder, uuid.toLowerCase() + ".playerskin");

        if (skinFile.exists()) {
            try {
                String content = Files.readString(skinFile.toPath(), StandardCharsets.UTF_8).trim();
                JsonObject jsonObject = JsonParser.parseString(content).getAsJsonObject();

                if (jsonObject.has("value")) {
                    String base64Value = jsonObject.get("value").getAsString();
                    byte[] decodedBytes = Base64.getDecoder().decode(base64Value);
                    String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);

                    JsonObject textureJson = JsonParser.parseString(decodedJson).getAsJsonObject();
                    if (textureJson.has("textures")) {
                        JsonObject textures = textureJson.getAsJsonObject("textures");
                        if (textures.has("SKIN")) {
                            JsonObject skin = textures.getAsJsonObject("SKIN");
                            if (skin.has("url")) {
                                String textureUrl = skin.get("url").getAsString();
                                String hash = textureUrl.substring(textureUrl.lastIndexOf('/') + 1);
                                return "https://mc-heads.net/avatar/" + hash + ".png";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return fallbackUrl;
    }

    private void syncExternalData() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        boolean needsSave = false;

        for (String key : yaml.getKeys(false)) {
            externalMap.put(key, yaml.getString(key));
        }

        try {
            URL url = new URL(externalDatabaseUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");

            try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        String discordId = parts[0];
                        String nickname = parts[1];

                        if (!yaml.contains(discordId) || !nickname.equals(yaml.getString(discordId))) {
                            yaml.set(discordId, nickname);
                            externalMap.put(discordId, nickname);
                            needsSave = true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (needsSave) {
            try {
                yaml.save(dataFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Map<String, String> loadUserCache() {
        Map<String, String> map = new ConcurrentHashMap<>();
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
            } catch (Exception ignored) {
            }
        }
        return map;
    }
}