// File: C:/111/CookiePl/src/main/java/com/leir4iks/cookiepl/modules/web/DatabaseManager.java
package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
    }

    public String getDatabaseJson() {
        Map<String, String> externalMap = loadExternalDatabase();
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

                        if (externalMap.containsKey(discordId)) {
                            minecraftName = externalMap.get(discordId);
                        } else if (userCacheMap.containsKey(minecraftUuid)) {
                            minecraftName = userCacheMap.get(minecraftUuid);
                        }

                        JsonObject playerData = new JsonObject();
                        playerData.addProperty("minecraft_name", minecraftName);
                        playerData.addProperty("minecraft_uuid", minecraftUuid);

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

    private Map<String, String> loadExternalDatabase() {
        Map<String, String> map = new HashMap<>();
        try {
            URL url = new URL(externalDatabaseUrl);
            try (Scanner scanner = new Scanner(url.openStream())) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        map.put(parts[0], parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to fetch external database: " + e.getMessage());
        }
        return map;
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
            } catch (IOException e) {
                plugin.getLogManager().warn("Failed to read usercache.json: " + e.getMessage());
            }
        }
        return map;
    }
}