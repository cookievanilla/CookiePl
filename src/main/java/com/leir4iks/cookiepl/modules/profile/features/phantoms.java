package com.leir4iks.cookiepl.modules.profile.features;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class phantoms implements Listener {

    private static final byte TRUE = 1;

    private final CookiePl plugin;
    private final NamespacedKey phantomDisabledKey;

    public phantoms(CookiePl plugin) {
        this.plugin = plugin;
        this.phantomDisabledKey = new NamespacedKey(plugin, "profile_disable_phantom_spawn");
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
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte value = pdc.get(phantomDisabledKey, PersistentDataType.BYTE);
        return value != null && value == TRUE;
    }

    public boolean togglePhantomSpawnDisabled(Player player) {
        boolean newState = !isPhantomSpawnDisabled(player);
        setPhantomSpawnDisabled(player, newState);
        return newState;
    }

    public void setPhantomSpawnDisabled(Player player, boolean disabled) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();

        if (!disabled) {
            pdc.remove(phantomDisabledKey);
            return;
        }

        pdc.set(phantomDisabledKey, PersistentDataType.BYTE, TRUE);
    }
}
