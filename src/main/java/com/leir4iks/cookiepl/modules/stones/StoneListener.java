package com.leir4iks.cookiepl.modules.stones;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public class StoneListener implements Listener {

    private final CookiePl plugin;
    private final StoneManager manager;

    public StoneListener(CookiePl plugin, StoneManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getItemMeta() == null || !item.getItemMeta().getPersistentDataContainer().has(manager.stoneItemKey, PersistentDataType.BYTE)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.getInventory().getItemInMainHand().isSimilar(item)) {
            player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);
        } else {
            player.getInventory().removeItem(manager.createStoneItem());
        }

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.0f);

        double power = plugin.getConfig().getDouble("modules.throwable-stones.throw-power.base", 1.0);
        if (player.isSneaking()) {
            power = plugin.getConfig().getDouble("modules.throwable-stones.throw-power.sneaking", 0.4);
        } else if (player.isSprinting()) {
            power = !player.isOnGround()
                    ? plugin.getConfig().getDouble("modules.throwable-stones.throw-power.sprinting-jumping", 1.5)
                    : plugin.getConfig().getDouble("modules.throwable-stones.throw-power.sprinting", 1.3);
        } else if (!player.isOnGround()) {
            power = plugin.getConfig().getDouble("modules.throwable-stones.throw-power.jumping", 1.3);
        }

        Vector velocity = player.getEyeLocation().getDirection().multiply(power);
        manager.throwStone(player, player.getEyeLocation(), velocity);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getVehicle() instanceof ArmorStand) {
            ArmorStand stand = (ArmorStand) event.getRightClicked().getVehicle();
            if (stand.getPersistentDataContainer().has(manager.stoneEntityKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                manager.removeStone(stand, true);
                event.getPlayer().getWorld().playSound(event.getPlayer().getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.0f);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction().isRightClick() && event.getPlayer().isSneaking() && event.getClickedBlock() != null) {
            if (event.getClickedBlock().getType() == Material.STONE_BUTTON) {
                event.setCancelled(true);
                event.getClickedBlock().setType(Material.AIR);
                event.getPlayer().getInventory().addItem(manager.createStoneItem());
                event.getPlayer().getWorld().playSound(event.getPlayer().getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.0f);
            }
        }
    }
}