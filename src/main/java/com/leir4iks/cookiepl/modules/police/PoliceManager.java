package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PoliceManager {

    private final CookiePl plugin;
    private final LogManager logManager;
    private final Map<UUID, CuffedData> cuffedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NockedData> nockedPlayers = new ConcurrentHashMap<>();
    private final WrappedTask watchdogTask;
    private final WrappedTask cuffUpdateTask;

    public static NamespacedKey HANDCUFFS_KEY;
    public static NamespacedKey BATON_KEY;

    public PoliceManager(CookiePl plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
        HANDCUFFS_KEY = new NamespacedKey(plugin, "handcuffs_item");
        BATON_KEY = new NamespacedKey(plugin, "baton_item");
        this.watchdogTask = startWatchdogTask();
        this.cuffUpdateTask = startCuffUpdateTask();
    }

    public boolean isCuffed(UUID uuid) {
        return cuffedPlayers.containsKey(uuid);
    }

    public boolean isNocked(UUID uuid) {
        return nockedPlayers.containsKey(uuid);
    }

    public void nockPlayer(Player victim, Player policeman) {
        if (isCuffed(victim.getUniqueId()) || isNocked(victim.getUniqueId())) return;

        int duration = plugin.getConfig().getInt("modules.police-system.nock.duration-seconds", 10);
        WrappedTask task = plugin.getFoliaLib().getScheduler().runAtEntityLater(victim, () -> unnockPlayer(victim, false), duration * 20L);

        nockedPlayers.put(victim.getUniqueId(), new NockedData(policeman.getUniqueId(), task));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, duration * 20, 0, true, false));
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.5f, 1.0f);

        String message = plugin.getConfig().getString("modules.police-system.nock.message", "&aYou have stunned player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " nocked " + victim.getName() + " for " + duration + " seconds.");
    }

    public void unnockPlayer(Player victim, boolean force) {
        NockedData data = nockedPlayers.remove(victim.getUniqueId());
        if (data != null) {
            if (!force && data.getExpireTask() != null && !data.getExpireTask().isCancelled()) {
                data.getExpireTask().cancel();
            }
            logManager.info("Player " + victim.getName() + " is no longer nocked.");
        }
    }

    public void cuffPlayer(Player victim, Player policeman) {
        if (isCuffed(victim.getUniqueId())) return;
        unnockPlayer(victim, true);

        ArmorStand anchor = victim.getWorld().spawn(victim.getLocation(), ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setSilent(true);
        });
        anchor.setLeashHolder(policeman);

        cuffedPlayers.put(victim.getUniqueId(), new CuffedData(policeman.getUniqueId(), anchor.getUniqueId()));

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.0f);
        executeEmoteCommand("start-cuff", victim.getName());

        String message = plugin.getConfig().getString("modules.police-system.cuff.message", "&aYou have cuffed player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " cuffed " + victim.getName());
    }

    public void uncuffPlayer(UUID victimUUID, String reason) {
        CuffedData data = cuffedPlayers.remove(victimUUID);
        if (data == null) return;

        Entity anchor = Bukkit.getEntity(data.getAnchorUUID());
        if (anchor != null) {
            anchor.remove();
        }

        Player victim = Bukkit.getPlayer(victimUUID);
        if (victim != null) {
            victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.0f);
            executeEmoteCommand("stop-cuff", victim.getName());

            Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());
            if (policeman != null) {
                String message = plugin.getConfig().getString("modules.police-system.cuff.uncuff-message", "&ePlayer {player} has been released!");
                policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
            }
        }
        logManager.info("Player " + (victim != null ? victim.getName() : victimUUID) + " has been uncuffed. Reason: " + reason);
    }

    private void executeEmoteCommand(String type, String playerName) {
        String command = plugin.getConfig().getString("modules.police-system.emote-commands." + type, "");
        if (command != null && !command.isEmpty()) {
            String finalCommand = command.replace("{player}", playerName);
            plugin.getFoliaLib().getScheduler().runNextTick((task) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand));
        }
    }

    public void cleanUpAll() {
        if (watchdogTask != null) watchdogTask.cancel();
        if (cuffUpdateTask != null) cuffUpdateTask.cancel();

        for (UUID uuid : cuffedPlayers.keySet()) {
            uncuffPlayer(uuid, "Plugin Disable");
        }
        cuffedPlayers.clear();
        nockedPlayers.clear();
        logManager.info("Cleaned up all police data.");
    }

    private WrappedTask startCuffUpdateTask() {
        final double minDistance = plugin.getConfig().getDouble("modules.police-system.physics.min-distance", 2.0);
        final double maxDistance = plugin.getConfig().getDouble("modules.police-system.physics.max-distance", 4.0);
        final double pullForce = plugin.getConfig().getDouble("modules.police-system.physics.pull-force", 0.3);
        final boolean preventWallDragging = plugin.getConfig().getBoolean("modules.police-system.physics.prevent-wall-dragging", true);

        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            if (cuffedPlayers.isEmpty()) return;

            for (Map.Entry<UUID, CuffedData> entry : cuffedPlayers.entrySet()) {
                Player victim = Bukkit.getPlayer(entry.getKey());
                Player policeman = Bukkit.getPlayer(entry.getValue().getPolicemanUUID());
                Entity anchor = Bukkit.getEntity(entry.getValue().getAnchorUUID());

                if (victim == null || policeman == null || anchor == null || !victim.isOnline() || !policeman.isOnline() || anchor.isDead()) {
                    continue;
                }

                plugin.getFoliaLib().getScheduler().runAtEntity(victim, task -> {
                    Location anchorLoc = anchor.getLocation();
                    Location policeLoc = policeman.getLocation();
                    if (anchorLoc.getWorld() != policeLoc.getWorld()) return;

                    plugin.getFoliaLib().getScheduler().teleportAsync(anchor, policeLoc);

                    Location victimLoc = victim.getLocation();
                    double distance = victimLoc.distance(anchorLoc);

                    if (distance < minDistance) {
                        if (victim.getVelocity().lengthSquared() > 0.01) {
                            victim.setVelocity(new Vector(0, victim.getVelocity().getY(), 0));
                        }
                        return;
                    }

                    if (preventWallDragging) {
                        RayTraceResult rayTrace = victim.getWorld().rayTraceBlocks(victim.getEyeLocation(),
                                anchorLoc.toVector().subtract(victim.getEyeLocation().toVector()).normalize(), distance);
                        if (rayTrace != null && rayTrace.getHitBlock() != null) {
                            return;
                        }
                    }

                    double forceMultiplier = Math.min(1.0, (distance - minDistance) / (maxDistance - minDistance));
                    Vector direction = anchorLoc.toVector().subtract(victimLoc.toVector()).normalize();
                    Vector pullVector = direction.multiply(pullForce * forceMultiplier);
                    victim.setVelocity(victim.getVelocity().add(pullVector));
                });
            }
        }, 0L, 1L);
    }

    private WrappedTask startWatchdogTask() {
        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            if (cuffedPlayers.isEmpty()) return;
            double maxDist = plugin.getConfig().getDouble("modules.police-system.max-cuff-distance", 20.0);

            for (Map.Entry<UUID, CuffedData> entry : cuffedPlayers.entrySet()) {
                UUID victimUUID = entry.getKey();
                CuffedData data = entry.getValue();
                Entity anchor = Bukkit.getEntity(data.getAnchorUUID());

                Player victim = Bukkit.getPlayer(victimUUID);
                Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());

                boolean shouldUncuff = false;
                String uncuffReason = "Unknown";

                if (policeman == null || !policeman.isOnline()) {
                    shouldUncuff = true;
                    uncuffReason = "Policeman is offline or null.";
                } else if (victim == null || !victim.isOnline()) {
                    shouldUncuff = true;
                    uncuffReason = "Victim is offline or null.";
                } else if (anchor == null || anchor.isDead()) {
                    shouldUncuff = true;
                    uncuffReason = "Anchor entity is invalid.";
                } else if (victim.getWorld() != policeman.getWorld()) {
                    shouldUncuff = true;
                    uncuffReason = "Player and policeman are in different worlds.";
                } else if (victim.getLocation().distance(policeman.getLocation()) > maxDist) {
                    shouldUncuff = true;
                    uncuffReason = "Distance limit exceeded.";
                }

                if (shouldUncuff) {
                    uncuffPlayer(victimUUID, "Watchdog: " + uncuffReason);
                }
            }
        }, 20L, 20L);
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final UUID anchorUUID;
        private final long cuffTimestamp;

        public CuffedData(UUID policemanUUID, UUID anchorUUID) {
            this.policemanUUID = policemanUUID;
            this.anchorUUID = anchorUUID;
            this.cuffTimestamp = System.currentTimeMillis();
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public UUID getAnchorUUID() { return anchorUUID; }
        public long getCuffTimestamp() { return cuffTimestamp; }
    }

    static class NockedData {
        private final UUID policemanUUID;
        private final WrappedTask expireTask;

        public NockedData(UUID policemanUUID, WrappedTask expireTask) {
            this.policemanUUID = policemanUUID;
            this.expireTask = expireTask;
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public WrappedTask getExpireTask() { return expireTask; }
    }
}