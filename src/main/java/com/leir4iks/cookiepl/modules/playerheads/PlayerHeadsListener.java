package com.leir4iks.cookiepl.modules.playerheads;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerHeadsListener implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private final double dropChance;
    private final long cooldownMillis;
    private final String bypassPermission;
    private final String headNameFormat;

    public PlayerHeadsListener(CookiePl plugin) {
        this.plugin = plugin;
        this.dropChance = plugin.getConfig().getDouble("modules.player-heads.drop-chance", 100.0);
        this.cooldownMillis = plugin.getConfig().getLong("modules.player-heads.cooldown-seconds", 3) * 1000;
        this.bypassPermission = plugin.getConfig().getString("modules.player-heads.bypass-permission", "cookiepl.playerheads.bypass");
        this.headNameFormat = plugin.getConfig().getString("modules.player-heads.head-name-format", "&f{player}'s Head");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (player.hasPermission(this.bypassPermission)) {
            return;
        }

        if (isOnCooldown(player)) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble(100.0) >= this.dropChance) {
            return;
        }

        setCooldown(player);

        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();

        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            String headName = this.headNameFormat.replace("{player}", player.getName());
            skullMeta.setDisplayName(formatColor(headName));
            playerHead.setItemMeta(skullMeta);
        }

        player.getWorld().dropItemNaturally(player.getLocation(), playerHead);
    }

    private boolean isOnCooldown(Player player) {
        if (this.cooldownMillis <= 0) return false;
        long lastDropped = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return (System.currentTimeMillis() - lastDropped) < this.cooldownMillis;
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}