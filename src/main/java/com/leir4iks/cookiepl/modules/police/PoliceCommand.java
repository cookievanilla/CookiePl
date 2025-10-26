package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.policebatons.BatonManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public class PoliceCommand implements CommandExecutor {

    private final CookiePl plugin;

    public PoliceCommand(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        String permission = plugin.getConfig().getString("modules.police-system.permission", "cookiepl.police.use");
        if (!player.hasPermission(permission)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            switch (args[1].toLowerCase()) {
                case "handcuffs":
                    player.getInventory().addItem(createPoliceItem("handcuffs-item", PoliceManager.HANDCUFFS_KEY, false));
                    player.sendMessage(ChatColor.GREEN + "You received handcuffs.");
                    break;
                case "baton":
                    player.getInventory().addItem(createPoliceItem("baton-item", PoliceManager.BATON_KEY, true));
                    player.sendMessage(ChatColor.GREEN + "You received a police baton.");
                    break;
                case "fbi-baton":
                    player.getInventory().addItem(BatonManager.createFbiBaton(plugin));
                    player.sendMessage(ChatColor.GREEN + "You received an FBI baton.");
                    break;
                case "electroshock-baton":
                    player.getInventory().addItem(BatonManager.createElectroshockBaton(plugin));
                    player.sendMessage(ChatColor.GREEN + "You received an electroshock baton.");
                    break;
                default:
                    player.sendMessage(ChatColor.RED + "Usage: /police give <handcuffs|baton|fbi-baton|electroshock-baton>");
                    break;
            }
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /police give <item>");
        }
        return true;
    }

    private ItemStack createPoliceItem(String configKey, NamespacedKey itemKey, boolean unbreakable) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("modules.police-system." + configKey);
        Material material = Material.matchMaterial(section.getString("material", "AIR"));
        String name = ChatColor.translateAlternateColorCodes('&', section.getString("display-name", ""));
        int modelData = section.getInt("custom-model-data", 0);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }
        meta.setUnbreakable(unbreakable);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        return item;
    }
}