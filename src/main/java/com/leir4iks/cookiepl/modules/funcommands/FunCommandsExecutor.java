package com.leir4iks.cookiepl.modules.funcommands;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FunCommandsExecutor implements CommandExecutor {

    private final CookiePl plugin;
    private final Map<String, Map<UUID, Long>> cooldowns = new ConcurrentHashMap<>();

    public FunCommandsExecutor(CookiePl plugin) {
        this.plugin = plugin;
        this.cooldowns.put("fart", new ConcurrentHashMap<>());
        this.cooldowns.put("spit", new ConcurrentHashMap<>());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String commandName = command.getName().toLowerCase();
        String permission = plugin.getConfig().getString("modules.fun-commands.permission-" + commandName);

        if (permission == null || !sender.hasPermission(permission)) {
            String noPermMsg = plugin.getConfig().getString("modules.fun-commands.messages.no-permission", "&cYou do not have permission to use this command.");
            sender.sendMessage(formatColor(noPermMsg));
            return true;
        }

        if (!(sender instanceof Player)) {
            String playersOnlyMsg = plugin.getConfig().getString("modules.fun-commands.messages.players-only", "&cThis command can only be used by players.");
            sender.sendMessage(formatColor(playersOnlyMsg));
            return true;
        }

        Player player = (Player) sender;

        if (isOnCooldown(player, commandName)) {
            String cooldownMsg = plugin.getConfig().getString("modules.fun-commands.messages.cooldown", "&cPlease wait before using this command again.");
            player.sendMessage(formatColor(cooldownMsg));
            return true;
        }

        switch (commandName) {
            case "fart":
                executeFart(player);
                break;
            case "spit":
                executeSpit(player);
                break;
        }

        setCooldown(player, commandName);
        return true;
    }

    private void executeFart(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BEE_HURT, 0.4f, 0.4f);
        Location particleLoc = player.getLocation().add(0, 0.5, 0);
        player.getWorld().spawnParticle(Particle.SNEEZE, particleLoc, 1, 0, 0, 0, 0);
    }

    private void executeSpit(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.0f, 1.0f);

        final long[] count = {0};
        plugin.getFoliaLib().getScheduler().runAtEntityTimer(player, (task) -> {
            long distance = count[0] + 1;
            if (distance > 5) {
                task.cancel();
                return;
            }

            Location eyeLoc = player.getEyeLocation();
            Vector direction = eyeLoc.getDirection();
            Location particleLoc = eyeLoc.clone().add(direction.multiply(distance));

            Particle.DustOptions dustOptions = new Particle.DustOptions(Color.WHITE, 1.0f);
            player.getWorld().spawnParticle(Particle.DUST, particleLoc, 2, 0.1, 0.1, 0.1, 0, dustOptions);
            count[0]++;
        }, 0L, 1L);
    }

    private boolean isOnCooldown(Player player, String command) {
        long cooldownSeconds = plugin.getConfig().getLong("modules.fun-commands." + command + ".cooldown-seconds");
        long cooldownMillis = cooldownSeconds * 1000;
        if (cooldownMillis <= 0) return false;

        Map<UUID, Long> commandCooldowns = cooldowns.get(command);
        long lastUsed = commandCooldowns.getOrDefault(player.getUniqueId(), 0L);

        return (System.currentTimeMillis() - lastUsed) < cooldownMillis;
    }

    private void setCooldown(Player player, String command) {
        cooldowns.get(command).put(player.getUniqueId(), System.currentTimeMillis());
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}