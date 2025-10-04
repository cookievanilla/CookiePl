package com.leir4iks.cookiepl.modules.hookah;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class HookahModule implements IModule {
    private HookahListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new HookahListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (listener != null) {
            listener.cancelTask();
            HandlerList.unregisterAll(listener);
        }
    }

    @Override
    public String getName() {
        return "Hookah";
    }

    @Override
    public String getConfigKey() {
        return "hookah";
    }
}