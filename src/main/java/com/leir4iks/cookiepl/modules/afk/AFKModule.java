package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;

public class AFKModule implements IModule {

    private AFKManager afkManager;
    private AFKPlaceholder afkPlaceholder;
    private AFKCommand afkCommand;

    public static NamespacedKey AFK_INDICATOR_KEY;

    @Override
    public void enable(CookiePl plugin) {
        AFK_INDICATOR_KEY = new NamespacedKey(plugin, "afk_indicator");

        this.afkManager = new AFKManager(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(afkManager, plugin);

        this.afkCommand = new AFKCommand(plugin, afkManager);
        PluginCommand cmd = plugin.getCommand("afk");
        if (cmd != null) {
            cmd.setExecutor(afkCommand);
        }

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
            HandlerList.unregisterAll(afkManager);
        }

        PluginCommand cmd = plugin.getCommand("afk");
        if (cmd != null) {
            cmd.setExecutor(null);
        }

        if (afkPlaceholder != null && afkPlaceholder.isRegistered()) {
            afkPlaceholder.unregister();
        }

        this.afkManager = null;
        this.afkPlaceholder = null;
        this.afkCommand = null;
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