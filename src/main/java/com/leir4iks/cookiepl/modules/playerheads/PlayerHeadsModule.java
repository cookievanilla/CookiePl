package com.leir4iks.cookiepl.modules.playerheads;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class PlayerHeadsModule implements IModule {
    private PlayerHeadsListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new PlayerHeadsListener(plugin);
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
        return "PlayerHeads";
    }

    @Override
    public String getConfigKey() {
        return "player-heads";
    }
}