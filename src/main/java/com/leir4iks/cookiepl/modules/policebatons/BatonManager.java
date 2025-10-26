package com.leir4iks.cookiepl.modules.policebatons;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public class BatonManager {

    public static final String FBI_BATON_ID = "fbi_normal";
    public static final String ELECTROSHOCK_BATON_ID = "fbi_electroshock";

    public static NamespacedKey getBatonTypeKey(CookiePl plugin) {
        return new NamespacedKey(plugin, "baton_type");
    }

    public static ItemStack createFbiBaton(CookiePl plugin) {
        ItemStack item = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("modules.police-batons.fbi-baton.display-name", "&9FBI Baton");
        int modelData = plugin.getConfig().getInt("modules.police-batons.fbi-baton.custom-model-data", 101);

        meta.setDisplayName(formatColor(name));
        meta.setCustomModelData(modelData);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(getBatonTypeKey(plugin), PersistentDataType.STRING, FBI_BATON_ID);

        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack createElectroshockBaton(CookiePl plugin) {
        ItemStack item = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("modules.police-batons.electroshock-baton.display-name", "&9FBI Electroshock Baton");
        int modelData = plugin.getConfig().getInt("modules.police-batons.electroshock-baton.custom-model-data", 102);

        meta.setDisplayName(formatColor(name));
        meta.setCustomModelData(modelData);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(getBatonTypeKey(plugin), PersistentDataType.STRING, ELECTROSHOCK_BATON_ID);

        item.setItemMeta(meta);
        return item;
    }

    @NotNull
    private static String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}