package com.leir4iks.cookiepl.modules.stones;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class StonesModule implements IModule {
    private StoneManager stoneManager;
    private StoneListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.stoneManager = new StoneManager(plugin, plugin.getLogManager());
        this.listener = new StoneListener(plugin, stoneManager);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (stoneManager != null) {
            stoneManager.cleanupAllStones();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    @Override
    public String getName() {
        return "ThrowableStones";
    }

    @Override
    public String getConfigKey() {
        return "throwable-stones";
    }
}