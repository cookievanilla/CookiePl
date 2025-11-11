package com.leir4iks.cookiepl.modules.police;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
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
        logManager.info("PoliceManager initialized");
    }

    public boolean isCuffed(UUID uuid) {
        return cuffedPlayers.containsKey(uuid);
    }

    public boolean isNocked(UUID uuid) {
        return nockedPlayers.containsKey(uuid);
    }

    public void nockPlayer(Player victim, Player policeman) {
        logManager.info("nockPlayer called - victim: " + victim.getName() + ", policeman: " + policeman.getName());

        if (isCuffed(victim.getUniqueId()) || isNocked(victim.getUniqueId())) {
            logManager.info("Player already cuffed or nocked, returning");
            return;
        }

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
        logManager.info("unnockPlayer called - victim: " + victim.getName() + ", force: " + force);

        NockedData data = nockedPlayers.remove(victim.getUniqueId());
        if (data != null) {
            if (!force && data.getExpireTask() != null && !data.getExpireTask().isCancelled()) {
                data.getExpireTask().cancel();
            }
            logManager.info("Player " + victim.getName() + " is no longer nocked.");
        }
    }

    public void cuffPlayer(Player victim, Player policeman) {
        logManager.info("cuffPlayer called - victim: " + victim.getName() + ", policeman: " + policeman.getName());

        if (isCuffed(victim.getUniqueId())) {
            logManager.info("Player already cuffed, returning");
            return;
        }
        unnockPlayer(victim, true);

        // Создаем ArmorStand для физики
        logManager.info("Creating ArmorStand at victim location: " + victim.getLocation());
        ArmorStand anchor = victim.getWorld().spawn(victim.getLocation(), ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setSmall(true);
        });
        logManager.info("ArmorStand created with ID: " + anchor.getUniqueId() + ", entity ID: " + anchor.getEntityId());

        // Создаем курицу для поводка
        logManager.info("Creating Chicken at victim location: " + victim.getLocation());
        Chicken leashChicken = victim.getWorld().spawn(victim.getLocation(), Chicken.class, c -> {
            c.setSilent(true);
            c.setInvulnerable(true);
            c.setGravity(false);
            c.setAI(false);
            c.setCollidable(false);
            c.setAdult();
            c.setBreed(false);
            c.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, true, false));
        });
        logManager.info("Chicken created with ID: " + leashChicken.getUniqueId() + ", entity ID: " + leashChicken.getEntityId());

        CuffedData cuffedData = new CuffedData(policeman.getUniqueId(), anchor.getUniqueId(), leashChicken.getUniqueId());
        cuffedPlayers.put(victim.getUniqueId(), cuffedData);
        logManager.info("CuffedData created and stored for victim: " + victim.getUniqueId());

        // Создаем поводок между игроком и курицей
        logManager.info("Creating leash packet between victim (ID: " + victim.getEntityId() + ") and chicken (ID: " + leashChicken.getEntityId() + ")");
        PacketContainer leashPacket = getLeashPacket(victim, leashChicken);
        if (leashPacket != null) {
            logManager.info("Leash packet created successfully, broadcasting to nearby players");
            broadcastToNearby(victim, leashPacket);
        } else {
            logManager.severe("Failed to create leash packet!");
        }

        // Дублируем через 1 тик для надежности
        plugin.getFoliaLib().getScheduler().runAtEntityLater(victim, () -> {
            logManager.info("Sending duplicate leash packet after 1 tick");
            PacketContainer duplicateLeashPacket = getLeashPacket(victim, leashChicken);
            if (duplicateLeashPacket != null) {
                broadcastToNearby(victim, duplicateLeashPacket);
            }
        }, 1L);

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.0f);
        executeEmoteCommand("start-cuff", victim.getName());

        String message = plugin.getConfig().getString("modules.police-system.cuff.message", "&aYou have cuffed player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " cuffed " + victim.getName() + " - ArmorStand: " + anchor.getEntityId() + ", Chicken: " + leashChicken.getEntityId());
    }

    public void uncuffPlayer(UUID victimUUID, String reason) {
        logManager.info("uncuffPlayer called - victim UUID: " + victimUUID + ", reason: " + reason);

        CuffedData data = cuffedPlayers.remove(victimUUID);
        if (data == null) {
            logManager.info("No cuffed data found for victim UUID: " + victimUUID);
            return;
        }

        Entity anchor = Bukkit.getEntity(data.getAnchorUUID());
        Entity leashChicken = Bukkit.getEntity(data.getLeashUUID());

        logManager.info("Found entities - anchor: " + (anchor != null) + ", leashChicken: " + (leashChicken != null));

        Entity taskEntity = anchor != null ? anchor : leashChicken;
        Location taskLocation = taskEntity != null ? taskEntity.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();

        plugin.getFoliaLib().getScheduler().runAtLocation(taskLocation, (task) -> {
            Player victim = Bukkit.getPlayer(victimUUID);
            logManager.info("Running uncuff task - victim: " + (victim != null ? victim.getName() : "null"));

            if (victim != null && victim.isOnline()) {
                logManager.info("Sending unleash packet for victim ID: " + victim.getEntityId());
                PacketContainer unleashPacket = getUnleashPacket(victim);
                if (unleashPacket != null) {
                    broadcastToNearby(victim, unleashPacket);
                }
            }

            if (anchor != null && !anchor.isDead()) {
                logManager.info("Removing ArmorStand with ID: " + anchor.getUniqueId());
                anchor.remove();
            } else {
                logManager.info("ArmorStand is null or already dead");
            }

            if (leashChicken != null && !leashChicken.isDead()) {
                logManager.info("Removing Chicken with ID: " + leashChicken.getUniqueId());
                leashChicken.remove();
            } else {
                logManager.info("Chicken is null or already dead");
            }

            if (victim != null && victim.isOnline()) {
                Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());

                victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.0f);
                executeEmoteCommand("stop-cuff", victim.getName());

                if (policeman != null && policeman.isOnline()) {
                    String message = plugin.getConfig().getString("modules.police-system.cuff.uncuff-message", "&ePlayer {player} has been released!");
                    policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
                }
            }
            logManager.info("Player " + (victim != null ? victim.getName() : victimUUID) + " has been uncuffed. Reason: " + reason);
        });
    }

    private void executeEmoteCommand(String type, String playerName) {
        String command = plugin.getConfig().getString("modules.police-system.emote-commands." + type, "");
        if (command != null && !command.isEmpty()) {
            String finalCommand = command.replace("{player}", playerName);
            plugin.getFoliaLib().getScheduler().runNextTick((task) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand));
        }
    }

    public void cleanUpAll() {
        logManager.info("cleanUpAll called");

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
                Entity leashChicken = Bukkit.getEntity(entry.getValue().getLeashUUID());

                if (victim == null || policeman == null || !victim.isOnline() || !policeman.isOnline()) {
                    continue;
                }

                if ((anchor == null || anchor.isDead()) && (leashChicken == null || leashChicken.isDead())) {
                    logManager.warn("Both entities lost for victim: " + victim.getName());
                    uncuffPlayer(entry.getKey(), "Both entities lost");
                    continue;
                }

                plugin.getFoliaLib().getScheduler().runAtEntity(victim, task -> {
                    Location policeLoc = policeman.getLocation();

                    // Телепортируем ArmorStand к полицейскому
                    if (anchor != null && !anchor.isDead()) {
                        if (!anchor.getLocation().getWorld().equals(policeLoc.getWorld()) ||
                                anchor.getLocation().distanceSquared(policeLoc) > 0.01) {
                            anchor.teleport(policeLoc);
                        }
                    }

                    // Телепортируем курицу к полицейскому и обновляем поводок
                    if (leashChicken != null && !leashChicken.isDead()) {
                        if (!leashChicken.getLocation().getWorld().equals(policeLoc.getWorld()) ||
                                leashChicken.getLocation().distanceSquared(policeLoc) > 0.01) {
                            leashChicken.teleport(policeLoc);
                        }

                        // Постоянно обновляем поводок
                        PacketContainer leashPacket = getLeashPacket(victim, leashChicken);
                        if (leashPacket != null) {
                            broadcastToNearby(victim, leashPacket);
                        }
                    }

                    // Применяем физику к игроку
                    if (anchor != null && !anchor.isDead()) {
                        Location victimLoc = victim.getLocation();
                        Location anchorLoc = anchor.getLocation();
                        double distance = victimLoc.distance(anchorLoc);

                        logManager.debug("Physics update - victim: " + victim.getName() + ", distance: " + distance);

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
                Entity anchor = Bukkit.getEntity(data.getAnchorUUID());
                Entity leashChicken = Bukkit.getEntity(data.getLeashUUID());

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
                } else if ((anchor == null || anchor.isDead()) && (leashChicken == null || leashChicken.isDead())) {
                    shouldUncuff = true;
                    uncuffReason = "Both anchor and leash entities are invalid.";
                } else if (victim.getWorld() != policeman.getWorld()) {
                    shouldUncuff = true;
                    uncuffReason = "Player and policeman are in different worlds.";
                } else if (victim.getLocation().distance(policeman.getLocation()) > maxDist) {
                    shouldUncuff = true;
                    uncuffReason = "Distance limit exceeded.";
                }

                if (shouldUncuff) {
                    logManager.info("Watchdog uncuffing victim: " + victimUUID + ", reason: " + uncuffReason);
                    uncuffPlayer(victimUUID, "Watchdog: " + uncuffReason);
                } else if (anchor == null || anchor.isDead() || leashChicken == null || leashChicken.isDead()) {
                    logManager.warn("Partial entity loss for cuffed player " + victim.getName() +
                            " - anchor: " + (anchor != null) + ", leash: " + (leashChicken != null));
                }
            }
        }, 20L, 20L);
    }

    private PacketContainer getLeashPacket(Entity leashed, Entity holder) {
        try {
            logManager.debug("Creating leash packet - leashed: " + leashed.getEntityId() + ", holder: " + holder.getEntityId());
            PacketContainer leashPacket = new PacketContainer(PacketType.Play.Server.ATTACH_ENTITY);
            leashPacket.getIntegers().write(0, leashed.getEntityId());
            leashPacket.getIntegers().write(1, holder.getEntityId());
            logManager.debug("Leash packet created successfully");
            return leashPacket;
        } catch (Exception e) {
            logManager.severe("Failed to create leash packet: " + e.getMessage());
            return null;
        }
    }

    private PacketContainer getUnleashPacket(Entity leashed) {
        try {
            logManager.debug("Creating unleash packet for entity: " + leashed.getEntityId());
            PacketContainer leashPacket = new PacketContainer(PacketType.Play.Server.ATTACH_ENTITY);
            leashPacket.getIntegers().write(0, leashed.getEntityId());
            leashPacket.getIntegers().write(1, -1);
            logManager.debug("Unleash packet created successfully");
            return leashPacket;
        } catch (Exception e) {
            logManager.severe("Failed to create unleash packet: " + e.getMessage());
            return null;
        }
    }

    private void broadcastToNearby(Player center, PacketContainer packet) {
        if (packet == null) {
            logManager.warn("Attempted to broadcast null packet");
            return;
        }

        int sentCount = 0;
        for (Player observer : center.getWorld().getPlayers()) {
            if (observer.getLocation().distanceSquared(center.getLocation()) < 128 * 128) {
                try {
                    protocolManager.sendServerPacket(observer, packet);
                    sentCount++;
                } catch (Exception e) {
                    logManager.severe("Failed to send packet to " + observer.getName() + ": " + e.getMessage());
                }
            }
        }
        logManager.debug("Leash packet broadcasted to " + sentCount + " players");
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final UUID anchorUUID;
        private final UUID leashUUID;
        private final long cuffTimestamp;

        public CuffedData(UUID policemanUUID, UUID anchorUUID, UUID leashUUID) {
            this.policemanUUID = policemanUUID;
            this.anchorUUID = anchorUUID;
            this.leashUUID = leashUUID;
            this.cuffTimestamp = System.currentTimeMillis();
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public UUID getAnchorUUID() { return anchorUUID; }
        public UUID getLeashUUID() { return leashUUID; }
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