package com.leir4iks.cookiepl.modules.airsword;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public final class AirSwordModule implements IModule {
    private AirSwordListener listener;

    @Override
    public void enable(CookiePl plugin) {
        if (this.listener != null) {
            disable(plugin);
        }

        this.listener = new AirSwordListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(this.listener, plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (this.listener == null) {
            return;
        }

        HandlerList.unregisterAll(this.listener);
        this.listener.clearCooldowns();
        this.listener = null;
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