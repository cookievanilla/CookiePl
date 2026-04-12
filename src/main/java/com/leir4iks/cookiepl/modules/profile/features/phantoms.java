package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.profile.ProfileStorage;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

public class phantoms implements Listener {

    private final CookiePl plugin;
    private final ProfileStorage storage;

    public phantoms(CookiePl plugin, ProfileStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }

        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        final double maxRange = plugin.getConfig().getDouble("modules.profile.phantoms.max-range", 32.0);
        if (maxRange <= 0.0) {
            return;
        }

        for (Player player : event.getLocation().getNearbyPlayers(maxRange)) {
            if (isPhantomSpawnDisabled(player)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public boolean isPhantomSpawnDisabled(Player player) {
        return storage.isPhantomSpawnDisabled(player.getUniqueId());
    }

    public boolean togglePhantomSpawnDisabled(Player player) {
        boolean newState = !isPhantomSpawnDisabled(player);
        setPhantomSpawnDisabled(player, newState);
        return newState;
    }

    public void setPhantomSpawnDisabled(Player player, boolean disabled) {
        storage.setPhantomSpawnDisabled(player.getUniqueId(), disabled);
    }
}
