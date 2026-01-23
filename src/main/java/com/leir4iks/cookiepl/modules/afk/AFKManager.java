package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AFKManager implements Listener {

    private final CookiePl plugin;
    private static final String AFK_META = "afk";

    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, TextDisplay> activeIndicators = new ConcurrentHashMap<>();
    private final WrappedTask afkCheckTask;

    private final long afkTimeMillis;
    private final boolean indicatorEnabled;
    private final String indicatorText;
    private final double indicatorYOffset;
    private final boolean freezePlayer;
    private final boolean invulnerabilityEnabled;
    private final boolean noHungerEnabled;
    private final boolean sleepIgnoreEnabled;

    private final String msgEnabled;
    private final String msgDisabled;

    public AFKManager(CookiePl plugin, AFKModule module) {
        this.plugin = plugin;
        String configKey = "modules." + module.getConfigKey();

        this.afkTimeMillis = plugin.getConfig().getLong(configKey + ".afk-time-seconds", 300) * 1000;
        this.indicatorEnabled = plugin.getConfig().getBoolean(configKey + ".indicator.enabled", true);
        this.indicatorText = formatColor(plugin.getConfig().getString(configKey + ".indicator.text", "&e⌛"));
        this.indicatorYOffset = plugin.getConfig().getDouble(configKey + ".indicator.y-offset", 0.5);
        this.freezePlayer = plugin.getConfig().getBoolean(configKey + ".freeze-player", false);
        this.invulnerabilityEnabled = plugin.getConfig().getBoolean(configKey + ".invulnerability.enabled", true);
        this.noHungerEnabled = plugin.getConfig().getBoolean(configKey + ".no-hunger.enabled", true);
        this.sleepIgnoreEnabled = plugin.getConfig().getBoolean(configKey + ".sleep-ignore.enabled", true);

        this.msgEnabled = formatColor(plugin.getConfig().getString(configKey + ".messages.afk-enabled", "&7AFK enabled."));
        this.msgDisabled = formatColor(plugin.getConfig().getString(configKey + ".messages.afk-disabled", "&7AFK disabled."));

        cleanupStaleIndicators();
        this.afkCheckTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::checkAutoAFK, 100L, 100L);
    }

    public void setAfk(Player player, boolean afk) {
        plugin.getFoliaLib().getScheduler().runAtEntity(player, (task) -> {
            if (player == null || !player.isOnline()) return;

            boolean isCurrentlyAfk = isAfk(player);

            if (afk && !isCurrentlyAfk) {
                player.setMetadata(AFK_META, new FixedMetadataValue(plugin, true));
                player.sendMessage(msgEnabled);

                if (invulnerabilityEnabled) player.setInvulnerable(true);
                if (sleepIgnoreEnabled) player.setSleepingIgnored(true);

                if (indicatorEnabled) {
                    spawnIndicator(player);
                }
            } else if (!afk && isCurrentlyAfk) {
                player.removeMetadata(AFK_META, plugin);
                player.sendMessage(msgDisabled);

                if (invulnerabilityEnabled && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                    player.setInvulnerable(false);
                }
                if (sleepIgnoreEnabled) player.setSleepingIgnored(false);

                removeIndicator(player);
                updateActivity(player);
            }
        });
    }

    public boolean isAfk(Player player) {
        return player != null && player.hasMetadata(AFK_META) && !player.getMetadata(AFK_META).isEmpty() && player.getMetadata(AFK_META).get(0).asBoolean();
    }

    public boolean isAfk(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        return isAfk(player);
    }

    private void checkAutoAFK() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            if (isAfk(player)) continue;

            long lastAction = lastActivity.getOrDefault(player.getUniqueId(), now);
            if (now - lastAction > afkTimeMillis) {
                setAfk(player, true);
            }
        }
    }

    private void updateActivity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void spawnIndicator(Player player) {
        removeIndicator(player);

        TextDisplay indicator = player.getWorld().spawn(player.getLocation(), TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(AFKModule.AFK_INDICATOR_KEY, PersistentDataType.BYTE, (byte) 1);
            display.setPersistent(false);
            display.setText(indicatorText);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setSeeThrough(true);

            Transformation transformation = display.getTransformation();
            transformation.getTranslation().add(0f, (float) this.indicatorYOffset, 0f);
            display.setTransformation(transformation);

            player.addPassenger(display);
        });

        activeIndicators.put(player.getUniqueId(), indicator);
    }

    private void removeIndicator(Player player) {
        TextDisplay display = activeIndicators.remove(player.getUniqueId());

        if (display != null && display.isValid()) {
            if (player.getPassengers().contains(display)) {
                player.removePassenger(display);
            }
            display.remove();
        }

        for (Entity passenger : player.getPassengers()) {
            if (passenger instanceof TextDisplay && passenger.getPersistentDataContainer().has(AFKModule.AFK_INDICATOR_KEY, PersistentDataType.BYTE)) {
                passenger.remove();
            }
        }
    }

    private void cleanupStaleIndicators() {
        plugin.getServer().getWorlds().forEach(world -> {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getPersistentDataContainer().has(AFKModule.AFK_INDICATOR_KEY, PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        });
    }

    public void shutdown() {
        if (afkCheckTask != null) afkCheckTask.cancel();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isAfk(player)) {
                player.removeMetadata(AFK_META, plugin);
                removeIndicator(player);
            }
        }
        activeIndicators.clear();
        lastActivity.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isAfk(player)) {
            if (freezePlayer) {
                if (event.hasChangedPosition()) {
                    event.setCancelled(true);
                }
            } else {
                if (event.hasChangedPosition() || event.hasChangedOrientation()) {
                    setAfk(player, false);
                }
            }
        } else {
            if (event.hasChangedPosition() || event.hasChangedOrientation()) {
                updateActivity(player);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isAfk(event.getPlayer())) {
            if (freezePlayer) {
                event.setCancelled(true);
            } else {
                setAfk(event.getPlayer(), false);
            }
        } else {
            updateActivity(event.getPlayer());
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isAfk(player) && invulnerabilityEnabled) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            if (isAfk(damager)) {
                if (freezePlayer) {
                    event.setCancelled(true);
                } else {
                    setAfk(damager, false);
                }
            } else {
                updateActivity(damager);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (isAfk(event.getPlayer())) {
            setAfk(event.getPlayer(), false);
        }
        updateActivity(event.getPlayer());
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().toLowerCase().startsWith("/afk")) {
            if (isAfk(event.getPlayer())) {
                setAfk(event.getPlayer(), false);
            }
            updateActivity(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateActivity(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
        removeIndicator(event.getPlayer());
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isAfk(player) && noHungerEnabled) {
            event.setCancelled(true);
        }
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}