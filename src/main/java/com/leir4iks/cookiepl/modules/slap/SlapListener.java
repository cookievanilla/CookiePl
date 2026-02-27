package com.leir4iks.cookiepl.modules.slap;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class SlapListener implements Listener {

    private final CookiePl plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private final long cooldownMillis;
    private final double patChance;
    private final String msgActionBar;
    private final String msgPatSender;
    private final String msgPatReceiver;
    private final String msgSlapSender;
    private final String msgSlapReceiver;

    public SlapListener(CookiePl plugin) {
        this.plugin = plugin;
        this.cooldownMillis = plugin.getConfig().getLong("modules.slap.cooldown-seconds", 3) * 1000L;
        this.patChance = plugin.getConfig().getDouble("modules.slap.pat-chance", 90.0);
        this.msgActionBar = formatColor(plugin.getConfig().getString("modules.slap.messages.action-bar", "&6 ← {player} →"));
        this.msgPatSender = formatColor(plugin.getConfig().getString("modules.slap.messages.pat-sender", "&6You patted {player} on the shoulder!"));
        this.msgPatReceiver = formatColor(plugin.getConfig().getString("modules.slap.messages.pat-receiver", "&6{player} patted you on the shoulder!"));
        this.msgSlapSender = formatColor(plugin.getConfig().getString("modules.slap.messages.slap-sender", "&6You slapped {player} on the ass!"));
        this.msgSlapReceiver = formatColor(plugin.getConfig().getString("modules.slap.messages.slap-receiver", "&6{player} slapped you on the ass!"));
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) {
            return;
        }

        Player slapper = event.getPlayer();
        Player victim = (Player) event.getRightClicked();

        if (slapper.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        if (isOnCooldown(slapper)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        setCooldown(slapper);

        if (slapper.isSneaking()) {
            sendTemporaryActionBar(slapper, msgActionBar.replace("{player}", victim.getName()));
            return;
        }

        slapper.swingMainHand();
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.0f);

        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.fromRGB(255, 153, 0), 1.0f);
        victim.getWorld().spawnParticle(Particle.DUST, victim.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0, dustOptions);

        DatabaseManager db = plugin.getWebDatabaseManager();
        if (db != null) {
            db.addSlapSent(slapper.getUniqueId(), 1L);
            db.addSlapReceived(victim.getUniqueId(), 1L);
        }

        if (ThreadLocalRandom.current().nextDouble(100.0) < patChance) {
            sendTemporaryActionBar(slapper, msgPatSender.replace("{player}", victim.getName()));
            sendTemporaryActionBar(victim, msgPatReceiver.replace("{player}", slapper.getName()));
        } else {
            sendTemporaryActionBar(slapper, msgSlapSender.replace("{player}", victim.getName()));
            sendTemporaryActionBar(victim, msgSlapReceiver.replace("{player}", slapper.getName()));
        }
    }

    private void sendTemporaryActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));

        player.getScheduler().runDelayed(plugin, scheduledTask -> {
            if (player.isOnline()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            }
        }, null, 60L);
    }

    private boolean isOnCooldown(Player player) {
        if (cooldownMillis <= 0) return false;
        Long lastUsed = cooldowns.get(player.getUniqueId());
        if (lastUsed == null) return false;
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