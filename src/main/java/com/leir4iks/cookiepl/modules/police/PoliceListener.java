package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.policebatons.BatonManager;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PoliceListener implements Listener {

    private final CookiePl plugin;
    private final PoliceManager manager;
    private final Map<UUID, Long> interactCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ArmorStand> activeSeats = new ConcurrentHashMap<>();

    public PoliceListener(CookiePl plugin, PoliceManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private boolean isPoliceItem(ItemStack item, NamespacedKey key) {
        if (item == null || item.getItemMeta() == null) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (manager.isCuffed(event.getPlayer().getUniqueId()) || manager.isNocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHotbarScroll(PlayerItemHeldEvent event) {
        if (manager.isCuffed(event.getPlayer().getUniqueId()) || manager.isNocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (manager.isCuffed(event.getPlayer().getUniqueId()) || manager.isNocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player damager = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();
        ItemStack itemInHand = damager.getInventory().getItemInMainHand();

        if (manager.isCuffed(damager.getUniqueId()) || manager.isNocked(damager.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        String permission = plugin.getConfig().getString("modules.police-system.permission");
        if (!damager.hasPermission(permission)) {
            return;
        }

        if (isPoliceItem(itemInHand, PoliceManager.BATON_KEY)) {
            manager.nockPlayer(victim, damager);
            return;
        }

        if (itemInHand.getItemMeta() != null) {
            PersistentDataContainer pdc = itemInHand.getItemMeta().getPersistentDataContainer();
            String batonType = pdc.get(BatonManager.getBatonTypeKey(plugin), PersistentDataType.STRING);
            if (batonType != null) {
                manager.nockPlayer(victim, damager);
                switch (batonType) {
                    case BatonManager.FBI_BATON_ID:
                        applyFbiBatonEffect(damager, victim);
                        break;
                    case BatonManager.ELECTROSHOCK_BATON_ID:
                        applyElectroshockBatonEffect(damager, victim);
                        break;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) return;

        Player policeman = event.getPlayer();
        Player victim = (Player) event.getRightClicked();
        ItemStack item = policeman.getInventory().getItemInMainHand();

        if (isPoliceItem(item, PoliceManager.HANDCUFFS_KEY)) {
            long now = System.currentTimeMillis();
            long lastUse = interactCooldowns.getOrDefault(policeman.getUniqueId(), 0L);
            if (now - lastUse < 2000L) {
                event.setCancelled(true);
                return;
            }
            interactCooldowns.put(policeman.getUniqueId(), now);

            String permission = plugin.getConfig().getString("modules.police-system.permission");
            if (policeman.hasPermission(permission)) {
                event.setCancelled(true);
                if (manager.isNocked(victim.getUniqueId())) {
                    manager.cuffPlayer(victim, policeman);
                } else if (manager.isCuffed(victim.getUniqueId())) {
                    manager.uncuffPlayer(victim.getUniqueId(), "Manual uncuff by " + policeman.getName());
                } else {
                    String notStunnedMessage = plugin.getConfig().getString("modules.police-system.nock.not-stunned-message", "&cTarget player is not stunned.");
                    policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', notStunnedMessage));
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (manager.isCuffed(player.getUniqueId())) {
            manager.uncuffPlayer(player.getUniqueId(), "Player Quit");
        }
        if (manager.isNocked(player.getUniqueId())) {
            manager.unnockPlayer(player, true);
        }
        cleanUpSeatFor(player);
    }

    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (manager.isCuffed(player.getUniqueId())) {
                manager.uncuffPlayer(player.getUniqueId(), "Entered Portal");
            }
        }
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
            cleanUpSeatFor(player);
        }, durationTicks);
    }

    public void cleanUpSeatFor(Player player) {
        ArmorStand seat = activeSeats.remove(player.getUniqueId());
        if (seat != null && !seat.isDead()) {
            seat.remove();
        }
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}