package com.leir4iks.cookiepl.modules.resize;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ResizeManager {

    private final CookiePl plugin;
    private final Map<UUID, Long> lastUsageTime = new ConcurrentHashMap<>();

    private final String bypassCooldownPerm;
    private final long cooldownSeconds;
    private final int resizeSteps;

    public ResizeManager(CookiePl plugin) {
        this.plugin = plugin;
        this.bypassCooldownPerm = plugin.getConfig().getString("modules.resize.permission-bypass-cooldown", "cookiepl.resize.bypass.cooldown");
        this.cooldownSeconds = plugin.getConfig().getLong("modules.resize.cooldown-seconds", 30);
        this.resizeSteps = plugin.getConfig().getInt("modules.resize.resize-steps", 20);
    }

    public boolean hasCooldown(Player player) {
        if (player.hasPermission(this.bypassCooldownPerm)) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        long lastUsed = lastUsageTime.getOrDefault(player.getUniqueId(), 0L);
        long timeSinceLastUse = (currentTime - lastUsed) / 1000L;
        return timeSinceLastUse < this.cooldownSeconds;
    }

    public long getRemainingCooldown(Player player) {
        long currentTime = System.currentTimeMillis();
        long lastUsed = lastUsageTime.getOrDefault(player.getUniqueId(), 0L);
        long timeSinceLastUse = (currentTime - lastUsed) / 1000L;
        return Math.max(0L, this.cooldownSeconds - timeSinceLastUse);
    }

    public void updateLastUsage(Player player) {
        lastUsageTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void smoothlyResizePlayer(Player player, double targetScale) {
        double initialScale = player.getAttribute(Attribute.GENERIC_SCALE).getBaseValue();
        double step = (targetScale - initialScale) / this.resizeSteps;

        plugin.getFoliaLib().getScheduler().runAtEntityTimer(player, (task) -> {
            long count = task.getRunCount() + 1;
            if (count >= resizeSteps) {
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(targetScale);
                task.cancel();
            } else {
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(initialScale + step * count);
            }
        }, 0L, 1L);
    }

    public boolean isValidScale(double scale, boolean hasExtendedPermission) {
        return scale >= getMinScale(hasExtendedPermission) && scale <= getMaxScale(hasExtendedPermission);
    }

    public double getMinScale(boolean hasExtendedPermission) {
        String path = hasExtendedPermission ? "extended-" : "default-";
        return plugin.getConfig().getDouble("modules.resize." + path + "min-scale");
    }

    public double getMaxScale(boolean hasExtendedPermission) {
        String path = hasExtendedPermission ? "extended-" : "default-";
        return plugin.getConfig().getDouble("modules.resize." + path + "max-scale");
    }
}