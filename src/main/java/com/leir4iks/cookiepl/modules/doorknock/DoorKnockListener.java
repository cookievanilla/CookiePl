package com.leir4iks.cookiepl.modules.doorknock;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.*;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DoorKnockListener implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public DoorKnockListener(CookiePl plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        if (!(event.getClickedBlock().getBlockData() instanceof Door)) {
            return;
        }

        Player player = event.getPlayer();
        if (isOnCooldown(player)) {
            return;
        }

        setCooldown(player);

        Material doorType = event.getClickedBlock().getType();
        Sound sound = (doorType == Material.IRON_DOOR)
                ? Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR
                : Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR;

        player.getWorld().playSound(player.getLocation(), sound, 1.0f, 1.0f);

        if (plugin.getConfig().getBoolean("modules.door-knock.text-display.enabled", true)) {
            spawnKnockText(player);
        }
    }

    private void spawnKnockText(Player player) {
        String text = plugin.getConfig().getString("modules.door-knock.text-display.text", "&f* knocks on the door *");
        if (text.isEmpty()) {
            return;
        }

        Location spawnLocation = player.getEyeLocation();
        TextDisplay textDisplay = player.getWorld().spawn(spawnLocation, TextDisplay.class, display -> {
            display.setText(formatColor(text));
            display.setBillboard(Display.Billboard.CENTER);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setBrightness(new Display.Brightness(15, 15));
        });

        long textDurationTicks = plugin.getConfig().getLong("modules.door-knock.text-display.duration-ticks", 20);
        plugin.getFoliaLib().getScheduler().runAtLocationLater(textDisplay.getLocation(), textDisplay::remove, textDurationTicks);
    }

    private boolean isOnCooldown(Player player) {
        long cooldownMillis = plugin.getConfig().getLong("modules.door-knock.cooldown-seconds", 1) * 1000;
        if (cooldownMillis <= 0) return false;

        long lastUsed = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return (System.currentTimeMillis() - lastUsed) < cooldownMillis;
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}