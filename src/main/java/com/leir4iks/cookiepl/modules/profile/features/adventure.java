package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.modules.profile.ProfileStorage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

public class adventure implements Listener {

    private final ProfileStorage storage;

    public adventure(ProfileStorage storage) {
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (!isVisible(event.getPlayer())) {
            event.message(null);
        }
    }

    public boolean isVisible(Player player) {
        return storage.isAdvancementVisible(player.getUniqueId());
    }

    public boolean toggleVisible(Player player) {
        boolean newState = !isVisible(player);
        setVisible(player, newState);
        return newState;
    }

    public void setVisible(Player player, boolean visible) {
        storage.setAdvancementVisible(player.getUniqueId(), visible);
    }
}