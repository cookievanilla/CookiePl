package com.leir4iks.cookiepl.modules.profile.features;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jpenilla.squaremap.api.PlayerManager;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;

import java.util.UUID;

public class squaremap {

    public boolean isAvailable() {
        return api() != null;
    }

    public VisibilityState getVisibilityState(Player player) {
        Squaremap api = api();
        if (api == null) {
            return VisibilityState.UNAVAILABLE;
        }

        PlayerManager playerManager = api.playerManager();
        UUID uuid = player.getUniqueId();

        return playerManager.hidden(uuid)
                ? VisibilityState.HIDDEN
                : VisibilityState.SHOWN;
    }

    public ToggleResult toggleVisible(Player player) {
        Squaremap api = api();
        if (api == null) {
            return ToggleResult.UNAVAILABLE;
        }

        PlayerManager playerManager = api.playerManager();
        UUID uuid = player.getUniqueId();

        if (playerManager.hidden(uuid)) {
            playerManager.show(uuid, true);
            return ToggleResult.SHOWN;
        }

        playerManager.hide(uuid, true);
        return ToggleResult.HIDDEN;
    }

    private Squaremap api() {
        Squaremap service = Bukkit.getServicesManager().load(Squaremap.class);
        if (service != null) {
            return service;
        }

        try {
            return SquaremapProvider.get();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    public enum VisibilityState {
        SHOWN,
        HIDDEN,
        UNAVAILABLE
    }

    public enum ToggleResult {
        SHOWN,
        HIDDEN,
        UNAVAILABLE
    }
}