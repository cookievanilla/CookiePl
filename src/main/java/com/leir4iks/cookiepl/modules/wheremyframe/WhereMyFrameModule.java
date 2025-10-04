package com.leir4iks.cookiepl.modules.wheremyframe;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public class WhereMyFrameModule implements IModule {
    private WhereMyFrameListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new WhereMyFrameListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        PluginCommand wmfCommand = Objects.requireNonNull(plugin.getCommand("wheremyframe"));
        WhereMyFrameCommand wmfExecutor = new WhereMyFrameCommand(plugin);
        wmfCommand.setExecutor(wmfExecutor);
        wmfCommand.setTabCompleter(wmfExecutor);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        PluginCommand wmfCommand = plugin.getCommand("wheremyframe");
        if (wmfCommand != null) {
            wmfCommand.setExecutor(null);
            wmfCommand.setTabCompleter(null);
        }
    }

    @Override
    public String getName() {
        return "WhereMyFrame";
    }

    @Override
    public String getConfigKey() {
        return "where-my-frame";
    }
}