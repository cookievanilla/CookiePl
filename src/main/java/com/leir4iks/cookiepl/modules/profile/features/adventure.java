package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class adventure implements Listener {

    private static final byte FALSE = 0;

    private final NamespacedKey advancementVisibleKey;

    public adventure(CookiePl plugin) {
        this.advancementVisibleKey = new NamespacedKey(plugin, "profile_show_advancements");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (!isVisible(event.getPlayer())) {
            event.message(null);
        }
    }

    public boolean isVisible(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte value = pdc.get(advancementVisibleKey, PersistentDataType.BYTE);
        return value == null || value != FALSE;
    }

    public boolean toggleVisible(Player player) {
        boolean newState = !isVisible(player);
        setVisible(player, newState);
        return newState;
    }

    public void setVisible(Player player, boolean visible) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();

        if (visible) {
            pdc.remove(advancementVisibleKey);
            return;
        }

        pdc.set(advancementVisibleKey, PersistentDataType.BYTE, FALSE);
    }
}