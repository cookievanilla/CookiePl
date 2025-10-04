package com.leir4iks.cookiepl.modules.wheremyframe.util;

import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class ItemDamageUtil {

    private static final Map<Integer, Integer> UNBREAKING_CHANCES = Map.of(1, 25, 2, 36, 3, 43);

    private ItemDamageUtil() {}

    public static void damageItemInMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();

        if (!(meta instanceof Damageable)) {
            return;
        }

        if (item.hasItemMeta() && item.getItemMeta().hasEnchant(Enchantment.UNBREAKING)) {
            int level = item.getEnchantmentLevel(Enchantment.UNBREAKING);
            int chanceToResist = UNBREAKING_CHANCES.getOrDefault(level, 0);
            if (ThreadLocalRandom.current().nextInt(100) < chanceToResist) {
                return;
            }
        }

        Damageable damageableMeta = (Damageable) meta;
        damageableMeta.setDamage(damageableMeta.getDamage() + 1);

        if (damageableMeta.getDamage() < item.getType().getMaxDurability()) {
            item.setItemMeta(damageableMeta);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            item.setAmount(0);
        }
    }
}