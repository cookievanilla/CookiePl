package com.leir4iks.cookiepl.modules.funcommands;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.command.PluginCommand;

import java.util.Objects;

public class FunCommandsModule implements IModule {
    @Override
    public void enable(CookiePl plugin) {
        FunCommandsExecutor funExecutor = new FunCommandsExecutor(plugin);
        if (plugin.getConfig().getBoolean("modules.fun-commands.fart.enabled", false)) {
            Objects.requireNonNull(plugin.getCommand("fart")).setExecutor(funExecutor);
        }
        if (plugin.getConfig().getBoolean("modules.fun-commands.spit.enabled", false)) {
            Objects.requireNonNull(plugin.getCommand("spit")).setExecutor(funExecutor);
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand fartCmd = plugin.getCommand("fart");
        if (fartCmd != null) fartCmd.setExecutor(null);
        PluginCommand spitCmd = plugin.getCommand("spit");
        if (spitCmd != null) spitCmd.setExecutor(null);
    }

    @Override
    public String getName() {
        return "FunCommands";
    }

    @Override
    public String getConfigKey() {
        return "fun-commands";
    }
}