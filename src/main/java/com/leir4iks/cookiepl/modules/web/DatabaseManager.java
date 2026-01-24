package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leir4iks.cookiepl.CookiePl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
    }

    public String getDatabaseJson() {
        JsonArray jsonArray = new JsonArray();
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

                        JsonObject playerObj = new JsonObject();
                        playerObj.addProperty("discord_id", discordId);
                        playerObj.addProperty("minecraft_uuid", minecraftUuid);

                        jsonArray.add(playerObj);
                    }
                }
            } catch (IOException e) {
                plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
                JsonObject error = new JsonObject();
                error.addProperty("error", "Failed to read database file");
                jsonArray.add(error);
            }
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("error", "DiscordSRV accounts.aof not found");
            jsonArray.add(error);
        }

        return jsonArray.toString();
    }
}