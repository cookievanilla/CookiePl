package com.leir4iks.cookiepl.modules.tags;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import org.bukkit.Bukkit;

public class TagsModule implements IModule {
    private TagsManager tagsManager;
    private TagsPlaceholder tagsPlaceholder;

    @Override
    public void enable(CookiePl plugin) {
        tagsManager = new TagsManager(plugin);
        tagsManager.load();
        plugin.setTagsManager(tagsManager);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            tagsPlaceholder = new TagsPlaceholder(plugin, tagsManager);
            tagsPlaceholder.register();
            plugin.getLogManager().info("Hooked into PlaceholderAPI for tags modulee.");
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        if (tagsPlaceholder != null && tagsPlaceholder.isRegistered()) {
            tagsPlaceholder.unregister();
        }
        plugin.setTagsManager(null);
        tagsPlaceholder = null;
        tagsManager = null;
    }

    @Override
    public String getName() {
        return "Tags";
    }

    @Override
    public String getConfigKey() {
        return "tags";
    }
}
