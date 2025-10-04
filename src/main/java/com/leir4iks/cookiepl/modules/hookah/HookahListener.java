package com.leir4iks.cookiepl.modules.hookah;

import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class HookahListener implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Double> hookahLevels = new HashMap<>();
    private WrappedTask reducerTask;

    private final int effectDuration;
    private final double levelIncrease;
    private final int smokeParticleCount;

    public HookahListener(CookiePl plugin) {
        this.plugin = plugin;
        this.effectDuration = plugin.getConfig().getInt("modules.hookah.effect-duration-seconds", 3) * 20;
        this.levelIncrease = plugin.getConfig().getDouble("modules.hookah.level-increase", 1.0);
        this.smokeParticleCount = plugin.getConfig().getInt("modules.hookah.smoke-particle-count", 15);
        startLevelReducerTask();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.BREWING_STAND
                || !event.getPlayer().isSneaking()) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        BrewingStand stand = (BrewingStand) event.getClickedBlock().getState();
        BrewerInventory inventory = stand.getInventory();

        List<PotionEffect> possibleEffects = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getItemMeta() instanceof PotionMeta) {
                PotionMeta meta = (PotionMeta) item.getItemMeta();
                possibleEffects.addAll(meta.getCustomEffects());
                PotionEffectType baseEffectType = meta.getBasePotionData().getType().getEffectType();
                if (baseEffectType != null) {
                    possibleEffects.add(new PotionEffect(baseEffectType, 0, 0));
                }
            }
        }

        if (!possibleEffects.isEmpty()) {
            PotionEffect randomEffect = possibleEffects.get(ThreadLocalRandom.current().nextInt(possibleEffects.size()));
            player.addPotionEffect(new PotionEffect(randomEffect.getType(), this.effectDuration, randomEffect.getAmplifier()));
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_WORK_CLERIC, 0.3f, 0.1f);
        player.swingMainHand();

        hookahLevels.put(player.getUniqueId(), hookahLevels.getOrDefault(player.getUniqueId(), 0.0) + this.levelIncrease);

        plugin.getFoliaLib().getScheduler().runLater(() -> spawnSmokeEffect(player), 20L);
    }

    private void spawnSmokeEffect(Player player) {
        final long[] count = {0};
        plugin.getFoliaLib().getScheduler().runAtEntityTimer(player, (task) -> {
            if (count[0] >= smokeParticleCount || !player.isOnline()) {
                task.cancel();
                return;
            }

            Location eyeLocation = player.getEyeLocation();
            Vector randomOffset = new Vector(
                    ThreadLocalRandom.current().nextDouble(-0.1, 0.1),
                    ThreadLocalRandom.current().nextDouble(-0.1, 0.1),
                    ThreadLocalRandom.current().nextDouble(-0.1, 0.1)
            );
            Vector velocity = eyeLocation.getDirection().clone().add(randomOffset).multiply(0.2);

            player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, eyeLocation.clone().add(eyeLocation.getDirection().multiply(0.5)), 1, 0, 0, 0, 0, velocity);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 0.05f, 0.5f);
            count[0]++;
        }, 0L, 1L);
    }

    private void startLevelReducerTask() {
        final double normalDecrease = plugin.getConfig().getDouble("modules.hookah.level-decrease-per-second", 0.2);
        final double overdoseDecrease = plugin.getConfig().getDouble("modules.hookah.overdose-level-decrease-per-second", 4.2);
        final double threshold = plugin.getConfig().getDouble("modules.hookah.overdose-threshold", 8.0);

        this.reducerTask = plugin.getFoliaLib().getScheduler().runTimerAsync(() -> {
            if (hookahLevels.isEmpty()) {
                return;
            }

            Iterator<Map.Entry<UUID, Double>> iterator = hookahLevels.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Double> entry = iterator.next();
                UUID uuid = entry.getKey();
                double currentLevel = entry.getValue();

                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    double decreaseAmount = (currentLevel >= threshold) ? overdoseDecrease : normalDecrease;
                    double newLevel = Math.max(0, currentLevel - decreaseAmount);

                    if (newLevel <= 0) {
                        iterator.remove();
                    } else {
                        entry.setValue(newLevel);
                    }
                } else {
                    iterator.remove();
                }
            }
        }, 20L, 20L);
    }

    public void cancelTask() {
        if (reducerTask != null && !reducerTask.isCancelled()) {
            reducerTask.cancel();
        }
    }
}