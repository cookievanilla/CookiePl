package com.leir4iks.cookiepl.modules.hat;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.command.PluginCommand;

public class HatModule implements IModule {

    private HatManager hatManager;

    @Override
    public void enable(CookiePl plugin) {
        this.hatManager = new HatManager(plugin);
        PluginCommand command = plugin.getCommand("hat");
        if (command != null) {
            command.setExecutor(this.hatManager);
            command.setTabCompleter(this.hatManager);
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand command = plugin.getCommand("hat");
        if (command != null) {
            command.setExecutor(null);
            command.setTabCompleter(null);
        }
        this.hatManager = null;
    }

    @Override
    public String getName() {
        return "Hat";
    }

    @Override
    public String getConfigKey() {
        return "hat";
    }
}
