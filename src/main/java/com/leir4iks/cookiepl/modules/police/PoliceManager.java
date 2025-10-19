package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PoliceManager {

    private final CookiePl plugin;
    private final LogManager logManager;
    private final Map<UUID, CuffedData> cuffedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NockedData> nockedPlayers = new ConcurrentHashMap<>();
    private final WrappedTask watchdogTask;

    public static NamespacedKey HANDCUFFS_KEY;
    public static NamespacedKey BATON_KEY;

    public PoliceManager(CookiePl plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
        this.watchdogTask = startWatchdogTask();
        HANDCUFFS_KEY = new NamespacedKey(plugin, "handcuffs_item");
        BATON_KEY = new NamespacedKey(plugin, "baton_item");
    }

    public boolean isCuffed(UUID uuid) {
        return cuffedPlayers.containsKey(uuid);
    }

    public boolean isNocked(UUID uuid) {
        return nockedPlayers.containsKey(uuid);
    }

    public CuffedData getCuffedData(UUID uuid) {
        return cuffedPlayers.get(uuid);
    }

    public Collection<CuffedData> getActiveCuffedData() {
        return cuffedPlayers.values();
    }

    public void nockPlayer(Player victim, Player policeman) {
        if (isCuffed(victim.getUniqueId()) || isNocked(victim.getUniqueId())) return;

        int duration = plugin.getConfig().getInt("modules.police-system.nock.duration-seconds", 4);
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
            if (!force && data.getExpireTask() != null) {
                data.getExpireTask().cancel();
            }
            logManager.info("Player " + victim.getName() + " is no longer nocked.");
        }
    }

    public void cuffPlayer(Player victim, Player policeman) {
        if (isCuffed(victim.getUniqueId())) return;
        unnockPlayer(victim, true);

        Location chickenLoc = victim.getLocation();
        Chicken chicken = victim.getWorld().spawn(chickenLoc, Chicken.class, c -> {
            c.setSilent(true);
            c.setInvulnerable(true);
            c.setCollidable(false);
            c.setInvisible(true);
            c.setAgeLock(true);
        });

        chicken.setLeashHolder(policeman);

        cuffedPlayers.put(victim.getUniqueId(), new CuffedData(policeman.getUniqueId(), chicken.getUniqueId()));

        plugin.getFoliaLib().getScheduler().runAtEntityTimer(victim, (task) -> {
            if (!cuffedPlayers.containsKey(victim.getUniqueId()) || !victim.isOnline() || chicken.isDead()) {
                task.cancel();
                return;
            }

            Location chickenLocation = chicken.getLocation();
            Location victimLocation = victim.getLocation();

            if (victimLocation.distanceSquared(chickenLocation) > 2.25) { // 1.5 blocks
                Vector pullVector = chickenLocation.toVector().subtract(victimLocation.toVector());
                victim.setVelocity(pullVector.normalize().multiply(1.2));
            }
        }, 0L, 3L);

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.0f);
        executeEmoteCommand("start-cuff", victim.getName());

        String message = plugin.getConfig().getString("modules.police-system.cuff.message", "&aYou have cuffed player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " cuffed " + victim.getName());
    }

    public void uncuffPlayer(UUID victimUUID, String reason) {
        CuffedData data = cuffedPlayers.remove(victimUUID);
        if (data == null) return;

        Chicken chicken = (Chicken) Bukkit.getEntity(data.getChickenUUID());
        if (chicken != null) {
            chicken.setLeashHolder(null);
            chicken.remove();
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
            logManager.info("Player " + (victim.isOnline() ? victim.getName() : victimUUID) + " has been uncuffed. Reason: " + reason);
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
        if (plugin.getServer().isStopping()) {
            return;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        for (UUID uuid : cuffedPlayers.keySet()) {
            uncuffPlayer(uuid, "Plugin Disable");
        }
        cuffedPlayers.clear();
        nockedPlayers.clear();
        logManager.info("Cleaned up all police data.");
    }

    private WrappedTask startWatchdogTask() {
        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            if (cuffedPlayers.isEmpty()) return;
            double maxDist = plugin.getConfig().getDouble("modules.police-system.max-cuff-distance", 20.0);

            for (Map.Entry<UUID, CuffedData> entry : cuffedPlayers.entrySet()) {
                UUID victimUUID = entry.getKey();
                CuffedData data = entry.getValue();
                Chicken chicken = (Chicken) Bukkit.getEntity(data.getChickenUUID());

                if (chicken == null) {
                    plugin.getFoliaLib().getScheduler().runNextTick(task -> uncuffPlayer(victimUUID, "Watchdog: Chicken entity is null"));
                    continue;
                }

                plugin.getFoliaLib().getScheduler().runAtEntity(chicken, (task) -> {
                    if (System.currentTimeMillis() - data.getCuffTimestamp() < 3000L) {
                        return;
                    }

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
                    } else if (chicken.isDead()) {
                        shouldUncuff = true;
                        uncuffReason = "Chicken entity is dead.";
                    } else if (!chicken.isLeashed()) {
                        shouldUncuff = true;
                        uncuffReason = "Chicken is not leashed.";
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
                });
            }
        }, 20L, 20L);
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final UUID chickenUUID;
        private final long cuffTimestamp;

        public CuffedData(UUID policemanUUID, UUID chickenUUID) {
            this.policemanUUID = policemanUUID;
            this.chickenUUID = chickenUUID;
            this.cuffTimestamp = System.currentTimeMillis();
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public UUID getChickenUUID() { return chickenUUID; }
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