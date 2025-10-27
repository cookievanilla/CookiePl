package com.leir4iks.cookiepl.modules.elytra;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ElytraModule implements IModule, CommandExecutor, TabCompleter {

    private CookiePl plugin;

    private static final Map<String, Integer> ELYTRA_COLORS = Map.ofEntries(
            Map.entry("black", 1), Map.entry("blue", 2), Map.entry("brown", 3),
            Map.entry("cyan", 4), Map.entry("gray", 5), Map.entry("green", 6),
            Map.entry("light_blue", 7), Map.entry("light_gray", 8), Map.entry("lime", 9),
            Map.entry("magenta", 10), Map.entry("orange", 11), Map.entry("pink", 12),
            Map.entry("purple", 13), Map.entry("red", 14), Map.entry("white", 15),
            Map.entry("yellow", 16)
    );

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;
        PluginCommand elytraCommand = Objects.requireNonNull(plugin.getCommand("elytra"));
        elytraCommand.setExecutor(this);
        elytraCommand.setTabCompleter(this);
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand elytraCommand = plugin.getCommand("elytra");
        if (elytraCommand != null) {
            elytraCommand.setExecutor(null);
            elytraCommand.setTabCompleter(null);
        }
    }

    @Override
    public String getName() {
        return "Elytra";
    }

    @Override
    public String getConfigKey() {
        return "elytra";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        String permission = plugin.getConfig().getString("modules.elytra.permission", "cookiepl.elytra.give");

        if (!player.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /" + label + " <color>");
            return true;
        }

        String color = args[0].toLowerCase();
        Integer customModelData = ELYTRA_COLORS.get(color);

        if (customModelData == null) {
            player.sendMessage(ChatColor.RED + "Invalid color specified. Use Tab to see available colors.");
            return true;
        }

        plugin.getFoliaLib().getScheduler().runAtEntity(player, task -> {
            ItemStack bukkitElytra = new ItemStack(Material.ELYTRA);
            ItemMeta meta = bukkitElytra.getItemMeta();

            if (meta != null) {
                meta.setCustomModelData(customModelData);
                bukkitElytra.setItemMeta(meta);
            }

            player.getInventory().addItem(bukkitElytra);
            player.sendMessage(ChatColor.GREEN + "You have received a " + color + " elytra!");
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], ELYTRA_COLORS.keySet(), new ArrayList<>());
        }
        return new ArrayList<>();
    }
}