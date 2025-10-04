package com.leir4iks.cookiepl.modules.tagitem;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TagItemListener implements Listener {

    private final CookiePl plugin;
    private final LogManager logManager;

    public TagItemListener(CookiePl plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        Material tagBlockType;
        try {
            String blockName = plugin.getConfig().getString("modules.tag-item.tag-block", "FLETCHING_TABLE");
            tagBlockType = Material.valueOf(blockName.toUpperCase());
        } catch (IllegalArgumentException e) {
            logManager.warn("Invalid block material for TagItem module in config.yml!");
            return;
        }

        if (clickedBlock.getType() != tagBlockType) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand.getType() == Material.AIR) {
            sendMessage(player, plugin.getConfig().getString("modules.tag-item.messages.no-item"));
            return;
        }

        ItemMeta meta = itemInHand.getItemMeta();
        if (meta == null) {
            return;
        }

        String tagFormat = plugin.getConfig().getString("modules.tag-item.tag-format", "&7#{player}");
        String tagLine = formatColor(tagFormat.replace("{player}", player.getName()));

        if (meta.hasLore() && Objects.requireNonNull(meta.getLore()).contains(tagLine)) {
            sendMessage(player, plugin.getConfig().getString("modules.tag-item.messages.already-tagged"));
            return;
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        lore.add(tagLine);
        meta.setLore(lore);
        itemInHand.setItemMeta(meta);

        sendMessage(player, plugin.getConfig().getString("modules.tag-item.messages.tag-applied"));
        logManager.debug("Player " + player.getName() + " tagged item " + itemInHand.getType());
    }

    private void sendMessage(@NotNull Player player, String message) {
        if (message != null && !message.isEmpty()) {
            player.sendMessage(formatColor(message));
        }
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}