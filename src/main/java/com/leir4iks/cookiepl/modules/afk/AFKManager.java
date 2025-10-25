package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AFKManager implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, AFKData> afkPlayers = new ConcurrentHashMap<>();
    private final Set<Location> afkProtectedBlocks = new HashSet<>();
    private final WrappedTask afkCheckTask;

    private final long afkTimeMillis;
    private final boolean indicatorEnabled;
    private final String indicatorText;
    private final boolean immobilityEnabled;
    private final boolean preventBlockBreak;
    private final boolean invulnerabilityEnabled;
    private final boolean noHungerEnabled;
    private final boolean sleepIgnoreEnabled;

    public AFKManager(CookiePl plugin, AFKModule module) {
        this.plugin = plugin;
        String configKey = "modules." + module.getConfigKey();

        this.afkTimeMillis = plugin.getConfig().getLong(configKey + ".afk-time-seconds", 300) * 1000;
        this.indicatorEnabled = plugin.getConfig().getBoolean(configKey + ".indicator.enabled", true);
        this.indicatorText = formatColor(plugin.getConfig().getString(configKey + ".indicator.text", "&e⌛"));
        this.immobilityEnabled = plugin.getConfig().getBoolean(configKey + ".immobility.enabled", true);
        this.preventBlockBreak = plugin.getConfig().getBoolean(configKey + ".immobility.prevent-block-break", true);
        this.invulnerabilityEnabled = plugin.getConfig().getBoolean(configKey + ".invulnerability.enabled", true);
        this.noHungerEnabled = plugin.getConfig().getBoolean(configKey + ".no-hunger.enabled", true);
        this.sleepIgnoreEnabled = plugin.getConfig().getBoolean(configKey + ".sleep-ignore.enabled", true);

        this.afkCheckTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::checkAFK, 100L, 100L);
    }

    private void checkAFK() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isAfk(player.getUniqueId())) continue;

            long lastAction = lastActivity.getOrDefault(player.getUniqueId(), now);
            if (now - lastAction > afkTimeMillis) {
                plugin.getFoliaLib().getScheduler().runAtEntity(player, (task) -> setAfk(player));
            }
        }
    }

    public void setAfk(Player player) {
        if (isAfk(player.getUniqueId())) return;

        Location afkLocation = player.getLocation();
        TextDisplay indicator = null;
        AFKData afkData = new AFKData(afkLocation);

        if (indicatorEnabled) {
            indicator = spawnIndicator(player);
            afkData.setIndicator(indicator);
        }
        if (immobilityEnabled) {
            startImmobilityTask(player, afkData);
        }
        if (preventBlockBreak) {
            afkProtectedBlocks.add(afkLocation.clone().subtract(0, 1, 0).getBlock().getLocation());
        }
        if (invulnerabilityEnabled) {
            player.setInvulnerable(true);
        }
        if (sleepIgnoreEnabled) {
            player.setSleepingIgnored(true);
        }

        afkPlayers.put(player.getUniqueId(), afkData);
    }

    public void unsetAfk(Player player) {
        AFKData data = afkPlayers.remove(player.getUniqueId());
        if (data == null) return;

        data.cancel();

        if (data.getIndicator() != null) {
            data.getIndicator().remove();
        }
        if (preventBlockBreak) {
            afkProtectedBlocks.remove(data.getOriginalLocation().clone().subtract(0, 1, 0).getBlock().getLocation());
        }
        if (invulnerabilityEnabled) {
            player.setInvulnerable(false);
        }
        if (sleepIgnoreEnabled) {
            player.setSleepingIgnored(false);
        }
    }

    private TextDisplay spawnIndicator(Player player) {
        Location loc = player.getLocation().add(0, player.getHeight() + 0.25, 0);
        return player.getWorld().spawn(loc, TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(AFKModule.AFK_INDICATOR_KEY, PersistentDataType.BYTE, (byte) 1);
            display.setText(indicatorText);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setSeeThrough(true);
            player.addPassenger(display);
        });
    }

    private void startImmobilityTask(Player player, AFKData afkData) {
        plugin.getFoliaLib().getScheduler().runAtEntityTimer(player, task -> {
            if (afkData.isCancelled() || !player.isOnline() || !isAfk(player.getUniqueId())) {
                task.cancel();
                return;
            }
            if (player.getLocation().distanceSquared(afkData.getOriginalLocation()) > 0.01) {
                Location currentLoc = player.getLocation();
                Location teleportTarget = afkData.getOriginalLocation().clone();
                teleportTarget.setYaw(currentLoc.getYaw());
                teleportTarget.setPitch(currentLoc.getPitch());
                player.teleport(teleportTarget);
            }
        }, 1L, 1L);
    }

    public boolean isAfk(UUID uuid) {
        return afkPlayers.containsKey(uuid);
    }

    public void shutdown() {
        if (afkCheckTask != null && !afkCheckTask.isCancelled()) {
            afkCheckTask.cancel();
        }
        for (UUID uuid : afkPlayers.keySet()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                unsetAfk(player);
            }
        }
        afkPlayers.clear();
        lastActivity.clear();
        afkProtectedBlocks.clear();
    }

    private void updateActivity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player.getUniqueId())) {
            unsetAfk(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) {
            updateActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        plugin.getFoliaLib().getScheduler().runAtEntity(event.getPlayer(), task -> updateActivity(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        updateActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        updateActivity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        unsetAfk(player);
        lastActivity.remove(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isAfk(event.getEntity().getUniqueId())) {
            if (invulnerabilityEnabled) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (isAfk(event.getEntity().getUniqueId())) {
            if (noHungerEnabled) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!preventBlockBreak || afkProtectedBlocks.isEmpty()) return;

        if (afkProtectedBlocks.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class AFKData {
        private final Location originalLocation;
        private TextDisplay indicator;
        private boolean isCancelled = false;

        AFKData(Location originalLocation) {
            this.originalLocation = originalLocation;
        }

        public Location getOriginalLocation() {
            return originalLocation;
        }

        public TextDisplay getIndicator() {
            return indicator;
        }

        public void setIndicator(TextDisplay indicator) {
            this.indicator = indicator;
        }

        public boolean isCancelled() {
            return isCancelled;
        }

        public void cancel() {
            this.isCancelled = true;
        }
    }
}