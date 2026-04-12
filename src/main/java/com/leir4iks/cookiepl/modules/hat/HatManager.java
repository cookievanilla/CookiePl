package com.leir4iks.cookiepl.modules.hat;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

    /*
     * Thanks to Essentials
     */

public class HatManager implements CommandExecutor, TabCompleter {

    private final CookiePl plugin;

    public HatManager(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "players-only");
            return true;
        }

        if (shouldRemove(args)) {
            removeHat(player);
            return true;
        }

        wearHat(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();
        if ("remove".startsWith(input)) {
            completions.add("remove");
        }
        if ("wear".startsWith(input)) {
            completions.add("wear");
        }
        return completions;
    }

    private boolean shouldRemove(String[] args) {
        if (args.length == 0) {
            return false;
        }

        String value = args[0].toLowerCase(Locale.ROOT);
        return value.contains("rem") || value.contains("off") || value.equals("0");
    }

    private void wearHat(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            sendMessage(player, "hat-fail");
            return;
        }

        if (isPreventedType(player, hand.getType())) {
            sendMessage(player, "hat-fail");
            return;
        }

        if (hand.getType().getMaxDurability() != 0) {
            sendMessage(player, "hat-armor");
            return;
        }

        ItemStack head = inventory.getHelmet();
        if (hasBindingCurse(head) && !canIgnoreBinding(player)) {
            sendMessage(player, "hat-curse");
            return;
        }

        inventory.setHelmet(hand);
        inventory.setItemInMainHand(head == null ? new ItemStack(Material.AIR) : head);
        sendMessage(player, "hat-placed");
    }

    private void removeHat(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack head = inventory.getHelmet();
        if (head == null || head.getType() == Material.AIR) {
            sendMessage(player, "hat-empty");
            return;
        }

        if (hasBindingCurse(head) && !canIgnoreBinding(player)) {
            sendMessage(player, "hat-curse");
            return;
        }

        inventory.setHelmet(new ItemStack(Material.AIR));
        Map<Integer, ItemStack> leftovers = inventory.addItem(head);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        sendMessage(player, "hat-removed");
    }

    private boolean hasBindingCurse(ItemStack itemStack) {
        return itemStack != null && itemStack.getEnchantments().containsKey(Enchantment.BINDING_CURSE);
    }

    private boolean canIgnoreBinding(Player player) {
        String permission = plugin.getConfig().getString("modules.hat.permission-ignore-binding", "cookiepl.hat.ignore-binding");
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private boolean isPreventedType(Player player, Material material) {
        String prefix = plugin.getConfig().getString("modules.hat.prevent-type-permission-prefix", "cookiepl.hat.prevent-type.");
        PermissionState wildcard = getExactPermissionState(player, prefix + "*");
        PermissionState exact = getExactPermissionState(player, prefix + material.name().toLowerCase(Locale.ROOT));
        return (wildcard == PermissionState.TRUE && exact != PermissionState.FALSE)
                || (wildcard != PermissionState.TRUE && exact == PermissionState.TRUE);
    }

    private PermissionState getExactPermissionState(Player player, String permission) {
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getPermission().equalsIgnoreCase(permission)) {
                return info.getValue() ? PermissionState.TRUE : PermissionState.FALSE;
            }
        }
        return PermissionState.UNSET;
    }

    private void sendMessage(CommandSender sender, String key) {
        String prefix = plugin.getConfig().getString("modules.hat.messages.prefix", "&6[Hat] ");
        String message = plugin.getConfig().getString("modules.hat.messages." + key, "&cMessage not found: " + key);
        sender.sendMessage(color(prefix + message));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum PermissionState {
        TRUE,
        FALSE,
        UNSET
    }
}
