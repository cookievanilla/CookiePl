package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public class PoliceModule implements IModule {

    private PoliceManager policeManager;
    private PoliceListener listener;
    private PoliceCommand command;

    @Override
    public void enable(CookiePl plugin) {
        this.policeManager = new PoliceManager(plugin, plugin.getLogManager());
        this.listener = new PoliceListener(plugin, policeManager);
        this.command = new PoliceCommand(plugin);

        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PluginCommand pluginCommand = Objects.requireNonNull(plugin.getCommand("police"));
        pluginCommand.setExecutor(command);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (policeManager != null) {
            policeManager.cleanUpAll();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        PluginCommand pluginCommand = plugin.getCommand("police");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(null);
        }
    }

    @Override
    public String getName() {
        return "PoliceSystem";
    }

    @Override
    public String getConfigKey() {
        return "police-system";
    }
}