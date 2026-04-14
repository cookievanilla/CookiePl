package com.leir4iks.cookiepl.modules.tags;

import com.leir4iks.cookiepl.CookiePl;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TagsPlaceholder extends PlaceholderExpansion {
    private final CookiePl plugin;
    private final TagsManager tagsManager;

    public TagsPlaceholder(CookiePl plugin, TagsManager tagsManager) {
        this.plugin = plugin;
        this.tagsManager = tagsManager;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "tags";
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
        if (!"roles".equalsIgnoreCase(params)) {
            return "";
        }
        return tagsManager.getRenderedTagsForPlayer(player);
    }
}
