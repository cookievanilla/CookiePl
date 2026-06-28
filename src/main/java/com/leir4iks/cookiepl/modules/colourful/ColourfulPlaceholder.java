package com.leir4iks.cookiepl.modules.colourful;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ColourfulPlaceholder extends PlaceholderExpansion {

    private static final String PARAM_END = "end";

    private final CookiePl plugin;

    public ColourfulPlaceholder(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "ccolour";
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
        if (player == null) return "";

        DatabaseManager db = plugin.getWebDatabaseManager();
        if (db == null) return "";

        String raw = db.getPlayerColour(player.getUniqueId());

        if (PARAM_END.equalsIgnoreCase(params)) {
            return ColourData.toMiniMessageClose(raw);
        }

        return ColourData.toMiniMessageOpen(raw);
    }
}
