package com.leir4iks.cookiepl.modules.stones;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StoneManager {

    private final CookiePl plugin;
    private final LogManager logManager;
    private final ConcurrentHashMap<UUID, ActiveStone> activeStones = new ConcurrentHashMap<>();
    public final NamespacedKey stoneItemKey;
    public final NamespacedKey stoneEntityKey;

    public StoneManager(CookiePl plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
        this.stoneItemKey = new NamespacedKey(plugin, "stone_item");
        this.stoneEntityKey = new NamespacedKey(plugin, "stone_entity");
    }

    public ItemStack createStoneItem() {
        ItemStack item = new ItemStack(Material.STONE_BUTTON);
        ItemMeta meta = item.getItemMeta();
        String name = plugin.getConfig().getString("modules.throwable-stones.stone-item.display-name", "&6Stone");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("modules.throwable-stones.stone-item.lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(stoneItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public void throwStone(Player thrower, Location location, Vector velocity) {
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setSmall(true);
            as.setMarker(true);
            as.getPersistentDataContainer().set(stoneEntityKey, PersistentDataType.BYTE, (byte) 1);
            as.getEquipment().setHelmet(new ItemStack(Material.STONE_BUTTON), true);
            as.addEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
        });

        Slime hitbox = location.getWorld().spawn(location, Slime.class, slime -> {
            slime.setSize(1);
            slime.setSilent(true);
            slime.setAI(false);
            slime.setCollidable(false);
            slime.setInvisible(true);
            slime.setInvulnerable(true);
            slime.getPersistentDataContainer().set(stoneEntityKey, PersistentDataType.BYTE, (byte) 1);
        });

        stand.addPassenger(hitbox);
        ActiveStone activeStone = new ActiveStone(plugin, this, thrower, stand, velocity);
        activeStones.put(stand.getUniqueId(), activeStone);

        logManager.debug("Player " + thrower.getName() + " threw a stone with velocity " + velocity.toString());
    }

    public void removeStone(ArmorStand stand, boolean dropItem) {
        if (stand == null || stand.isDead()) return;

        activeStones.remove(stand.getUniqueId());

        if (!stand.getPassengers().isEmpty() && stand.getPassengers().get(0) instanceof Slime) {
            stand.getPassengers().get(0).remove();
        }
        if (dropItem) {
            stand.getWorld().dropItemNaturally(stand.getLocation(), createStoneItem());
        }
        stand.remove();
    }

    public void cleanupAllStones() {
        if (plugin.getServer().isStopping()) {
            activeStones.clear();
            logManager.info("Server is stopping, skipping entity removal for stones.");
            return;
        }
        for (ActiveStone stone : activeStones.values()) {
            removeStone(stone.getStoneEntity(), true);
        }
        activeStones.clear();
        logManager.info("Cleaned up all active stones.");
    }

    public ConcurrentHashMap<UUID, ActiveStone> getActiveStones() {
        return activeStones;
    }
}