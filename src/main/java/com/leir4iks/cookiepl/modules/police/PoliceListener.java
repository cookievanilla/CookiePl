package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class PoliceListener implements Listener {

    private final CookiePl plugin;
    private final PoliceManager manager;

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
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            if (manager.isCuffed(damager.getUniqueId()) || manager.isNocked(damager.getUniqueId())) {
                event.setCancelled(true);
                return;
            }

            if (event.getEntity() instanceof Player && isPoliceItem(damager.getInventory().getItemInMainHand(), PoliceManager.BATON_KEY)) {
                Player victim = (Player) event.getEntity();
                String permission = plugin.getConfig().getString("modules.police-system.permission");
                if (damager.hasPermission(permission)) {
                    manager.nockPlayer(victim, damager);
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
            String permission = plugin.getConfig().getString("modules.police-system.permission");
            if (policeman.hasPermission(permission)) {
                event.setCancelled(true);
                if (manager.isNocked(victim.getUniqueId())) {
                    manager.cuffPlayer(victim, policeman);
                } else if (manager.isCuffed(victim.getUniqueId())) {
                    manager.uncuffPlayer(victim.getUniqueId());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (manager.isCuffed(player.getUniqueId())) {
            manager.uncuffPlayer(player.getUniqueId());
        }
        if (manager.isNocked(player.getUniqueId())) {
            manager.unnockPlayer(player, true);
        }
    }

    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (manager.isCuffed(player.getUniqueId())) {
                manager.uncuffPlayer(player.getUniqueId());
            }
        } else if (event.getEntity() instanceof Chicken) {
            for (PoliceManager.CuffedData data : manager.getActiveCuffedData()) {
                if (data.getChickenUUID().equals(event.getEntity().getUniqueId())) {
                    Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());
                    if (policeman != null) {
                        manager.uncuffPlayer(policeman.getUniqueId());
                    }
                    break;
                }
            }
        }
    }
}