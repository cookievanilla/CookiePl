package com.leir4iks.cookiepl.modules.antigrief;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.event.HandlerList;

public class AntiGriefModule implements IModule {
    private AntiGriefManager manager;

    @Override
    public void enable(CookiePl plugin) {
        this.manager = new AntiGriefManager(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(this.manager, plugin);
        this.manager.start();
    }

    @Override
    public void disable(CookiePl plugin) {
        if (this.manager != null) {
            HandlerList.unregisterAll(this.manager);
            this.manager.shutdown();
            this.manager = null;
        }
    }

    @Override
    public String getName() {
        return "AntiGrief";
    }

    @Override
    public String getConfigKey() {
        return "antigrief";
    }
}
