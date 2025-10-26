package com.leir4iks.cookiepl.commands;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("fixinvulnerable")) {
                if (sender.hasPermission("cookiepl.command.admin")) {
                    int fixedPlayers = 0;
                    for (Player player : plugin.getServer().getOnlinePlayers()) {
                        if (player.isInvulnerable() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                            player.setInvulnerable(false);
                            fixedPlayers++;
                        }
                    }
                    sender.sendMessage(ChatColor.GREEN + "Fixed " + fixedPlayers + " players stuck in invulnerability mode.");
                    plugin.getLogManager().info("Admin " + sender.getName() + " ran invulnerability fix. Affected players: " + fixedPlayers);
                } else {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("cookiepl.command.reload")) {
                    plugin.reloadAllModules();
                    sender.sendMessage(ChatColor.GREEN + "CookiePl configuration and modules reloaded.");
                } else {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                }
                return true;
            }
        }

        sender.sendMessage(ChatColor.AQUA + "This server is running CookiePl v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Use /cookiepl <reload|fixinvulnerable>");
        return true;
    }
}