package com.leir4iks.cookiepl.config;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;

public class ConfigManager {

    private final CookiePl plugin;

    public ConfigManager(CookiePl plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        updateConfig();
        plugin.reloadConfig();
    }

    private void updateConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        try (InputStream defConfigStream = plugin.getResource("config.yml")) {
            if (defConfigStream == null) return;

            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            boolean modified = false;

            Set<String> defKeys = defConfig.getKeys(true);

            for (String key : defKeys) {
                if (!currentConfig.contains(key)) {
                    currentConfig.set(key, defConfig.get(key));
                    modified = true;
                }
            }

            if (modified) {
                currentConfig.save(configFile);
                plugin.getLogger().info("Config.yml was outdated. New keys have been added successfully.");
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Could not update config.yml", e);
        }
    }
}