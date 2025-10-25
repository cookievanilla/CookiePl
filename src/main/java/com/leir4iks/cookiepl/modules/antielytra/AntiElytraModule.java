package com.leir4iks.cookiepl.modules.antielytra;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class AntiElytraModule implements IModule, Listener {

    private CookiePl plugin;
    private String mode;
    private Set<String> worlds;
    private String bypassPermission;
    private String message;
    private Sound sound;
    private Particle particle;

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable(CookiePl plugin) {
        HandlerList.unregisterAll(this);
        if (worlds != null) {
            worlds.clear();
        }
        this.plugin = null;
        this.mode = null;
        this.worlds = null;
        this.bypassPermission = null;
        this.message = null;
        this.sound = null;
        this.particle = null;
    }

    private void loadConfig() {
        String configKey = getConfigKey();
        this.mode = plugin.getConfig().getString("modules." + configKey + ".mode", "blacklist");
        this.worlds = new HashSet<>(plugin.getConfig().getStringList("modules." + configKey + ".worlds"));
        this.bypassPermission = plugin.getConfig().getString("modules." + configKey + ".bypass-permission", "cookiepl.antielytra.bypass");

        this.message = plugin.getConfig().getString("modules." + configKey + ".feedback.message", "");
        try {
            String soundName = plugin.getConfig().getString("modules." + configKey + ".feedback.sound", "NONE").toUpperCase();
            this.sound = soundName.equals("NONE") ? null : Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            this.sound = null;
            plugin.getLogManager().warn("Invalid sound name in anti-elytra module config.");
        }
        try {
            String particleName = plugin.getConfig().getString("modules." + configKey + ".feedback.particle", "NONE").toUpperCase();
            this.particle = particleName.equals("NONE") ? null : Particle.valueOf(particleName);
        } catch (IllegalArgumentException e) {
            this.particle = null;
            plugin.getLogManager().warn("Invalid particle name in anti-elytra module config.");
        }
    }

    @EventHandler
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player) || !event.isGliding()) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (isRestricted(player)) {
            event.setCancelled(true);
            playFeedback(player);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player.isGliding() && isRestricted(player)) {
            player.setGliding(false);
            playFeedback(player);
        }
    }

    private boolean isRestricted(Player player) {
        if (bypassPermission != null && !bypassPermission.isEmpty() && player.hasPermission(bypassPermission)) {
            return false;
        }

        String worldName = player.getWorld().getName();
        boolean worldInList = worlds.contains(worldName);

        if ("blacklist".equalsIgnoreCase(mode)) {
            return worldInList;
        } else if ("whitelist".equalsIgnoreCase(mode)) {
            return !worldInList;
        }
        return false;
    }

    private void playFeedback(Player player) {
        if (message != null && !message.isEmpty()) {
            player.sendMessage(formatColor(message));
        }
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
        if (particle != null) {
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0);
        }
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public String getName() {
        return "AntiElytra";
    }

    @Override
    public String getConfigKey() {
        return "anti-elytra";
    }
}