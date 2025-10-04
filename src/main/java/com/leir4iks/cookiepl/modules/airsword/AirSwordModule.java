package com.leir4iks.cookiepl.modules.airsword;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class AirSwordModule implements IModule {
    private AirSwordListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new AirSwordListener(plugin);
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
        return "AirSword";
    }

    @Override
    public String getConfigKey() {
        return "air-sword";
    }
}