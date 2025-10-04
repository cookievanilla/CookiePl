package com.leir4iks.cookiepl.modules.wheremyframe;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.wheremyframe.util.ItemDamageUtil;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

public class WhereMyFrameListener implements Listener {

    private final CookiePl plugin;

    public WhereMyFrameListener(CookiePl plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }

        Player player = event.getPlayer();
        ItemFrame itemFrame = (ItemFrame) event.getRightClicked();

        if (player.isSneaking()
                && player.getInventory().getItemInMainHand().getType() == Material.SHEARS
                && itemFrame.isVisible()
                && itemFrame.getItem().getType() != Material.AIR) {

            event.setCancelled(true);
            itemFrame.setVisible(false);
            itemFrame.getWorld().playSound(itemFrame.getLocation(), Sound.ITEM_AXE_STRIP, 1.0f, 1.0f);

            boolean damageShears = plugin.getConfig().getBoolean("modules.where-my-frame.damagingShears");
            if (damageShears && player.getGameMode() != GameMode.CREATIVE) {
                ItemDamageUtil.damageItemInMainHand(player);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }

        ItemFrame itemFrame = (ItemFrame) event.getEntity();
        boolean allowEmpty = plugin.getConfig().getBoolean("modules.where-my-frame.allowEmptyItemFrame");

        if (!itemFrame.isVisible() && itemFrame.getItem().getType() != Material.AIR && !allowEmpty) {
            itemFrame.getWorld().playSound(itemFrame.getLocation(), Sound.ENTITY_ITEM_FRAME_BREAK, 1.0f, 1.0f);

            if (event.getDamager() instanceof Player && ((Player) event.getDamager()).getGameMode() != GameMode.CREATIVE) {
                Material frameMaterial = (itemFrame instanceof GlowItemFrame) ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME;
                itemFrame.getWorld().dropItemNaturally(itemFrame.getLocation(), itemFrame.getItem());
                itemFrame.getWorld().dropItemNaturally(itemFrame.getLocation(), new ItemStack(frameMaterial));
            }

            itemFrame.setItem(null);
            itemFrame.remove();
        }
    }
}