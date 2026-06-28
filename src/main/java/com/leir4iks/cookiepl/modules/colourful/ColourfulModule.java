package com.leir4iks.cookiepl.modules.colourful;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;

public class ColourfulModule implements IModule {

    private ColourCommand colourCommand;
    private ColourfulPlaceholder placeholder;

    @Override
    public void enable(CookiePl plugin) {
        colourCommand = new ColourCommand(plugin);

        PluginCommand cmd = plugin.getCommand("colour");
        if (cmd != null) {
            cmd.setExecutor(colourCommand);
            cmd.setTabCompleter(colourCommand);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholder = new ColourfulPlaceholder(plugin);
            placeholder.register();
            plugin.getLogManager().info("Colourful: hooked into PlaceholderAPI (%ccolour%).");
        } else {
            plugin.getLogManager().warn("Colourful: PlaceholderAPI not found — %ccolour% placeholder inactive.");
        }

        plugin.getLogManager().info("Colourful module enabled.");
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand cmd = plugin.getCommand("colour");
        if (cmd != null) {
            cmd.setExecutor(null);
            cmd.setTabCompleter(null);
        }

        if (placeholder != null && placeholder.isRegistered()) {
            placeholder.unregister();
        }

        colourCommand = null;
        placeholder = null;
    }

    @Override
    public String getName() {
        return "Colourful";
    }

    @Override
    public String getConfigKey() {
        return "colourful";
    }
}
