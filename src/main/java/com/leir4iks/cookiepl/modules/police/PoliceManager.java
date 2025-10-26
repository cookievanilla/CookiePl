package com.leir4iks.cookiepl.modules.police;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
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
    private final ProtocolManager protocolManager;
    private final Map<UUID, CuffedData> cuffedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NockedData> nockedPlayers = new ConcurrentHashMap<>();
    private final WrappedTask watchdogTask;
    private final WrappedTask cuffUpdateTask;
    private final WrappedTask leashSyncTask;

    public static NamespacedKey HANDCUFFS_KEY;
    public static NamespacedKey BATON_KEY;

    public PoliceManager(CookiePl plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        HANDCUFFS_KEY = new NamespacedKey(plugin, "handcuffs_item");
        BATON_KEY = new NamespacedKey(plugin, "baton_item");
        this.watchdogTask = startWatchdogTask();
        this.cuffUpdateTask = startCuffUpdateTask();
        this.leashSyncTask = startLeashSyncTask();
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

        cuffedPlayers.put(victim.getUniqueId(), new CuffedData(policeman.getUniqueId()));
        sendLeashPacket(policeman, victim, true);

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.0f);
        executeEmoteCommand("start-cuff", victim.getName());

        String message = plugin.getConfig().getString("modules.police-system.cuff.message", "&aYou have cuffed player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " cuffed " + victim.getName());
    }

    public void uncuffPlayer(UUID victimUUID, String reason) {
        CuffedData data = cuffedPlayers.remove(victimUUID);
        if (data == null) return;

        Player victim = Bukkit.getPlayer(victimUUID);
        Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());

        if (victim != null && policeman != null) {
            sendLeashPacket(policeman, victim, false);
        }

        if (victim != null) {
            victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.0f);
            executeEmoteCommand("stop-cuff", victim.getName());

            if (policeman != null) {
                String message = plugin.getConfig().getString("modules.police-system.cuff.uncuff-message", "&ePlayer {player} has been released!");
                policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
            }
        }
        logManager.info("Player " + (victim != null ? victim.getName() : victimUUID) + " has been uncuffed. Reason: " + reason);
    }

    private void sendLeashPacket(Player holder, Player leashed, boolean attach) {
        PacketContainer leashPacket = protocolManager.createPacket(PacketType.Play.Server.ATTACH_ENTITY);
        leashPacket.getIntegers().write(0, leashed.getEntityId());
        leashPacket.getIntegers().write(1, attach ? holder.getEntityId() : -1);

        for (Player observer : holder.getWorld().getPlayers()) {
            if (observer.getLocation().distanceSquared(holder.getLocation()) < 64 * 64) {
                try {
                    protocolManager.sendServerPacket(observer, leashPacket);
                } catch (Exception e) {
                    logManager.severe("Failed to send leash packet to " + observer.getName());
                }
            }
        }
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
        if (leashSyncTask != null) leashSyncTask.cancel();

        for (UUID uuid : cuffedPlayers.keySet()) {
            uncuffPlayer(uuid, "Plugin Disable");
        }
        cuffedPlayers.clear();
        nockedPlayers.clear();
        logManager.info("Cleaned up all police data.");
    }

    private WrappedTask startCuffUpdateTask() {
        final double pullForce = plugin.getConfig().getDouble("modules.police-system.physics.pull-force", 0.4);
        final double maxSpeed = plugin.getConfig().getDouble("modules.police-system.physics.max-speed", 1.5);
        final double pullStartDistance = plugin.getConfig().getDouble("modules.police-system.physics.pull-start-distance", 2.0);
        final boolean preventWallDragging = plugin.getConfig().getBoolean("modules.police-system.physics.prevent-wall-dragging", true);

        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            if (cuffedPlayers.isEmpty()) return;

            for (Map.Entry<UUID, CuffedData> entry : cuffedPlayers.entrySet()) {
                Player victim = Bukkit.getPlayer(entry.getKey());
                Player policeman = Bukkit.getPlayer(entry.getValue().getPolicemanUUID());

                if (victim == null || policeman == null || !victim.isOnline() || !policeman.isOnline()) {
                    continue;
                }

                plugin.getFoliaLib().getScheduler().runAtEntity(victim, task -> {
                    Location victimLoc = victim.getLocation();
                    Location policeLoc = policeman.getLocation();
                    if (victimLoc.getWorld() != policeLoc.getWorld()) return;

                    double distance = victimLoc.distance(policeLoc);
                    if (distance > pullStartDistance) {
                        if (preventWallDragging) {
                            RayTraceResult rayTrace = policeman.getWorld().rayTraceBlocks(
                                    policeman.getEyeLocation(),
                                    victim.getEyeLocation().toVector().subtract(policeman.getEyeLocation().toVector()),
                                    distance,
                                    FluidCollisionMode.NEVER,
                                    true
                            );
                            if (rayTrace != null && rayTrace.getHitBlock() != null) {
                                return;
                            }
                        }

                        Vector direction = policeLoc.toVector().subtract(victimLoc.toVector()).normalize();
                        Vector pullVector = direction.multiply(pullForce);
                        Vector newVelocity = victim.getVelocity().add(pullVector);

                        if (newVelocity.length() > maxSpeed) {
                            newVelocity.normalize().multiply(maxSpeed);
                        }
                        victim.setVelocity(newVelocity);
                    }
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

    private WrappedTask startLeashSyncTask() {
        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            if (cuffedPlayers.isEmpty()) return;

            for (Map.Entry<UUID, CuffedData> entry : cuffedPlayers.entrySet()) {
                Player victim = Bukkit.getPlayer(entry.getKey());
                Player policeman = Bukkit.getPlayer(entry.getValue().getPolicemanUUID());

                if (victim != null && policeman != null && victim.isOnline() && policeman.isOnline()) {
                    sendLeashPacket(policeman, victim, true);
                }
            }
        }, 40L, 40L);
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final long cuffTimestamp;

        public CuffedData(UUID policemanUUID) {
            this.policemanUUID = policemanUUID;
            this.cuffTimestamp = System.currentTimeMillis();
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
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