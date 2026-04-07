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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AirSwordListener implements Listener {

    private static final EnumSet<Material> SWORD_MATERIALS = EnumSet.of(
            Material.WOODEN_SWORD,
            Material.STONE_SWORD,
            Material.IRON_SWORD,
            Material.GOLDEN_SWORD,
            Material.DIAMOND_SWORD,
            Material.NETHERITE_SWORD
    );

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final long cooldownMillis;
    private final int cooldownTicks;

    public AirSwordListener(CookiePl plugin) {
        this.cooldownTicks = plugin.getConfig().getInt("modules.air-sword.cooldown-ticks", 10);
        this.cooldownMillis = this.cooldownTicks * 50L;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!event.hasItem()) {
            return;
        }

        ItemStack itemInHand = event.getItem();
        Material material = itemInHand.getType();

        if (!SWORD_MATERIALS.contains(material)) {
            return;
        }

        Player player = event.getPlayer();

        if (isOnCooldown(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        setCooldown(player.getUniqueId());

        player.setCooldown(material, this.cooldownTicks);
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_THROW, 1.0f, 1.0f);

        Location particleLocation = player.getLocation();
        particleLocation.add(0.0, 1.0, 0.0);
        particleLocation.add(particleLocation.getDirection());

        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, particleLocation, 1, 0.0, 0.0, 0.0, 0.0);
    }

    public void clearCooldowns() {
        this.cooldowns.clear();
    }

    private boolean isOnCooldown(UUID playerId) {
        long lastUsed = this.cooldowns.getOrDefault(playerId, 0L);
        return System.currentTimeMillis() - lastUsed < this.cooldownMillis;
    }

    private void setCooldown(UUID playerId) {
        this.cooldowns.put(playerId, System.currentTimeMillis());
    }
}