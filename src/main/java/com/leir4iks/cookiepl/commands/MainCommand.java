package com.leir4iks.cookiepl.commands;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class MainCommand implements CommandExecutor {

    private final CookiePl plugin;

    public MainCommand(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cookiepl.command.base")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("cookiepl.command.reload")) {
                plugin.reloadAllModules();
                sender.sendMessage(ChatColor.GREEN + "CookiePl configuration and modules reloaded.");
            } else {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            }
            return true;
        }

        sender.sendMessage(ChatColor.AQUA + "This server is running CookiePl v" + plugin.getDescription().getVersion());
        return true;
    }
}