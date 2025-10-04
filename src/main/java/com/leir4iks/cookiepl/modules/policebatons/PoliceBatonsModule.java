package com.leir4iks.cookiepl.modules.policebatons;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ShapedRecipe;

public class PoliceBatonsModule implements IModule {
    private BatonListener listener;

    @Override
    public void enable(CookiePl plugin) {
        this.listener = new BatonListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        registerRecipes(plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    @Override
    public String getName() {
        return "PoliceBatons";
    }

    @Override
    public String getConfigKey() {
        return "police-batons";
    }

    private void registerRecipes(CookiePl plugin) {
        if (plugin.getConfig().getBoolean("modules.police-batons.fbi-baton.enabled", false)) {
            NamespacedKey fbiKey = new NamespacedKey(plugin, "fbi_baton");
            if (Bukkit.getRecipe(fbiKey) == null) {
                ShapedRecipe fbiRecipe = new ShapedRecipe(fbiKey, BatonManager.createFbiBaton(plugin));
                fbiRecipe.shape(" A ", " B ", " C ");
                fbiRecipe.setIngredient('B', Material.BRICK);
                fbiRecipe.setIngredient('C', Material.STICK);
                Bukkit.addRecipe(fbiRecipe);
            }
        }

        if (plugin.getConfig().getBoolean("modules.police-batons.electroshock-baton.enabled", false)) {
            NamespacedKey elKey = new NamespacedKey(plugin, "electroshock_baton");
            if (Bukkit.getRecipe(elKey) == null) {
                ShapedRecipe elRecipe = new ShapedRecipe(elKey, BatonManager.createElectroshockBaton(plugin));
                elRecipe.shape("D E", "FGH", " I ");
                elRecipe.setIngredient('D', Material.RAW_COPPER);
                elRecipe.setIngredient('E', Material.CHAIN);
                elRecipe.setIngredient('F', Material.CHAIN);
                elRecipe.setIngredient('G', Material.BRICK);
                elRecipe.setIngredient('H', Material.COPPER_INGOT);
                elRecipe.setIngredient('I', Material.STICK);
                Bukkit.addRecipe(elRecipe);
            }
        }
    }
}