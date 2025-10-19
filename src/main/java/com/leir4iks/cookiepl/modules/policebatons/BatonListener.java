package com.leir4iks.cookiepl.modules.policebatons;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BatonListener implements Listener {

    private final CookiePl plugin;
    private final ConcurrentHashMap<UUID, ArmorStand> activeSeats = new ConcurrentHashMap<>();

    public BatonListener(CookiePl plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player damager = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();
        ItemStack itemInHand = damager.getInventory().getItemInMainHand();

        if (itemInHand.getItemMeta() == null) {
            return;
        }

        PersistentDataContainer pdc = itemInHand.getItemMeta().getPersistentDataContainer();
        String batonType = pdc.get(BatonManager.getBatonTypeKey(plugin), PersistentDataType.STRING);

        if (batonType == null) {
            return;
        }

        switch (batonType) {
            case BatonManager.FBI_BATON_ID:
                applyFbiBatonEffect(damager, victim);
                break;
            case BatonManager.ELECTROSHOCK_BATON_ID:
                applyElectroshockBatonEffect(damager, victim);
                break;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanUpSeatFor(event.getPlayer());
    }

    private void applyFbiBatonEffect(Player damager, Player victim) {
        String path = "modules.police-batons.fbi-baton.";
        int darkAmp = plugin.getConfig().getInt(path + "effects.darkness.amplifier", 2);
        int darkDur = plugin.getConfig().getInt(path + "effects.darkness.duration-seconds", 5) * 20;
        int slowAmp = plugin.getConfig().getInt(path + "effects.slowness.amplifier", 2);
        int slowDur = plugin.getConfig().getInt(path + "effects.slowness.duration-seconds", 5) * 20;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darkDur, darkAmp - 1));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowDur, slowAmp - 1));

        String message = plugin.getConfig().getString(path + "message", "&6Player &e{player} &6was lightly stunned.");
        damager.sendMessage(formatColor(message.replace("{player}", victim.getName())));
    }

    private void applyElectroshockBatonEffect(Player damager, Player victim) {
        String path = "modules.police-batons.electroshock-baton.";
        int darkAmp = plugin.getConfig().getInt(path + "effects.darkness.amplifier", 10);
        int darkDur = plugin.getConfig().getInt(path + "effects.darkness.duration-seconds", 7) * 20;
        int slowAmp = plugin.getConfig().getInt(path + "effects.slowness.amplifier", 10);
        int slowDur = plugin.getConfig().getInt(path + "effects.slowness.duration-seconds", 7) * 20;
        long sitDur = plugin.getConfig().getLong(path + "sit-duration-seconds", 7) * 20;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darkDur, darkAmp - 1));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowDur, slowAmp - 1));

        if (!activeSeats.containsKey(victim.getUniqueId())) {
            forceSit(victim, sitDur);
        }

        String message = plugin.getConfig().getString(path + "message", "&6Player &e{player} &6was heavily stunned.");
        damager.sendMessage(formatColor(message.replace("{player}", victim.getName())));
    }

    private void forceSit(Player player, long durationTicks) {
        if (activeSeats.containsKey(player.getUniqueId())) {
            return;
        }
        ArmorStand seat = player.getWorld().spawn(player.getLocation(), ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setInvulnerable(true);
        });
        seat.addPassenger(player);
        activeSeats.put(player.getUniqueId(), seat);

        plugin.getFoliaLib().getScheduler().runAtEntityLater(seat, () -> {
            if (!seat.isDead()) {
                seat.remove();
            }
            activeSeats.remove(player.getUniqueId());
        }, durationTicks);
    }

    public void cleanUpSeatFor(Player player) {
        ArmorStand seat = activeSeats.remove(player.getUniqueId());
        if (seat != null && !seat.isDead()) {
            seat.remove();
        }
    }

    public void cleanUpAllSeats() {
        for (ArmorStand seat : activeSeats.values()) {
            if (seat != null && !seat.isDead()) {
                seat.remove();
            }
        }
        activeSeats.clear();
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}