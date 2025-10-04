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
    private final NamespacedKey handcuffsKey;
    private final NamespacedKey batonKey;

    public PoliceListener(CookiePl plugin, PoliceManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.handcuffsKey = new NamespacedKey(plugin, "handcuffs_item");
        this.batonKey = new NamespacedKey(plugin, "baton_item");
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

            if (event.getEntity() instanceof Player && isPoliceItem(damager.getInventory().getItemInMainHand(), batonKey)) {
                Player victim = (Player) event.getEntity();