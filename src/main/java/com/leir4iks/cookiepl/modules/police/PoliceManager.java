package com.leir4iks.cookiepl.modules.police;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PoliceManager {

    private final CookiePl plugin;
    private final LogManager logManager;
    private final ProtocolManager protocolManager;
    private final Map<UUID, CuffedData> cuffedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NockedData> nockedPlayers = new ConcurrentHashMap<>();
    private final WrappedTask watchdogTask;
    private final WrappedTask cuffUpdateTask;
    private static final AtomicInteger entityIdCounter = new AtomicInteger(Integer.MAX_VALUE - 10000);

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

        int anchorId = entityIdCounter.decrementAndGet();
        CuffedData cuffedData = new CuffedData(policeman.getUniqueId(), anchorId);
        cuffedPlayers.put(victim.getUniqueId(), cuffedData);

        broadcastToNearby(policeman, getAnchorSpawnPackets(anchorId, policeman.getLocation()));
        broadcastToNearby(policeman, getLeashPacket(policeman, anchorId));

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

        if (policeman != null) {
            broadcastToNearby(policeman, getDestroyPacket(data.getAnchorId()));
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
                int anchorId = entry.getValue().getAnchorId();

                if (victim == null || policeman == null || !victim.isOnline() || !policeman.isOnline()) {
                    continue;
                }

                plugin.getFoliaLib().getScheduler().runAtEntity(victim, task -> {
                    Location policeLoc = policeman.getLocation();
                    broadcastToNearby(policeman, getTeleportPacket(anchorId, policeLoc));

                    Location victimLoc = victim.getLocation();
                    double distance = victimLoc.distance(policeLoc);

                    if (distance < minDistance) {
                        if (victim.getVelocity().lengthSquared() > 0.01) {
                            victim.setVelocity(new Vector(0, victim.getVelocity().getY(), 0));
                        }
                        return;
                    }

                    if (preventWallDragging) {
                        RayTraceResult rayTrace = victim.getWorld().rayTraceBlocks(victim.getEyeLocation(),
                                policeLoc.toVector().subtract(victim.getEyeLocation().toVector()).normalize(), distance);
                        if (rayTrace != null && rayTrace.getHitBlock() != null) {
                            return;
                        }
                    }

                    double forceMultiplier = Math.min(1.0, (distance - minDistance) / (maxDistance - minDistance));
                    Vector direction = policeLoc.toVector().subtract(victimLoc.toVector()).normalize();
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

    private List<PacketContainer> getAnchorSpawnPackets(int entityId, Location loc) {
        PacketContainer spawnPacket = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
        spawnPacket.getIntegers().write(0, entityId);
        spawnPacket.getUUIDs().write(0, UUID.randomUUID());
        spawnPacket.getEntityTypeModifier().write(0, EntityType.ARMOR_STAND);
        spawnPacket.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());

        PacketContainer metadataPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        metadataPacket.getIntegers().write(0, entityId);
        WrappedDataWatcher watcher = new WrappedDataWatcher();
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(0, WrappedDataWatcher.Registry.get(Byte.class)), (byte) 0x20); // Invisible
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(15, WrappedDataWatcher.Registry.get(Byte.class)), (byte) 0x10); // isMarker
        final List<WrappedDataValue> wrappedDataValueList = new ArrayList<>();
        watcher.getWatchableObjects().stream()
                .filter(Objects::nonNull)
                .forEach(entry -> {
                    final WrappedDataWatcher.WrappedDataWatcherObject dataWatcherObject = entry.getWatcherObject();
                    wrappedDataValueList.add(
                            new WrappedDataValue(
                                    dataWatcherObject.getIndex(),
                                    dataWatcherObject.getSerializer(),
                                    entry.getRawValue()
                            )
                    );
                });
        metadataPacket.getDataValueCollectionModifier().write(0, wrappedDataValueList);

        return Arrays.asList(spawnPacket, metadataPacket);
    }

    private PacketContainer getLeashPacket(Player holder, int anchorId) {
        PacketContainer leashPacket = new PacketContainer(PacketType.Play.Server.LEASH_ENTITY);
        leashPacket.getIntegers().write(0, holder.getEntityId());
        leashPacket.getIntegers().write(1, anchorId);
        return leashPacket;
    }

    private PacketContainer getTeleportPacket(int entityId, Location loc) {
        PacketContainer teleportPacket = new PacketContainer(PacketType.Play.Server.ENTITY_TELEPORT);
        teleportPacket.getIntegers().write(0, entityId);
        teleportPacket.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());
        teleportPacket.getBytes()
                .write(0, (byte) (loc.getYaw() * 256.0F / 360.0F))
                .write(1, (byte) (loc.getPitch() * 256.0F / 360.0F));
        teleportPacket.getBooleans().write(0, true);
        return teleportPacket;
    }

    private PacketContainer getDestroyPacket(int entityId) {
        PacketContainer destroyPacket = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
        destroyPacket.getIntLists().write(0, Collections.singletonList(entityId));
        return destroyPacket;
    }

    private void broadcastToNearby(Player center, PacketContainer packet) {
        for (Player observer : center.getWorld().getPlayers()) {
            if (observer.getLocation().distanceSquared(center.getLocation()) < 64 * 64) {
                protocolManager.sendServerPacket(observer, packet);
            }
        }
    }

    private void broadcastToNearby(Player center, List<PacketContainer> packets) {
        for (PacketContainer packet : packets) {
            broadcastToNearby(center, packet);
        }
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final int anchorId;
        private final long cuffTimestamp;

        public CuffedData(UUID policemanUUID, int anchorId) {
            this.policemanUUID = policemanUUID;
            this.anchorId = anchorId;
            this.cuffTimestamp = System.currentTimeMillis();
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public int getAnchorId() { return anchorId; }
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