package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.CookiePl;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class death implements Listener {

    private static final byte FALSE = 0;
    private final NamespacedKey deathVisibleKey;

    public death(CookiePl plugin) {
        this.deathVisibleKey = new NamespacedKey(plugin, "profile_show_death_messages");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (isVisible(event.getEntity())) {
            return;
        }

        Component deathMessage = event.deathMessage();
        if (deathMessage != null) {
            event.deathScreenMessageOverride(deathMessage);
        }

        event.deathMessage(null);
    }

    public boolean isVisible(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte value = pdc.get(deathVisibleKey, PersistentDataType.BYTE);
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
            pdc.remove(deathVisibleKey);
            return;
        }

        pdc.set(deathVisibleKey, PersistentDataType.BYTE, FALSE);
    }
}