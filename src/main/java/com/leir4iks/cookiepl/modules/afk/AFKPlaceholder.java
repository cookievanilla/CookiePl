package com.leir4iks.cookiepl.modules.afk;

import com.leir4iks.cookiepl.CookiePl;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AFKPlaceholder extends PlaceholderExpansion {

    private final CookiePl plugin;
    private final AFKManager afkManager;
    private final String afkSymbol;

    public AFKPlaceholder(CookiePl plugin, AFKManager afkManager) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.afkSymbol = formatColor(plugin.getConfig().getString("modules.afk-system.placeholder.afk-symbol", "&e⌛"));
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "cafk";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "leir4iks";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        if (afkManager.isAfk(player.getUniqueId())) {
            return afkSymbol;
        }
        return "";
    }

    @NotNull
    private String formatColor(@NotNull String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}