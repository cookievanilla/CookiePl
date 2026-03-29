package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

public class adventure implements Listener {

    private final CookiePl plugin;

    public adventure(CookiePl plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (!isVisible(event.getPlayer())) {
            event.message(null);
        }
    }

    public boolean isVisible(Player player) {
        return plugin.getConfig().getBoolean(getPath(player), true);
    }

    public boolean toggle(Player player) {
        boolean newState = !isVisible(player);
        plugin.getConfig().set(getPath(player), newState);
        plugin.saveConfig();
        return newState;
    }

    private String getPath(Player player) {
        return "modules.profile.players." + player.getUniqueId() + ".show-advancements";
    }
}