package com.leir4iks.cookiepl.modules.profile;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import com.leir4iks.cookiepl.modules.profile.features.adventure;
import com.leir4iks.cookiepl.modules.profile.features.death;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class profile implements IModule, Listener, CommandExecutor {

    private static final int INVENTORY_SIZE = 27;
    private static final int PLAYER_INFO_SLOT = 13;
    private static final int DEATH_SLOT = 11;
    private static final int ADVANCEMENT_SLOT = 15;
    private static final int CLOSE_SLOT = 22;

    private CookiePl plugin;
    private death deathFeature;
    private adventure adventureFeature;

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;
        this.deathFeature = new death(plugin);
        this.adventureFeature = new adventure(plugin);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(deathFeature, plugin);
        plugin.getServer().getPluginManager().registerEvents(adventureFeature, plugin);

        PluginCommand command = plugin.getCommand("profile");
        if (command != null) {
            command.setExecutor(this);
            command.setPermission(plugin.getConfig().getString("modules.profile.permission", "cookiepl.command.profile"));
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        PluginCommand command = plugin.getCommand("profile");
        if (command != null) {
            command.setExecutor(null);
        }

        HandlerList.unregisterAll(this);
        if (deathFeature != null) {
            HandlerList.unregisterAll(deathFeature);
        }
        if (adventureFeature != null) {
            HandlerList.unregisterAll(adventureFeature);
        }

        this.deathFeature = null;
        this.adventureFeature = null;
        this.plugin = null;
    }

    @Override
    public String getName() {
        return "Profile";
    }

    @Override
    public String getConfigKey() {
        return "profile";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getString("modules.profile.messages.players-only", "&cЭту команду может использовать только игрок.")));
            return true;
        }

        openMenu(player);
        return true;
    }

    private void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE, getMenuTitle());
        renderMenu(player, inventory);
        player.openInventory(inventory);
    }

    private void renderMenu(Player player, Inventory inventory) {
        ItemStack filler = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of(), true);

        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(PLAYER_INFO_SLOT, createProfileInfoItem(player));
        inventory.setItem(DEATH_SLOT, createToggleItem(
                Material.SKELETON_SKULL,
                getString("modules.profile.menu.items.death.name", "&fСмерть"),
                deathFeature.isVisible(player),
                getStringList(
                        "modules.profile.menu.items.death.description",
                        List.of("&7Кто увидит сообщение о смерти")
                )
        ));

        inventory.setItem(ADVANCEMENT_SLOT, createToggleItem(
                Material.KNOWLEDGE_BOOK,
                getString("modules.profile.menu.items.advancement.name", "&fДостижения"),
                adventureFeature.isVisible(player),
                getStringList(
                        "modules.profile.menu.items.advancement.description",
                        List.of("&7Кто увидит сообщение о достижении")
                )
        ));

        inventory.setItem(CLOSE_SLOT, createItem(
                Material.BARRIER,
                getString("modules.profile.menu.items.close.name", "&7Закрыть"),
                getStringList("modules.profile.menu.items.close.description", List.of("&7Нажми, чтобы выйти")),
                true
        ));
    }

    private ItemStack createProfileInfoItem(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setOwningPlayer(player);
        meta.setDisplayName(color(getString("modules.profile.menu.items.profile-info.name", "&fПрофиль")));

        List<String> lore = new ArrayList<>();
        for (String line : getStringList(
                "modules.profile.menu.items.profile-info.description",
                List.of("&7Настройки уведомлений", "&7Игрок: &f{player}")
        )) {
            lore.add(color(line.replace("{player}", player.getName())));
        }
        lore.add("");
        lore.add(color("&7Смерть: " + formatState(deathFeature.isVisible(player))));
        lore.add(color("&7Достижения: " + formatState(adventureFeature.isVisible(player))));

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createToggleItem(Material material, String displayName, boolean enabled, List<String> description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(displayName));

        List<String> lore = new ArrayList<>();
        for (String line : description) {
            lore.add(color(line));
        }
        lore.add("");
        lore.add(color("&7Статус: " + formatState(enabled)));
        lore.add(color(enabled
                ? getString("modules.profile.menu.common.click-to-disable", "&7Нажми, чтобы скрыть")
                : getString("modules.profile.menu.common.click-to-enable", "&7Нажми, чтобы показать")));

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String formatState(boolean enabled) {
        return enabled
                ? getString("modules.profile.menu.common.state-enabled", "&aвидно")
                : getString("modules.profile.menu.common.state-disabled", "&7скрыто");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isProfileMenu(event.getView().getTitle())) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }

        switch (event.getRawSlot()) {
            case DEATH_SLOT -> {
                boolean isVisible = deathFeature.toggle(player);
                player.sendMessage(color(getString(
                        isVisible ? "modules.profile.messages.death-enabled" : "modules.profile.messages.death-disabled",
                        isVisible
                                ? "&7Профиль: &fсмерть видна всем."
                                : "&7Профиль: &fсмерть скрыта."
                )));
                renderMenu(player, event.getView().getTopInventory());
            }
            case ADVANCEMENT_SLOT -> {
                boolean isVisible = adventureFeature.toggle(player);
                player.sendMessage(color(getString(
                        isVisible ? "modules.profile.messages.advancements-enabled" : "modules.profile.messages.advancements-disabled",
                        isVisible
                                ? "&7Профиль: &fдостижения видны всем."
                                : "&7Профиль: &fдостижения скрыты."
                )));
                renderMenu(player, event.getView().getTopInventory());
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isProfileMenu(event.getView().getTitle())) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isProfileMenu(String title) {
        return getMenuTitle().equals(title);
    }

    private String getMenuTitle() {
        return color(getString("modules.profile.menu.title", "&8Профиль"));
    }

    private ItemStack createItem(Material material, String displayName, List<String> lore, boolean hideAttributes) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(displayName));
        if (!lore.isEmpty()) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(color(line));
            }
            meta.setLore(coloredLore);
        }
        if (hideAttributes) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String getString(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private List<String> getStringList(String path, List<String> def) {
        List<String> list = plugin.getConfig().getStringList(path);
        return list == null || list.isEmpty() ? def : list;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
