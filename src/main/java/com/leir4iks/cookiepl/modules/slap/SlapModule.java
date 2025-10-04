package com.leir4iks.cookiepl.modules.slap;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class SlapModule implements IModule {

    private SlapListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new SlapListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    @Override
    public String getName() {
        return "Slap";
    }

    @Override
    public String getConfigKey() {
        return "slap";
    }
}