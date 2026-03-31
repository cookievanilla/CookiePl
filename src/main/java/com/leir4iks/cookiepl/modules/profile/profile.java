package com.leir4iks.cookiepl.modules.profile;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import com.leir4iks.cookiepl.modules.profile.features.adventure;
import com.leir4iks.cookiepl.modules.profile.features.death;
import com.leir4iks.cookiepl.modules.profile.features.squaremap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class profile implements IModule, Listener, CommandExecutor {

    private static final int INVENTORY_SIZE = 27;

    private static final int CATEGORY_LEFT_SLOT = 11;
    private static final int CATEGORY_CENTER_SLOT = 13;
    private static final int CATEGORY_RIGHT_SLOT = 15;

    private static final int PROFILE_INFO_SLOT = 4;
    private static final int DEATH_SLOT = 11;
    private static final int ADVANCEMENT_SLOT = 13;
    private static final int SQUAREMAP_SLOT = 15;

    private static final int BACK_SLOT = 18;
    private static final int CLOSE_SLOT = 22;

    private CookiePl plugin;
    private death deathFeature;
    private adventure adventureFeature;
    private squaremap squaremapFeature;

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;

        this.deathFeature = new death(plugin);
        this.adventureFeature = new adventure(plugin);

        if (plugin.getServer().getPluginManager().isPluginEnabled("squaremap")) {
            this.squaremapFeature = new squaremap();
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(deathFeature, plugin);
        plugin.getServer().getPluginManager().registerEvents(adventureFeature, plugin);

        PluginCommand command = plugin.getCommand("profile");
        if (command != null) {
            command.setExecutor(this);
            command.setPermission(plugin.getConfig().getString(
                    "modules.profile.permission",
                    "cookiepl.command.profile"
            ));
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
        this.squaremapFeature = null;
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
            sender.sendMessage(color(getString(
                    "modules.profile.messages.players-only",
                    "&cЭту команду может использовать только игрок."
            )));
            return true;
        }

        openCategoriesMenu(player);
        return true;
    }

    private void openCategoriesMenu(Player player) {
        ProfileMenuHolder holder = new ProfileMenuHolder(MenuType.CATEGORIES);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, getCategoriesMenuTitle());
        holder.setInventory(inventory);
        renderCategoriesMenu(player, inventory);
        player.openInventory(inventory);
    }

    private void openProfileSettingsMenu(Player player) {
        ProfileMenuHolder holder = new ProfileMenuHolder(MenuType.PROFILE_SETTINGS);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, getProfileSettingsMenuTitle());
        holder.setInventory(inventory);
        renderProfileSettingsMenu(player, inventory);
        player.openInventory(inventory);
    }

    private void renderCategoriesMenu(Player player, Inventory inventory) {
        ItemStack filler = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of(), true);

        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(CATEGORY_LEFT_SLOT, createSoonCategoryItem());
        inventory.setItem(CATEGORY_CENTER_SLOT, createProfileCategoryItem(player));
        inventory.setItem(CATEGORY_RIGHT_SLOT, createSoonCategoryItem());
        inventory.setItem(CLOSE_SLOT, createCloseItem());
    }

    private void renderProfileSettingsMenu(Player player, Inventory inventory) {
        ItemStack filler = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of(), true);

        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(PROFILE_INFO_SLOT, createProfileInfoItem(player));

        inventory.setItem(DEATH_SLOT, createToggleItem(
                Material.SKELETON_SKULL,
                getString("modules.profile.menu.items.death.name", "&fПоказ смертей"),
                deathFeature.isVisible(player),
                getStringList(
                        "modules.profile.menu.items.death.description",
                        List.of("&7Включение/выключение показа смертей")
                )
        ));

        inventory.setItem(ADVANCEMENT_SLOT, createToggleItem(
                Material.KNOWLEDGE_BOOK,
                getString("modules.profile.menu.items.advancement.name", "&fПоказ достижений"),
                adventureFeature.isVisible(player),
                getStringList(
                        "modules.profile.menu.items.advancement.description",
                        List.of("&7Включение/выключение показа достижений")
                )
        ));

        inventory.setItem(SQUAREMAP_SLOT, createSquaremapItem(player));

        inventory.setItem(BACK_SLOT, createItem(
                Material.ARROW,
                getString("modules.profile.menu.items.back.name", "&7Назад"),
                getStringList(
                        "modules.profile.menu.items.back.description",
                        List.of("&7Вернуться к категориям")
                ),
                true
        ));

        inventory.setItem(CLOSE_SLOT, createCloseItem());
    }

    private ItemStack createSoonCategoryItem() {
        return createItem(
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                List.of("&7soon..."),
                true
        );
    }

    private ItemStack createProfileCategoryItem(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setOwningPlayer(player);
        meta.setDisplayName(color("&f" + player.getName()));
        meta.setLore(List.of(color("&7Управление профилем")));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createProfileInfoItem(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setOwningPlayer(player);
        meta.setDisplayName(color("&f" + player.getName()));

        List<String> lore = new ArrayList<>();
        lore.add(color("&7Управление профилем"));
        lore.add("");
        lore.add(color("&7Смерти: " + formatState(deathFeature.isVisible(player))));
        lore.add(color("&7Достижения: " + formatState(adventureFeature.isVisible(player))));
        lore.add(color("&7Карта: " + formatSquaremapState(player)));

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseItem() {
        return createItem(
                Material.BARRIER,
                getString("modules.profile.menu.items.close.name", "&7Закрыть"),
                getStringList(
                        "modules.profile.menu.items.close.description",
                        List.of("&7Нажми, чтобы выйти")
                ),
                true
        );
    }

    private ItemStack createSquaremapItem(Player player) {
        if (squaremapFeature == null || !squaremapFeature.isAvailable()) {
            return createItem(
                    Material.FILLED_MAP,
                    getString("modules.profile.menu.items.squaremap.name", "&fПоказ на карте"),
                    List.of(
                            "&7Показ/скрытие игрока на карте squaremap",
                            "",
                            "&7Статус: &cнедоступно",
                            "&7Squaremap не найден или API ещё не готов"
                    ),
                    true
            );
        }

        squaremap.VisibilityState state = squaremapFeature.getVisibilityState(player);
        if (state == squaremap.VisibilityState.UNAVAILABLE) {
            return createItem(
                    Material.FILLED_MAP,
                    getString("modules.profile.menu.items.squaremap.name", "&fПоказ на карте"),
                    List.of(
                            "&7Показ/скрытие игрока на карте squaremap",
                            "",
                            "&7Статус: &cнедоступно",
                            "&7Squaremap не найден или API ещё не готов"
                    ),
                    true
            );
        }

        return createToggleItem(
                Material.FILLED_MAP,
                getString("modules.profile.menu.items.squaremap.name", "&fПоказ на карте"),
                state == squaremap.VisibilityState.SHOWN,
                getStringList(
                        "modules.profile.menu.items.squaremap.description",
                        List.of("&7Показ/скрытие игрока на карте squaremap")
                )
        );
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

    private String formatSquaremapState(Player player) {
        if (squaremapFeature == null || !squaremapFeature.isAvailable()) {
            return colorState("&cнедоступно");
        }

        squaremap.VisibilityState state = squaremapFeature.getVisibilityState(player);
        return switch (state) {
            case SHOWN -> formatState(true);
            case HIDDEN -> formatState(false);
            case UNAVAILABLE -> colorState("&cнедоступно");
        };
    }

    private String colorState(String value) {
        return color(value);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isProfileMenu(event.getView().getTopInventory())) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null) {
            return;
        }

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }

        ProfileMenuHolder holder = (ProfileMenuHolder) event.getView().getTopInventory().getHolder();
        if (holder == null) {
            return;
        }

        switch (holder.getMenuType()) {
            case CATEGORIES -> handleCategoriesClick(player, event.getRawSlot());
            case PROFILE_SETTINGS -> handleProfileSettingsClick(player, event.getRawSlot(), event.getView().getTopInventory());
        }
    }

    private void handleCategoriesClick(Player player, int slot) {
        switch (slot) {
            case CATEGORY_CENTER_SLOT -> openProfileSettingsMenu(player);
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handleProfileSettingsClick(Player player, int slot, Inventory inventory) {
        switch (slot) {
            case DEATH_SLOT -> {
                boolean isVisible = deathFeature.toggleVisible(player);
                player.sendMessage(color(getString(
                        isVisible ? "modules.profile.messages.death-enabled" : "modules.profile.messages.death-disabled",
                        isVisible
                                ? "&7Профиль: &fсмерть видна всем."
                                : "&7Профиль: &fсмерть скрыта."
                )));
                renderProfileSettingsMenu(player, inventory);
            }
            case ADVANCEMENT_SLOT -> {
                boolean isVisible = adventureFeature.toggleVisible(player);
                player.sendMessage(color(getString(
                        isVisible ? "modules.profile.messages.advancements-enabled" : "modules.profile.messages.advancements-disabled",
                        isVisible
                                ? "&7Профиль: &fдостижения видны всем."
                                : "&7Профиль: &fдостижения скрыты."
                )));
                renderProfileSettingsMenu(player, inventory);
            }
            case SQUAREMAP_SLOT -> {
                if (squaremapFeature == null) {
                    player.sendMessage(color(getString(
                            "modules.profile.messages.squaremap-unavailable",
                            "&cSquaremap не найден или ещё не загрузился."
                    )));
                    renderProfileSettingsMenu(player, inventory);
                    return;
                }

                squaremap.ToggleResult result = squaremapFeature.toggleVisible(player);
                switch (result) {
                    case SHOWN -> player.sendMessage(color(getString(
                            "modules.profile.messages.squaremap-enabled",
                            "&7Профиль: &fотображение на карте включено."
                    )));
                    case HIDDEN -> player.sendMessage(color(getString(
                            "modules.profile.messages.squaremap-disabled",
                            "&7Профиль: &fотображение на карте скрыто."
                    )));
                    case UNAVAILABLE -> player.sendMessage(color(getString(
                            "modules.profile.messages.squaremap-unavailable",
                            "&cSquaremap не найден или ещё не загрузился."
                    )));
                }

                renderProfileSettingsMenu(player, inventory);
            }
            case BACK_SLOT -> openCategoriesMenu(player);
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isProfileMenu(event.getView().getTopInventory())) {
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

    private boolean isProfileMenu(Inventory inventory) {
        return inventory.getHolder() instanceof ProfileMenuHolder;
    }

    private String getCategoriesMenuTitle() {
        return color(getString("modules.profile.menu.categories.title", "&8Профиль"));
    }

    private String getProfileSettingsMenuTitle() {
        return color(getString("modules.profile.menu.profile-settings.title", "&8Управление профилем"));
    }

    private ItemStack createItem(Material material, String displayName, List<String> lore, boolean hideAttributes) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(displayName));
        if (!lore.isEmpty()) {
            List<String> coloredLore = new ArrayList<>(lore.size());
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
        return (list == null || list.isEmpty()) ? def : list;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum MenuType {
        CATEGORIES,
        PROFILE_SETTINGS
    }

    private static final class ProfileMenuHolder implements InventoryHolder {
        private final MenuType menuType;
        private Inventory inventory;

        private ProfileMenuHolder(MenuType menuType) {
            this.menuType = menuType;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private MenuType getMenuType() {
            return menuType;
        }
    }
}