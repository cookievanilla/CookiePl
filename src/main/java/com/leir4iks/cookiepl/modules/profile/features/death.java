package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.modules.profile.ProfileStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class death implements Listener {

    private final ProfileStorage storage;

    public death(ProfileStorage storage) {
        this.storage = storage;
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
        return storage.isDeathMessageVisible(player.getUniqueId());
    }

    public boolean toggleVisible(Player player) {
        boolean newState = !isVisible(player);
        setVisible(player, newState);
        return newState;
    }

    public void setVisible(Player player, boolean visible) {
        storage.setDeathMessageVisible(player.getUniqueId(), visible);
    }
}