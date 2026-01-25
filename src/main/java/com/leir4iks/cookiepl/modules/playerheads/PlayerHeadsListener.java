package com.leir4iks.cookiepl.modules.playerheads;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerHeadsListener implements Listener {
    private final CookiePl plugin;
    private final double dropChance;
    private final long cooldownMillis;
    private final String bypassPermission;
    private final String headNameFormat;
    private final List<String> headLoreFormat;
    private final String dateFormatPattern;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Object cooldownLock = new Object();

    public PlayerHeadsListener(CookiePl plugin) {
        this.plugin = plugin;
        this.dropChance = plugin.getConfig().getDouble("modules.player-heads.drop-chance", 100.0);
        this.cooldownMillis = plugin.getConfig().getLong("modules.player-heads.cooldown-seconds", 3) * 1000;
        this.bypassPermission = plugin.getConfig().getString("modules.player-heads.bypass-permission", "cookiepl.playerheads.bypass");
        this.headNameFormat = plugin.getConfig().getString("modules.player-heads.head-name-format", "&fГолова {player}");
        this.headLoreFormat = plugin.getConfig().getStringList("modules.player-heads.head-lore-format");
        this.dateFormatPattern = plugin.getConfig().getString("modules.player-heads.date-format", "dd.MM.yyyy HH:mm:ss");
        if (headLoreFormat.isEmpty()) {
            headLoreFormat.add("&7Убит: &f{killer}");
            headLoreFormat.add("&7Время: &f{date}");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) {
            return;
        }

        if (victim.hasPermission(bypassPermission)) {
            return;
        }

        synchronized (cooldownLock) {
            if (isOnCooldown(victim)) {
                return;
            }
            setCooldown(victim);
        }

        if (ThreadLocalRandom.current().nextDouble(100.0) >= dropChance) {
            return;
        }

        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();

        if (skullMeta != null) {
            skullMeta.setOwningPlayer(victim);
            String headName = headNameFormat.replace("{player}", victim.getName());
            skullMeta.setDisplayName(formatColor(headName));

            List<String> lore = new ArrayList<>();
            for (String line : headLoreFormat) {
                String formattedLine = line
                        .replace("{player}", victim.getName())
                        .replace("{killer}", killer.getName())
                        .replace("{date}", new SimpleDateFormat(dateFormatPattern).format(new Date()));
                lore.add(formatColor(formattedLine));
            }
            skullMeta.setLore(lore);

            playerHead.setItemMeta(skullMeta);
            victim.getWorld().dropItemNaturally(victim.getLocation(), playerHead);
        }
    }

    private boolean isOnCooldown(Player player) {
        if (cooldownMillis <= 0) return false;
        Long lastDropped = cooldowns.get(player.getUniqueId());
        if (lastDropped == null) return false;
        return (System.currentTimeMillis() - lastDropped) < cooldownMillis;
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private String formatColor(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}