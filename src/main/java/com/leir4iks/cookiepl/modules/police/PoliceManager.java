package com.leir4iks.cookiepl.modules.police;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.*;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

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
            c.setAI(false);
        });

        chicken.setLeashHolder(policeman);

        cuffedPlayers.put(victim.getUniqueId(), new CuffedData(policeman.getUniqueId(), chicken.getUniqueId()));

        plugin.getFoliaLib().getScheduler().runAtEntityTimer(victim, (task) -> {
            if (!cuffedPlayers.containsKey(victim.getUniqueId()) || !victim.isOnline() || chicken.isDead()) {
                task.cancel();
                return;
            }
            Location chickenLocation = chicken.getLocation();
            if (victim.getLocation().distanceSquared(chickenLocation) > 1.5 * 1.5) {
                plugin.getFoliaLib().getScheduler().teleportAsync(victim, chickenLocation);
            }
        }, 0L, 2L);

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 1.0f);
        executeEmoteCommand("start-cuff", victim.getName());

        String message = plugin.getConfig().getString("modules.police-system.cuff.message", "&aYou have cuffed player {player}!");
        policeman.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", victim.getName())));
        logManager.info("Player " + policeman.getName() + " cuffed " + victim.getName());
    }

    public void uncuffPlayer(UUID victimUUID) {
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
            logManager.info("Player " + (victim.isOnline() ? victim.getName() : victimUUID) + " has been uncuffed.");
        }
    }

    private void executeEmoteCommand(String type, String playerName) {
        String command = plugin.getConfig().getString("modules.police-system.emote-commands." + type, "");
        if (command != null && !command.isEmpty()) {
            String finalCommand = command.replace("{player}", playerName);
            plugin.getServer().getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand));
        }
    }

    public void cleanUpAll() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        for (UUID uuid : cuffedPlayers.keySet()) {
            uncuffPlayer(uuid);
        }
        cuffedPlayers.clear();
        nockedPlayers.clear();
        logManager.info("Cleaned up all police data.");
    }

    private WrappedTask startWatchdogTask() {
        return plugin.getFoliaLib().getScheduler().runTimer(() -> {
            double maxDist = plugin.getConfig().getDouble("modules.police-system.max-cuff-distance", 20.0);
            for (UUID victimUUID : cuffedPlayers.keySet()) {
                CuffedData data = cuffedPlayers.get(victimUUID);
                if (data == null) continue;

                Player victim = Bukkit.getPlayer(victimUUID);
                Player policeman = Bukkit.getPlayer(data.getPolicemanUUID());
                Chicken chicken = (Chicken) Bukkit.getEntity(data.getChickenUUID());

                boolean shouldUncuff = false;
                if (policeman == null || !policeman.isOnline() || victim == null || !victim.isOnline()) {
                    shouldUncuff = true;
                } else if (chicken == null || chicken.isDead() || !chicken.isLeashed()) {
                    shouldUncuff = true;
                } else if (victim.getWorld() != policeman.getWorld() || victim.getLocation().distance(policeman.getLocation()) > maxDist) {
                    shouldUncuff = true;
                }

                if (shouldUncuff) {
                    Location location = victim != null ? victim.getLocation() : (policeman != null ? policeman.getLocation() : null);
                    if (location != null) {
                        plugin.getFoliaLib().getScheduler().runAtLocation(location, (task) -> uncuffPlayer(victimUUID));
                    } else {
                        plugin.getServer().getScheduler().runTask(plugin, () -> uncuffPlayer(victimUUID));
                    }
                    logManager.debug("Auto-uncuffed player " + (victim != null ? victim.getName() : victimUUID) + " due to watchdog conditions.");
                }
            }
        }, 20L, 20L);
    }

    static class CuffedData {
        private final UUID policemanUUID;
        private final UUID chickenUUID;

        public CuffedData(UUID policemanUUID, UUID chickenUUID) {
            this.policemanUUID = policemanUUID;
            this.chickenUUID = chickenUUID;
        }

        public UUID getPolicemanUUID() { return policemanUUID; }
        public UUID getChickenUUID() { return chickenUUID; }
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