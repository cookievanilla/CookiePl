package com.leir4iks.cookiepl.modules.antigrief;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class AntiGriefManager implements Listener {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MAX_TRACK_DELTA_MILLIS = 60_000L;
    private static final double MULTI_WORLD_SPREAD_SENTINEL = 99_999.0D;
    private static final String DEFAULT_UNKNOWN_TARGET = "unknown";
    private static final Statistic PLAY_TIME_STATISTIC = resolvePlayTimeStatistic();
    private static final ThreadLocal<DecimalFormat> SPREAD_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setGroupingUsed(false);
        return format;
    });
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final CookiePl plugin;
    private final String configKey;
    private final File dataFile;
    private final ConcurrentMap<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EventWindow> windows = new ConcurrentHashMap<>();
    private final Set<UUID> pendingExternalImport = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean saveRunning = new AtomicBoolean(false);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private WrappedTask activeTickTask;
    private WrappedTask saveTask;
    private WrappedTask importRetryTask;

    private long requiredActiveTimeMillis;
    private long idleTimeoutMillis;
    private long newbieMonitorMaxActiveMillis;
    private long monitorWindowMillis;
    private int openMinUniqueTargets;
    private int breakMinUniqueTargets;
    private double openMinSpreadBlocks;
    private double breakMinSpreadBlocks;
    private long openAlertCooldownMillis;
    private long breakAlertCooldownMillis;
    private boolean webhookEnabled;
    private boolean webServerExpected;
    private String webhookUrl;
    private String webhookUsername;
    private String webhookAvatarUrl;
    private String webhookMention;
    private int webhookTimeoutSeconds;
    private String restrictedMessage;
    private String openAlertMessage;
    private String breakAlertMessage;
    private String permissionBypass;
    private DateTimeFormatter timeFormatter;
    private long saveIntervalTicks;
    private Map<String, String> itemDisplayNames = Map.of();
    private Map<Material, String> targetDisplayNames = Map.of();

    public AntiGriefManager(CookiePl plugin, AntiGriefModule module) {
        this.plugin = plugin;
        this.configKey = "modules." + module.getConfigKey();
        this.dataFile = new File(plugin.getDataFolder(), "antigrief-data.yml");
        reloadSettings();
        loadStoredData();
    }

    public void start() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            initializePlayerState(player, now);
        }
        this.activeTickTask = plugin.getFoliaLib().getScheduler().runTimer(this::tickActiveTime, 20L, 20L);
        this.saveTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::saveDataAsync, this.saveIntervalTicks, this.saveIntervalTicks);
        this.importRetryTask = plugin.getFoliaLib().getScheduler().runTimer(this::retryPendingImports, 200L, 1200L);
    }

    public void shutdown() {
        cancelTask(this.activeTickTask);
        cancelTask(this.saveTask);
        cancelTask(this.importRetryTask);
        this.activeTickTask = null;
        this.saveTask = null;
        this.importRetryTask = null;
        flushOnlineTime();
        saveNow();
        this.windows.clear();
        this.pendingExternalImport.clear();
    }

    private void reloadSettings() {
        this.requiredActiveTimeMillis = Math.max(0L, plugin.getConfig().getLong(configKey + ".required-active-time-seconds", 10800L) * MILLIS_PER_SECOND);
        this.idleTimeoutMillis = Math.max(MILLIS_PER_SECOND, plugin.getConfig().getLong(configKey + ".idle-timeout-seconds", 300L) * MILLIS_PER_SECOND);
        this.newbieMonitorMaxActiveMillis = Math.max(0L, plugin.getConfig().getLong(configKey + ".container-monitor.newbie-max-active-time-seconds", 7200L) * MILLIS_PER_SECOND);
        this.monitorWindowMillis = Math.max(MILLIS_PER_SECOND, plugin.getConfig().getLong(configKey + ".container-monitor.window-seconds", 300L) * MILLIS_PER_SECOND);
        this.openMinUniqueTargets = Math.max(1, plugin.getConfig().getInt(configKey + ".container-monitor.open.min-unique-targets", 8));
        this.breakMinUniqueTargets = Math.max(1, plugin.getConfig().getInt(configKey + ".container-monitor.break.min-unique-targets", 5));
        this.openMinSpreadBlocks = Math.max(0.0D, plugin.getConfig().getDouble(configKey + ".container-monitor.open.min-spread-blocks", 24.0D));
        this.breakMinSpreadBlocks = Math.max(0.0D, plugin.getConfig().getDouble(configKey + ".container-monitor.break.min-spread-blocks", 24.0D));
        this.openAlertCooldownMillis = Math.max(0L, plugin.getConfig().getLong(configKey + ".container-monitor.open.alert-cooldown-seconds", 900L) * MILLIS_PER_SECOND);
        this.breakAlertCooldownMillis = Math.max(0L, plugin.getConfig().getLong(configKey + ".container-monitor.break.alert-cooldown-seconds", 900L) * MILLIS_PER_SECOND);
        this.webhookEnabled = plugin.getConfig().getBoolean(configKey + ".webhook.enabled", false);
        this.webServerExpected = plugin.getConfig().getBoolean("modules.web-server.enabled", false);
        this.webhookUrl = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.url", ""));
        this.webhookUsername = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.username", "CookiePl AntiGrief"));
        this.webhookAvatarUrl = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.avatar-url", ""));
        this.webhookMention = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.mention", ""));
        this.webhookTimeoutSeconds = Math.max(1, plugin.getConfig().getInt(configKey + ".webhook.timeout-seconds", 10));
        this.restrictedMessage = color(plugin.getConfig().getString(configKey + ".messages.restricted-item", "&cЧтобы использовать {item}, нужно минимум {required}. Сейчас у тебя {current}."));
        this.openAlertMessage = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.messages.open", "{mention}🚨 Игрок **{player}** за период **{period}** открыл слишком много объектов в разных местах. Активное время: **{active_time}**. Уникальных объектов: **{count}**. Миры: **{worlds}**. Разброс: **{spread}** блоков. Детали: **{details}**."));
        this.breakAlertMessage = blankToEmpty(plugin.getConfig().getString(configKey + ".webhook.messages.break", "{mention}🚨 Игрок **{player}** за период **{period}** сломал слишком много объектов в разных местах. Активное время: **{active_time}**. Уникальных объектов: **{count}**. Миры: **{worlds}**. Разброс: **{spread}** блоков. Детали: **{details}**."));
        this.permissionBypass = plugin.getConfig().getString(configKey + ".bypass-permission", "cookiepl.antigrief.bypass");
        this.saveIntervalTicks = Math.max(20L, plugin.getConfig().getLong(configKey + ".save-interval-seconds", 60L) * 20L);

        String zoneId = plugin.getConfig().getString(configKey + ".time-zone", ZoneId.systemDefault().getId());
        String timePattern = plugin.getConfig().getString(configKey + ".time-format", "dd.MM.yyyy HH:mm:ss");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (Exception ignored) {
            zone = ZoneId.systemDefault();
        }
        this.timeFormatter = DateTimeFormatter.ofPattern(timePattern).withZone(zone);
        this.itemDisplayNames = loadItemDisplayNames();
        this.targetDisplayNames = loadTargetDisplayNames();
    }

    private Map<String, String> loadItemDisplayNames() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(configKey + ".item-names");
        if (section == null) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (String key : section.getKeys(false)) {
            names.put(key, color(section.getString(key, key)));
        }
        return Map.copyOf(names);
    }

    private Map<Material, String> loadTargetDisplayNames() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(configKey + ".target-names");
        Map<String, String> overrides = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = blankToEmpty(section.getString(key, key));
                if (!value.isBlank()) {
                    overrides.put(key.toLowerCase(Locale.ROOT), value);
                }
            }
        }

        String genericShulkerName = overrides.getOrDefault("shulker-box", "шалкер");
        Map<Material, String> names = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            String key = material.name().toLowerCase(Locale.ROOT).replace('_', '-');
            String configured = overrides.get(key);
            if (configured != null && !configured.isBlank()) {
                names.put(material, configured);
                continue;
            }
            if (material.name().endsWith("SHULKER_BOX") && !genericShulkerName.isBlank()) {
                names.put(material, genericShulkerName);
                continue;
            }
            names.put(material, key);
        }
        return Map.copyOf(names);
    }

    private void loadStoredData() {
        if (!this.dataFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.dataFile);
        ConfigurationSection playersSection = yaml.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String rawUuid : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                long totalActiveMs = Math.max(0L, yaml.getLong("players." + rawUuid + ".active-ms", 0L));
                String lastName = blankToEmpty(yaml.getString("players." + rawUuid + ".last-name", ""));
                PlayerState state = new PlayerState();
                state.totalActiveMs = totalActiveMs;
                state.lastName = lastName;
                state.externalImportResolved = true;
                playerStates.put(uuid, state);
            } catch (Exception ignored) {
            }
        }
    }

    private void tickActiveTime() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerState state = initializePlayerState(player, now);
            accumulateActiveTime(player, state, now, true);
        }
    }

    private void flushOnlineTime() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerState state = initializePlayerState(player, now);
            accumulateActiveTime(player, state, now, true);
        }
    }

    private void accumulateActiveTime(Player player, PlayerState state, long now, boolean requireOnline) {
        synchronized (state) {
            long previousTick = state.lastTickMs == 0L ? now : state.lastTickMs;
            long delta = clampDelta(now - previousTick);
            state.lastTickMs = now;
            if (delta > 0L && shouldCountAsActive(player, state, now, requireOnline)) {
                state.totalActiveMs += delta;
                state.dirty = true;
            }
        }
    }

    private long clampDelta(long delta) {
        if (delta <= 0L) {
            return 0L;
        }
        return Math.min(delta, MAX_TRACK_DELTA_MILLIS);
    }

    private boolean shouldCountAsActive(Player player, PlayerState state, long now, boolean requireOnline) {
        if (player == null) {
            return false;
        }
        if (requireOnline && !player.isOnline()) {
            return false;
        }
        if (isAfk(player)) {
            return false;
        }
        return now - state.lastActivityMs <= this.idleTimeoutMillis;
    }

    private boolean isAfk(Player player) {
        try {
            return player.hasMetadata("afk") && !player.getMetadata("afk").isEmpty() && player.getMetadata("afk").get(0).asBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private PlayerState initializePlayerState(Player player, long now) {
        PlayerState state = this.playerStates.computeIfAbsent(player.getUniqueId(), uuid -> {
            PlayerState created = new PlayerState();
            created.totalActiveMs = loadApproximatePlaytimeMillis(player);
            created.externalImportResolved = false;
            created.dirty = true;
            return created;
        });

        boolean shouldQueueImport = false;
        synchronized (state) {
            if (state.lastActivityMs == 0L) {
                state.lastActivityMs = now;
            }
            if (state.lastTickMs == 0L) {
                state.lastTickMs = now;
            }
            String playerName = player.getName();
            if (!playerName.equals(state.lastName)) {
                state.lastName = playerName;
                state.dirty = true;
            }
            if (!state.externalImportResolved && !state.externalImportInProgress) {
                shouldQueueImport = true;
            }
        }

        if (shouldQueueImport) {
            this.pendingExternalImport.add(player.getUniqueId());
            tryExternalImport(player.getUniqueId(), player.getName());
        }
        return state;
    }

    private void tryExternalImport(UUID uuid, String playerName) {
        if (!this.pendingExternalImport.contains(uuid)) {
            return;
        }

        PlayerState state = playerStates.get(uuid);
        if (state == null) {
            this.pendingExternalImport.remove(uuid);
            return;
        }

        synchronized (state) {
            if (state.externalImportResolved || state.externalImportInProgress) {
                this.pendingExternalImport.remove(uuid);
                return;
            }
            state.externalImportInProgress = true;
        }

        this.pendingExternalImport.remove(uuid);
        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            DatabaseManager databaseManager = plugin.getWebDatabaseManager();
            if (databaseManager == null && webServerExpected) {
                this.pendingExternalImport.add(uuid);
                synchronized (state) {
                    state.externalImportResolved = false;
                    state.externalImportInProgress = false;
                }
                return;
            }

            long external = readExternalActivePlaytimeMillis(uuid);
            synchronized (state) {
                if (external > state.totalActiveMs) {
                    state.totalActiveMs = external;
                    state.dirty = true;
                }
                state.externalImportResolved = true;
                state.externalImportInProgress = false;
                if (!playerName.equals(state.lastName)) {
                    state.lastName = playerName;
                    state.dirty = true;
                }
            }
        });
    }

    private void retryPendingImports() {
        if (this.pendingExternalImport.isEmpty()) {
            return;
        }
        for (UUID uuid : this.pendingExternalImport) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                tryExternalImport(uuid, player.getName());
            }
        }
    }

    private long readExternalActivePlaytimeMillis(UUID uuid) {
        long best = 0L;
        try {
            DatabaseManager databaseManager = plugin.getWebDatabaseManager();
            if (databaseManager != null) {
                best = Math.max(best, databaseManager.getActivePlaytimeMillis(uuid));
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private long loadApproximatePlaytimeMillis(Player player) {
        if (PLAY_TIME_STATISTIC == null) {
            return 0L;
        }
        try {
            return Math.max(0L, player.getStatistic(PLAY_TIME_STATISTIC)) * 50L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Statistic resolvePlayTimeStatistic() {
        for (String name : new String[]{"PLAY_TIME", "PLAY_ONE_MINUTE"}) {
            try {
                return (Statistic) Enum.valueOf((Class<Enum>) Statistic.class, name);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private long getActiveTimeMillis(Player player) {
        PlayerState state = initializePlayerState(player, System.currentTimeMillis());
        synchronized (state) {
            return Math.max(0L, state.totalActiveMs);
        }
    }

    private boolean hasBypass(Player player) {
        return permissionBypass != null && !permissionBypass.isBlank() && player.hasPermission(permissionBypass);
    }

    private boolean shouldRestrict(Player player) {
        return player != null && player.isOnline() && !hasBypass(player) && getActiveTimeMillis(player) < this.requiredActiveTimeMillis;
    }

    private boolean shouldMonitor(Player player) {
        return player != null && player.isOnline() && !hasBypass(player) && getActiveTimeMillis(player) < this.newbieMonitorMaxActiveMillis;
    }

    private boolean blockRestrictedUse(Player player, CancellableAdapter adapter, String itemKey) {
        if (!shouldRestrict(player)) {
            return false;
        }
        adapter.cancel();
        long current = getActiveTimeMillis(player);
        player.sendMessage(buildRestrictedMessage(itemKey, current));
        return true;
    }

    private String buildRestrictedMessage(String itemKey, long currentActiveTimeMillis) {
        String itemName = this.itemDisplayNames.getOrDefault(itemKey, itemKey);
        return this.restrictedMessage
                .replace("{item}", itemName)
                .replace("{required}", formatDuration(this.requiredActiveTimeMillis))
                .replace("{current}", formatDuration(currentActiveTimeMillis));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        markActivity(player);

        Material type = event.getBlockPlaced().getType();
        if (type == Material.TNT) {
            blockRestrictedUse(player, () -> event.setCancelled(true), "tnt");
            return;
        }
        if (type == Material.RESPAWN_ANCHOR) {
            blockRestrictedUse(player, () -> event.setCancelled(true), "respawn-anchor");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        markActivity(player);

        ItemStack item = event.getItem();
        Material itemType = item == null ? Material.AIR : item.getType();
        Block clickedBlock = event.getClickedBlock();

        if (itemType == Material.FLINT_AND_STEEL
                && (clickedBlock == null || clickedBlock.getType() != Material.OBSIDIAN)
                && blockRestrictedUse(player, () -> event.setCancelled(true), "flint-and-steel")) {
            return;
        }

        if (itemType == Material.FIRE_CHARGE
                && (clickedBlock == null || clickedBlock.getType() != Material.OBSIDIAN)
                && blockRestrictedUse(player, () -> event.setCancelled(true), "fire-charge")) {
            return;
        }

        if (itemType == Material.END_CRYSTAL && blockRestrictedUse(player, () -> event.setCancelled(true), "end-crystal")) {
            return;
        }

        if (clickedBlock != null && clickedBlock.getType() == Material.RESPAWN_ANCHOR
                && blockRestrictedUse(player, () -> event.setCancelled(true), "respawn-anchor")) {
            return;
        }

        if (clickedBlock != null && isBed(clickedBlock.getType()) && isExplosiveBedWorld(clickedBlock.getWorld())
                && blockRestrictedUse(player, () -> event.setCancelled(true), "bed")) {
            return;
        }

        if (event.getHand() == EquipmentSlot.HAND
                && clickedBlock != null
                && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && isTrackedContainerBlock(clickedBlock.getType())) {
            recordSuspiciousAction(player, ActionType.OPEN, typeNameFor(clickedBlock.getType()), clickedBlock.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        markActivity(player);

        Entity entity = event.getRightClicked();
        if (entity instanceof Villager) {
            recordSuspiciousAction(player, ActionType.OPEN, "villager", entity.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        markActivity(player);

        Block block = event.getBlock();
        if (isTrackedContainerBlock(block.getType())) {
            recordSuspiciousAction(player, ActionType.BREAK, typeNameFor(block.getType()), block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        Player killer = villager.getKiller();
        if (killer == null) {
            return;
        }

        markActivity(killer);
        recordSuspiciousAction(killer, ActionType.BREAK, "villager", villager.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        PlayerState state = initializePlayerState(player, now);
        accumulateActiveTime(player, state, now, false);
        clearPlayerWindows(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) {
            markActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        plugin.getFoliaLib().getScheduler().runAtEntity(player, task -> markActivity(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActivity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActivity(player);
        }
    }

    private void markActivity(Player player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState state = initializePlayerState(player, now);
        synchronized (state) {
            state.lastActivityMs = now;
            String playerName = player.getName();
            if (!playerName.equals(state.lastName)) {
                state.lastName = playerName;
                state.dirty = true;
            }
        }
    }

    private void recordSuspiciousAction(Player player, ActionType actionType, String targetType, Location location) {
        if (!shouldMonitor(player) || location == null || location.getWorld() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String targetKey = buildTargetKey(location, targetType);
        String windowKey = buildWindowKey(player.getUniqueId(), actionType);
        EventWindow window = this.windows.computeIfAbsent(windowKey, key -> new EventWindow(actionType));
        AlertSnapshot snapshot = window.record(
                now,
                targetKey,
                targetType,
                location,
                this.monitorWindowMillis,
                actionType == ActionType.OPEN ? this.openMinUniqueTargets : this.breakMinUniqueTargets,
                actionType == ActionType.OPEN ? this.openMinSpreadBlocks : this.breakMinSpreadBlocks,
                actionType == ActionType.OPEN ? this.openAlertCooldownMillis : this.breakAlertCooldownMillis
        );
        if (snapshot == null) {
            return;
        }

        long activeTime = getActiveTimeMillis(player);
        sendWebhookAlert(player.getName(), activeTime, snapshot);
    }

    private String buildWindowKey(UUID uuid, ActionType actionType) {
        return uuid + ":" + actionType.name();
    }

    private void clearPlayerWindows(UUID uuid) {
        this.windows.remove(buildWindowKey(uuid, ActionType.OPEN));
        this.windows.remove(buildWindowKey(uuid, ActionType.BREAK));
    }

    private void sendWebhookAlert(String playerName, long activeTime, AlertSnapshot snapshot) {
        if (!this.webhookEnabled || this.webhookUrl.isBlank()) {
            return;
        }

        String content = buildWebhookContent(playerName, activeTime, snapshot);
        plugin.getFoliaLib().getScheduler().runAsync(task -> postWebhook(content));
    }

    private String buildWebhookContent(String playerName, long activeTime, AlertSnapshot snapshot) {
        String template = snapshot.actionType() == ActionType.OPEN ? this.openAlertMessage : this.breakAlertMessage;
        return template
                .replace("{mention}", this.webhookMention)
                .replace("{player}", playerName)
                .replace("{active_time}", formatDuration(activeTime))
                .replace("{count}", String.valueOf(snapshot.uniqueTargets()))
                .replace("{spread}", formatSpread(snapshot.spread()))
                .replace("{period}", formatPeriod(snapshot.firstTimestamp(), snapshot.lastTimestamp()))
                .replace("{worlds}", String.join(", ", snapshot.worlds()))
                .replace("{details}", snapshot.details());
    }

    private String formatSpread(double spread) {
        return SPREAD_FORMAT.get().format(spread);
    }

    private void postWebhook(String content) {
        try {
            String json = buildWebhookJson(content);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.webhookUrl))
                    .timeout(Duration.ofSeconds(this.webhookTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                plugin.getLogManager().warn("AntiGrief webhook returned status " + status);
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("AntiGrief webhook error: " + e.getMessage());
        }
    }

    private String buildWebhookJson(String content) {
        StringBuilder sb = new StringBuilder(content.length() + this.webhookUsername.length() + this.webhookAvatarUrl.length() + 64);
        sb.append('{');
        sb.append("\"content\":\"").append(jsonEscape(content)).append('"');
        if (!this.webhookUsername.isBlank()) {
            sb.append(",\"username\":\"").append(jsonEscape(this.webhookUsername)).append('"');
        }
        if (!this.webhookAvatarUrl.isBlank()) {
            sb.append(",\"avatar_url\":\"").append(jsonEscape(this.webhookAvatarUrl)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        appendUnicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u");
        sb.append(HEX_DIGITS[(c >>> 12) & 0xF]);
        sb.append(HEX_DIGITS[(c >>> 8) & 0xF]);
        sb.append(HEX_DIGITS[(c >>> 4) & 0xF]);
        sb.append(HEX_DIGITS[c & 0xF]);
    }

    private String buildTargetKey(Location location, String targetType) {
        return location.getWorld().getName()
                + ':' + location.getBlockX()
                + ':' + location.getBlockY()
                + ':' + location.getBlockZ()
                + ':' + targetType.toLowerCase(Locale.ROOT);
    }

    private boolean isTrackedContainerBlock(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case CHEST, TRAPPED_CHEST, BARREL -> true;
            default -> material.name().endsWith("SHULKER_BOX");
        };
    }

    private boolean isBed(Material material) {
        return material != null && material.name().endsWith("_BED");
    }

    private boolean isExplosiveBedWorld(World world) {
        if (world == null) {
            return false;
        }
        World.Environment environment = world.getEnvironment();
        return environment == World.Environment.NETHER || environment == World.Environment.THE_END;
    }

    private String typeNameFor(Material material) {
        if (material == null) {
            return DEFAULT_UNKNOWN_TARGET;
        }
        return this.targetDisplayNames.getOrDefault(material, DEFAULT_UNKNOWN_TARGET);
    }

    private String formatDuration(long millis) {
        long totalMinutes = Math.max(0L, millis / MILLIS_PER_MINUTE);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours + "ч " + minutes + "м";
    }

    private String formatPeriod(long first, long last) {
        if (first <= 0L || last <= 0L) {
            return "неизвестно";
        }
        return this.timeFormatter.format(Instant.ofEpochMilli(first)) + " — " + this.timeFormatter.format(Instant.ofEpochMilli(last));
    }

    private String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void saveDataAsync() {
        saveNow();
    }

    private void saveNow() {
        if (!this.saveRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            List<PlayerSaveSnapshot> snapshots = new ArrayList<>(this.playerStates.size());
            boolean hasDirty = false;

            for (Map.Entry<UUID, PlayerState> entry : this.playerStates.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerState state = entry.getValue();
                long total;
                String lastName;
                boolean dirty;
                synchronized (state) {
                    total = Math.max(0L, state.totalActiveMs);
                    lastName = blankToEmpty(state.lastName);
                    dirty = state.dirty;
                }
                hasDirty |= dirty;
                snapshots.add(new PlayerSaveSnapshot(uuid, state, total, lastName, dirty));
            }

            if (!hasDirty) {
                return;
            }

            snapshots.sort(Comparator.comparing(snapshot -> snapshot.uuid().toString()));

            YamlConfiguration yaml = new YamlConfiguration();
            for (PlayerSaveSnapshot snapshot : snapshots) {
                String path = "players." + snapshot.uuid();
                yaml.set(path + ".active-ms", snapshot.totalActiveMs());
                yaml.set(path + ".last-name", snapshot.lastName());
            }

            File parent = this.dataFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                plugin.getLogManager().warn("Failed to create antigrief data directory: " + parent.getAbsolutePath());
                return;
            }

            yaml.save(this.dataFile);

            for (PlayerSaveSnapshot snapshot : snapshots) {
                if (!snapshot.dirty()) {
                    continue;
                }
                synchronized (snapshot.state()) {
                    if (snapshot.state().totalActiveMs == snapshot.totalActiveMs()
                            && Objects.equals(snapshot.state().lastName, snapshot.lastName())) {
                        snapshot.state().dirty = false;
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogManager().warn("Failed to save antigrief data: " + e.getMessage());
        } finally {
            this.saveRunning.set(false);
        }
    }

    private void cancelTask(WrappedTask task) {
        try {
            if (task != null) {
                task.cancel();
            }
        } catch (Exception ignored) {
        }
    }

    private enum ActionType {
        OPEN,
        BREAK
    }

    private static final class PlayerState {
        long totalActiveMs;
        long lastActivityMs;
        long lastTickMs;
        String lastName = "";
        boolean externalImportResolved;
        boolean dirty;
        boolean externalImportInProgress;
    }

    private record PlayerSaveSnapshot(UUID uuid, PlayerState state, long totalActiveMs, String lastName, boolean dirty) {
    }

    private record EventRecord(long timestamp, String targetKey, String targetType, String world, int x, int y, int z) {
    }

    private record AlertSnapshot(ActionType actionType, long firstTimestamp, long lastTimestamp, int uniqueTargets, double spread,
                                 List<String> worlds, String details) {
    }

    private static final class EventWindow {
        private final ActionType actionType;
        private final Deque<EventRecord> records = new ArrayDeque<>();
        private long lastAlertMs;

        private EventWindow(ActionType actionType) {
            this.actionType = actionType;
        }

        private synchronized AlertSnapshot record(long now, String targetKey, String targetType, Location location,
                                                  long windowMillis, int minUniqueTargets, double minSpreadBlocks, long cooldownMillis) {
            this.records.addLast(new EventRecord(
                    now,
                    targetKey,
                    targetType,
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            ));
            prune(now, windowMillis);

            if (now - this.lastAlertMs < cooldownMillis) {
                return null;
            }

            Map<String, EventRecord> uniqueByTarget = new HashMap<>();
            Set<String> worlds = new HashSet<>();
            Map<String, Integer> typeCounts = new HashMap<>();
            long first = 0L;
            long last = 0L;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;

            for (EventRecord record : this.records) {
                uniqueByTarget.putIfAbsent(record.targetKey(), record);
                worlds.add(record.world());
                if (first == 0L || record.timestamp() < first) {
                    first = record.timestamp();
                }
                if (record.timestamp() > last) {
                    last = record.timestamp();
                }
            }

            for (EventRecord record : uniqueByTarget.values()) {
                minX = Math.min(minX, record.x());
                maxX = Math.max(maxX, record.x());
                minZ = Math.min(minZ, record.z());
                maxZ = Math.max(maxZ, record.z());
                typeCounts.merge(record.targetType(), 1, Integer::sum);
            }

            int uniqueTargets = uniqueByTarget.size();
            boolean multiWorld = worlds.size() > 1;
            double spread = multiWorld
                    ? MULTI_WORLD_SPREAD_SENTINEL
                    : Math.hypot(Math.max(0, maxX - minX), Math.max(0, maxZ - minZ));
            if (uniqueTargets < minUniqueTargets || spread < minSpreadBlocks) {
                return null;
            }

            this.lastAlertMs = now;

            List<String> worldsList = new ArrayList<>(worlds);
            worldsList.sort(String::compareToIgnoreCase);

            List<Map.Entry<String, Integer>> sortedTypeCounts = new ArrayList<>(typeCounts.entrySet());
            sortedTypeCounts.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            List<String> detailParts = new ArrayList<>(sortedTypeCounts.size());
            for (Map.Entry<String, Integer> entry : sortedTypeCounts) {
                detailParts.add(entry.getKey() + "=" + entry.getValue());
            }

            return new AlertSnapshot(this.actionType, first, last, uniqueTargets, spread, worldsList, String.join(", ", detailParts));
        }

        private void prune(long now, long windowMillis) {
            long minTimestamp = now - windowMillis;
            while (!this.records.isEmpty() && this.records.peekFirst().timestamp() < minTimestamp) {
                this.records.removeFirst();
            }
        }
    }

    @FunctionalInterface
    private interface CancellableAdapter {
        void cancel();
    }
}
