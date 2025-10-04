package com.leir4iks.cookiepl.modules.tagitem;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class TagItemModule implements IModule {
    private TagItemListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new TagItemListener(plugin, plugin.getLogManager());
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
        return "TagItem";
    }

    @Override
    public String getConfigKey() {
        return "tag-item";
    }
}