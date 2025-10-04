package com.leir4iks.cookiepl.modules.wheremyframe;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WhereMyFrameCommand implements CommandExecutor, TabCompleter {

    private final CookiePl plugin;
    private static final List<String> SUB_COMMANDS = Collections.singletonList("info");

    public WhereMyFrameCommand(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String permission = plugin.getConfig().getString("modules.where-my-frame.permission", "cookiepl.command.wmf");
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.GRAY + "Module Author" + ChatColor.DARK_GRAY + " > " + ChatColor.WHITE + "lluk");
            sender.sendMessage(ChatColor.GRAY + "Plugin" + ChatColor.DARK_GRAY + " > " + ChatColor.WHITE + "CookiePl v" + plugin.getDescription().getVersion());
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <info>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUB_COMMANDS, new ArrayList<>());
        }
        return new ArrayList<>();
    }
}