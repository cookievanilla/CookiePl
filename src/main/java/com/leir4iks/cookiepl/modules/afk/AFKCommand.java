package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class AFKCommand implements CommandExecutor {

    private final CookiePl plugin;
    private final AFKManager manager;

    public AFKCommand(CookiePl plugin, AFKManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            String msg = plugin.getConfig().getString("modules.afk-system.messages.only-player", "&cOnly players can use this command.");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return true;
        }

        Player player = (Player) sender;
        boolean currentStatus = manager.isAfk(player);
        manager.setAfk(player, !currentStatus);

        return true;
    }
}