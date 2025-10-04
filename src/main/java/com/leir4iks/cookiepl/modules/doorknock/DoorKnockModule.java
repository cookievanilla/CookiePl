package com.leir4iks.cookiepl.modules.doorknock;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class DoorKnockModule implements IModule {
    private DoorKnockListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new DoorKnockListener(plugin);
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
        return "DoorKnock";
    }

    @Override
    public String getConfigKey() {
        return "door-knock";
    }
}