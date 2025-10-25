package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

public class AFKModule implements IModule {

    private AFKManager afkManager;
    private AFKPlaceholder afkPlaceholder;
    public static NamespacedKey AFK_INDICATOR_KEY;

    @Override
    public void enable(CookiePl plugin) {
        AFK_INDICATOR_KEY = new NamespacedKey(plugin, "afk_indicator");
        cleanupStaleIndicators();

        this.afkManager = new AFKManager(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(afkManager, plugin);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.afkPlaceholder = new AFKPlaceholder(plugin, afkManager);
            this.afkPlaceholder.register();
            plugin.getLogManager().info("Hooked into PlaceholderAPI for AFK module.");
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        if (afkManager != null) {
            afkManager.shutdown();
        }
        if (afkPlaceholder != null && afkPlaceholder.isRegistered()) {
            afkPlaceholder.unregister();
        }
        this.afkManager = null;
        this.afkPlaceholder = null;
    }

    private void cleanupStaleIndicators() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getPersistentDataContainer().has(AFK_INDICATOR_KEY, PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
    }

    @Override
    public String getName() {
        return "AFKSystem";
    }

    @Override
    public String getConfigKey() {
        return "afk-system";
    }
}