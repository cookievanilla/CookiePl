package com.leir4iks.cookiepl.modules.policebatons;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class PoliceBatonsModule implements IModule {
    private BatonListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new BatonListener(plugin);
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
        return "PoliceBatons";
    }

    @Override
    public String getConfigKey() {
        return "police-batons";
    }
}