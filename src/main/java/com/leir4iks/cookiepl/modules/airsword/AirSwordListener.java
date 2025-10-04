package com.leir4iks.cookiepl.modules.airsword;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class AirSwordListener implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final long cooldownMillis;

    private static final EnumSet<Material> SWORD_MATERIALS = EnumSet.of(
            Material.DIAMOND_SWORD,
            Material.WOODEN_SWORD,
            Material.IRON_SWORD,
            Material.STONE_SWORD,
            Material.GOLDEN_SWORD,
            Material.NETHERITE_SWORD
    );

    public AirSwordListener(CookiePl plugin) {
        this.plugin = plugin;
        this.cooldownMillis = plugin.getConfig().getInt("modules.air-sword.cooldown-ticks", 10) * 50L;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (!SWORD_MATERIALS.contains(itemInHand.getType())) {
            return;
        }

        if (isOnCooldown(player)) {
            return;
        }

        event.setCancelled(true);
        setCooldown(player);

        int cooldownTicks = plugin.getConfig().getInt("modules.air-sword.cooldown-ticks", 10);
        player.setCooldown(itemInHand.getType(), cooldownTicks);

        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_THROW, 1.0f, 1.0f);

        Location particleLocation = player.getLocation()
                .add(0, 1, 0)
                .add(player.getLocation().getDirection());
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, particleLocation, 1, 0, 0, 0, 0);
    }

    private boolean isOnCooldown(Player player) {
        long lastUsed = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return (System.currentTimeMillis() - lastUsed) < cooldownMillis;
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }
}