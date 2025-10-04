package com.leir4iks.cookiepl.modules.resize;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.command.PluginCommand;

import java.util.Objects;

public class ResizeModule implements IModule {
    private ResizeManager resizeManager;

    @Override
    public void enable(CookiePl plugin) {
        this.resizeManager = new ResizeManager(plugin);
        PluginCommand resizeCmd = Objects.requireNonNull(plugin.getCommand("resize"));
        ResizeCommand resizeExecutor = new ResizeCommand(plugin, resizeManager);
        resizeCmd.setExecutor(resizeExecutor);
        resizeCmd.setTabCompleter(resizeExecutor);
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand resizeCmd = plugin.getCommand("resize");
        if (resizeCmd != null) {
            resizeCmd.setExecutor(null);
            resizeCmd.setTabCompleter(null);
        }
    }

    @Override
    public String getName() {
        return "Resize";
    }

    @Override
    public String getConfigKey() {
        return "resize";
    }
}