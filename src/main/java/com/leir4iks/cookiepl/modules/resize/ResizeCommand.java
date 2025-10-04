package com.leir4iks.cookiepl.modules.resize;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ResizeCommand implements CommandExecutor, TabCompleter {

    private final CookiePl plugin;
    private final ResizeManager manager;

    public ResizeCommand(CookiePl plugin, ResizeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String basePerm = plugin.getConfig().getString("modules.resize.permission", "cookiepl.resize");
        if (!sender.hasPermission(basePerm)) {
            sendMessage(sender, "no-permission");
            return true;
        }

        String reloadPerm = plugin.getConfig().getString("modules.resize.permission-reload", "cookiepl.resize.reload");
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission(reloadPerm)) {
                sendMessage(sender, "reloaded");
            } else {
                sendMessage(sender, "no-permission");
            }
            return true;
        }

        Player target;
        double scale;
        String othersPerm = plugin.getConfig().getString("modules.resize.permission-others", "cookiepl.resize.others");

        if (args.length >= 2 && sender.hasPermission(othersPerm)) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sendMessage(sender, "player-not-found", "{player}", args[1]);
                return true;
            }
            try {
                scale = Double.parseDouble(args[0]);
            } catch (NumberFormatException e) {
                sendMessage(sender, "invalid-number");
                return true;
            }
        } else if (args.length == 1) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "players-only");
                return true;
            }
            target = (Player) sender;
            try {
                scale = Double.parseDouble(args[0]);
            } catch (NumberFormatException e) {
                sendMessage(sender, "invalid-number");
                return true;
            }
        } else {
            sendMessage(sender, "usage");
            return true;
        }

        String extendedPerm = plugin.getConfig().getString("modules.resize.permission-extended", "cookiepl.resize.extended");
        boolean hasExtended = sender.hasPermission(extendedPerm);

        if (!manager.isValidScale(scale, hasExtended)) {
            sendMessage(sender, "scale-range", "{min}", String.valueOf(manager.getMinScale(hasExtended)), "{max}", String.valueOf(manager.getMaxScale(hasExtended)));
            return true;
        }

        if (manager.hasCooldown(target)) {
            sendMessage(sender, "cooldown", "{time}", String.valueOf(manager.getRemainingCooldown(target)));
            return true;
        }

        manager.updateLastUsage(target);
        manager.smoothlyResizePlayer(target, scale);

        if (target.equals(sender)) {
            sendMessage(sender, "resized", "{scale}", String.valueOf(scale));
        } else {
            sendMessage(sender, "resized-other", "{player}", target.getName(), "{scale}", String.valueOf(scale));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = Arrays.asList("0.8", "1.0", "1.15");
            if (sender.hasPermission(plugin.getConfig().getString("modules.resize.permission-extended", "cookiepl.resize.extended"))) {
                completions.addAll(Arrays.asList("0.0625", "16.0"));
            }
            return completions;
        }
        if (args.length == 2 && sender.hasPermission(plugin.getConfig().getString("modules.resize.permission-others", "cookiepl.resize.others"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void sendMessage(CommandSender sender, String key, String... placeholders) {
        String prefix = plugin.getConfig().getString("modules.resize.messages.prefix", "&d[&5Resize&d] ");
        String message = plugin.getConfig().getString("modules.resize.messages." + key, "&cMessage not found: " + key);

        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                message = message.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + message));
    }
}