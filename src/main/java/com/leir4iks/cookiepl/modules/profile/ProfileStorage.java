package com.leir4iks.cookiepl.modules.profile;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

public class ProfileStorage {

    private static final String PLAYERS_PATH = "players";
    private static final String SHOW_DEATH_MESSAGES_KEY = "show-death-messages";
    private static final String SHOW_ADVANCEMENTS_KEY = "show-advancements";
    private static final String PHANTOM_SPAWN_DISABLED_KEY = "disable-phantom-spawn";

    private final CookiePl plugin;
    private final File file;
    private FileConfiguration yaml;

    public ProfileStorage(CookiePl plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "profiles.yml");
        load();
        migrateLegacyDataFromConfig();
    }

    public synchronized boolean isDeathMessageVisible(UUID playerId) {
        return getBoolean(playerId, SHOW_DEATH_MESSAGES_KEY, true);
    }

    public synchronized void setDeathMessageVisible(UUID playerId, boolean visible) {
        setBoolean(playerId, SHOW_DEATH_MESSAGES_KEY, visible, true);
    }

    public synchronized boolean isAdvancementVisible(UUID playerId) {
        return getBoolean(playerId, SHOW_ADVANCEMENTS_KEY, true);
    }

    public synchronized void setAdvancementVisible(UUID playerId, boolean visible) {
        setBoolean(playerId, SHOW_ADVANCEMENTS_KEY, visible, true);
    }

    public synchronized boolean isPhantomSpawnDisabled(UUID playerId) {
        return getBoolean(playerId, PHANTOM_SPAWN_DISABLED_KEY, false);
    }

    public synchronized void setPhantomSpawnDisabled(UUID playerId, boolean disabled) {
        setBoolean(playerId, PHANTOM_SPAWN_DISABLED_KEY, disabled, false);
    }

    private void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for profiles.yml");
        }

        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("Could not create profiles.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not create profiles.yml", e);
            }
        }

        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    private synchronized void migrateLegacyDataFromConfig() {
        ConfigurationSection legacyPlayers = plugin.getConfig().getConfigurationSection(PLAYERS_PATH);
        if (legacyPlayers == null) {
            return;
        }

        boolean migrated = false;

        for (String playerId : legacyPlayers.getKeys(false)) {
            ConfigurationSection legacyPlayerSection = legacyPlayers.getConfigurationSection(playerId);
            if (legacyPlayerSection == null) {
                continue;
            }

            if (legacyPlayerSection.contains(SHOW_DEATH_MESSAGES_KEY)) {
                yaml.set(playerPath(playerId, SHOW_DEATH_MESSAGES_KEY), legacyPlayerSection.getBoolean(SHOW_DEATH_MESSAGES_KEY, true));
                migrated = true;
            }

            if (legacyPlayerSection.contains(SHOW_ADVANCEMENTS_KEY)) {
                yaml.set(playerPath(playerId, SHOW_ADVANCEMENTS_KEY), legacyPlayerSection.getBoolean(SHOW_ADVANCEMENTS_KEY, true));
                migrated = true;
            }
        }

        if (!migrated) {
            return;
        }

        save();

        plugin.getConfig().set(PLAYERS_PATH, null);
        plugin.saveConfig();
        plugin.getLogger().info("Legacy profile data has been moved from config.yml to profiles.yml");
    }

    private boolean getBoolean(UUID playerId, String key, boolean defaultValue) {
        return yaml.getBoolean(playerPath(playerId, key), defaultValue);
    }

    private void setBoolean(UUID playerId, String key, boolean value, boolean defaultValue) {
        if (value == defaultValue) {
            yaml.set(playerPath(playerId, key), null);
            cleanupPlayerSection(playerId);
        } else {
            yaml.set(playerPath(playerId, key), value);
        }

        save();
    }

    private void cleanupPlayerSection(UUID playerId) {
        cleanupPlayerSection(playerId.toString());
    }

    private void cleanupPlayerSection(String playerId) {
        ConfigurationSection playerSection = yaml.getConfigurationSection(playerPath(playerId));
        if (playerSection == null || playerSection.getKeys(false).isEmpty()) {
            yaml.set(playerPath(playerId), null);
        }

        ConfigurationSection playersSection = yaml.getConfigurationSection(PLAYERS_PATH);
        if (playersSection == null || playersSection.getKeys(false).isEmpty()) {
            yaml.set(PLAYERS_PATH, null);
        }
    }

    private String playerPath(UUID playerId, String key) {
        return playerPath(playerId.toString(), key);
    }

    private String playerPath(String playerId, String key) {
        return playerPath(playerId) + "." + key;
    }

    private String playerPath(String playerId) {
        return PLAYERS_PATH + "." + playerId;
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save profiles.yml", e);
        }
    }
}
