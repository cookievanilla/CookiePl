package com.leir4iks.cookiepl.permissions;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.Locale;

public final class PermissionRegistrar implements Listener {

    private static final String DEFAULT_NO_PERMISSION_MESSAGE = ChatColor.RED + "You do not have permission to use this command.";

    private final CookiePl plugin;

    public PermissionRegistrar(CookiePl plugin) {
        this.plugin = plugin;
    }

    public void apply() {
        registerPermissions();
        applyCommandPermissions();
        refreshOnlinePlayers();
    }

    private void registerPermissions() {
        registerPermission("cookiepl.command.base");
        registerPermission("cookiepl.command.reload");
        registerPermission(getConfigString("modules.where-my-frame.permission", "cookiepl.command.wmf"));
        registerPermission(getConfigString("modules.fun-commands.permission-fart", "cookiepl.command.fart"));
        registerPermission(getConfigString("modules.fun-commands.permission-spit", "cookiepl.command.spit"));
        registerPermission(getConfigString("modules.police-system.permission", "cookiepl.police.use"));
        registerPermission(getConfigString("modules.resize.permission", "cookiepl.resize"));
        registerPermission(getConfigString("modules.resize.permission-extended", "cookiepl.resize.extended"));
        registerPermission(getConfigString("modules.resize.permission-others", "cookiepl.resize.others"));
        registerPermission(getConfigString("modules.resize.permission-bypass-cooldown", "cookiepl.resize.bypass.cooldown"));
        registerPermission(getConfigString("modules.resize.permission-reload", "cookiepl.resize.reload"));
        registerPermission(getConfigString("modules.afk-system.permission", "cookiepl.command.afk"));
        registerPermission(getConfigString("modules.hat.permission-ignore-binding", "cookiepl.hat.ignore-binding"));
        registerPermission(getConfigString("modules.player-heads.bypass-permission", "cookiepl.playerheads.bypass"));
        registerPermission(getConfigString("modules.antielytra.bypass-permission", "cookiepl.antielytra.bypass"));
        registerPermission(getConfigString("modules.antigrief.bypass-permission", "cookiepl.antigrief.bypass"));
    }

    private void applyCommandPermissions() {
        setCommandPermission("cookiepl", "cookiepl.command.base");
        setCommandPermission("wheremyframe", getConfigString("modules.where-my-frame.permission", "cookiepl.command.wmf"));
        setCommandPermission("fart", getConfigString("modules.fun-commands.permission-fart", "cookiepl.command.fart"));
        setCommandPermission("spit", getConfigString("modules.fun-commands.permission-spit", "cookiepl.command.spit"));
        setCommandPermission("police", getConfigString("modules.police-system.permission", "cookiepl.police.use"));
        setCommandPermission("resize", getConfigString("modules.resize.permission", "cookiepl.resize"));
        setCommandPermission("afk", getConfigString("modules.afk-system.permission", "cookiepl.command.afk"));
        setCommandPermission("profile", null);
    }

    private void registerPermission(String node) {
        String normalized = normalize(node);
        if (normalized == null) {
            return;
        }

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        Permission permission = pluginManager.getPermission(normalized);

        if (permission == null) {
            pluginManager.addPermission(new Permission(normalized, PermissionDefault.FALSE));
            return;
        }

        if (permission.getDefault() != PermissionDefault.FALSE) {
            permission.setDefault(PermissionDefault.FALSE);
            pluginManager.recalculatePermissionDefaults(permission);
        }
    }

    private void setCommandPermission(String commandName, String permissionNode) {
        PluginCommand command = plugin.getCommand(commandName);
        if (command == null) {
            return;
        }

        command.setPermission(normalize(permissionNode));
        command.setPermissionMessage(DEFAULT_NO_PERMISSION_MESSAGE);
    }

    private String getConfigString(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerCommands(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getFoliaLib().getScheduler().runAtEntity(event.getPlayer(), task -> refreshPlayerCommands(event.getPlayer()));
    }

    private void refreshPlayerCommands(Player player) {
        try {
            player.recalculatePermissions();
        } catch (Exception ignored) {
        }

        try {
            player.updateCommands();
        } catch (Exception ignored) {
        }
    }
}
