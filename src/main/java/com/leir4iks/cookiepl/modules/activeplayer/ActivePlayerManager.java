package com.leir4iks.cookiepl.modules.activeplayer;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActivePlayerManager implements Listener {
    private static final long DEFAULT_REQUIRED_ACTIVE_MS = 60L * 60L * 1000L;
    private static final long DEFAULT_ROLE_DURATION_MS = 3L * 24L * 60L * 60L * 1000L;

    private final CookiePl plugin;
    private final String configKey;
    private final File dataFile;
    private final HttpClient httpClient;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, GrantState> grantsByUuid = new ConcurrentHashMap<>();
    private final AtomicBoolean saveRunning = new AtomicBoolean(false);

    private WrappedTask monitorTask;
    private WrappedTask saveTask;
    private WrappedTask bootstrapTask;

    private volatile String endpointUrl;
    private volatile long requiredActiveMs;
    private volatile long roleDurationMs;
    private volatile long monitorPeriodTicks;
    private volatile long savePeriodTicks;
    private volatile int requestTimeoutSeconds;
    private volatile boolean databaseStateRestored;

    public ActivePlayerManager(CookiePl plugin, String configKey) {
        this.plugin = plugin;
        this.configKey = "modules." + configKey;
        this.dataFile = new File(plugin.getDataFolder(), "active-player.yml");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        reloadSettings();
        loadData();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SessionState state = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new SessionState());
            state.sessionStartMs = now;
        }
        monitorTask = plugin.getFoliaLib().getScheduler().runTimer(this::tick, 20L, Math.max(20L, monitorPeriodTicks));
        saveTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::saveDataSafe, Math.max(20L, savePeriodTicks), Math.max(20L, savePeriodTicks));
        bootstrapTask = plugin.getFoliaLib().getScheduler().runTimer(this::bootstrapOnlineSessions, 1L, 40L);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        cancel(monitorTask);
        cancel(saveTask);
        cancel(bootstrapTask);
        monitorTask = null;
        saveTask = null;
        bootstrapTask = null;
        saveNow();
        sessions.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SessionState state = sessions.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new SessionState());
        state.sessionStartMs = System.currentTimeMillis();
        state.initialized = false;
        state.sessionStartActiveMs = 0L;
        state.grantedThisSession = false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        DatabaseManager db = plugin.getWebDatabaseManager();
        bootstrapOnlineSessions();
        if (db != null && !databaseStateRestored) restoreDatabaseState(db, now);
        evaluateOnlinePlayers(db, now);
        revokeExpiredOffline(now, db);
        syncPendingStates();
    }

    private void bootstrapOnlineSessions() {
        DatabaseManager db = plugin.getWebDatabaseManager();
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SessionState state = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new SessionState());
            if (state.sessionStartMs <= 0L) state.sessionStartMs = now;
            if (!state.initialized && db != null) {
                state.sessionStartActiveMs = Math.max(0L, db.getActivePlaytimeMillis(player.getUniqueId()));
                state.initialized = true;
            }
        }
    }

    private void restoreDatabaseState(DatabaseManager db, long now) {
        for (Map.Entry<String, GrantState> entry : grantsByUuid.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.getKey());
            } catch (Exception ignored) {
                continue;
            }
            GrantState grant = entry.getValue();
            synchronized (grant) {
                if (isBlank(grant.discordId)) {
                    String discordId = db.getDiscordIdByMinecraftUuid(uuid);
                    if (!isBlank(discordId)) grant.discordId = discordId;
                }
                boolean shouldBeActive = grant.active && grant.activeUntilMs > now;
                if (grant.active != shouldBeActive) {
                    grant.active = shouldBeActive;
                    if (!shouldBeActive) grant.activeUntilMs = 0L;
                    grant.lastSyncedState = null;
                }
                if (!isBlank(grant.discordId)) db.setPlayerActive(grant.discordId, shouldBeActive);
            }
        }
        databaseStateRestored = true;
    }

    private void evaluateOnlinePlayers(DatabaseManager db, long now) {
        if (db == null) return;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            SessionState session = sessions.computeIfAbsent(uuid, ignored -> new SessionState());
            if (session.sessionStartMs <= 0L) session.sessionStartMs = now;
            if (!session.initialized) {
                session.sessionStartActiveMs = Math.max(0L, db.getActivePlaytimeMillis(uuid));
                session.initialized = true;
                continue;
            }

            GrantState grant = grantsByUuid.computeIfAbsent(uuid.toString(), ignored -> new GrantState());
            synchronized (grant) {
                if (isBlank(grant.discordId)) {
                    String discordId = db.getDiscordIdByMinecraftUuid(uuid);
                    if (!isBlank(discordId)) grant.discordId = discordId;
                }
            }

            if (session.grantedThisSession) continue;

            long currentActiveMs = Math.max(0L, db.getActivePlaytimeMillis(uuid));
            if (currentActiveMs - session.sessionStartActiveMs < requiredActiveMs) continue;
            if (grantRole(uuid, grant, db, now)) session.grantedThisSession = true;
        }
    }

    private boolean grantRole(UUID uuid, GrantState grant, DatabaseManager db, long now) {
        String discordId;
        synchronized (grant) {
            if (isBlank(grant.discordId)) {
                String resolved = db.getDiscordIdByMinecraftUuid(uuid);
                if (!isBlank(resolved)) grant.discordId = resolved;
            }
            discordId = grant.discordId;
            if (isBlank(discordId)) return false;
            grant.active = true;
            grant.activeUntilMs = now + Math.max(1000L, roleDurationMs);
            grant.lastSyncedState = null;
        }
        db.setPlayerActive(discordId, true);
        queueSync(grant);
        return true;
    }

    private void revokeExpiredOffline(long now, DatabaseManager db) {
        for (Map.Entry<String, GrantState> entry : grantsByUuid.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.getKey());
            } catch (Exception ignored) {
                continue;
            }
            if (plugin.getServer().getPlayer(uuid) != null) continue;

            GrantState grant = entry.getValue();
            String discordId = null;
            boolean changed = false;

            synchronized (grant) {
                if (!grant.active) continue;
                if (grant.activeUntilMs <= 0L || grant.activeUntilMs > now) continue;
                grant.active = false;
                grant.activeUntilMs = 0L;
                grant.lastSyncedState = null;
                discordId = grant.discordId;
                changed = true;
            }

            if (!changed) continue;
            if (db != null && !isBlank(discordId)) db.setPlayerActive(discordId, false);
            queueSync(grant);
        }
    }

    private void syncPendingStates() {
        for (GrantState grant : grantsByUuid.values()) queueSync(grant);
    }

    private void queueSync(GrantState grant) {
        String discordId;
        boolean active;
        synchronized (grant) {
            if (grant.syncInProgress) return;
            if (isBlank(grant.discordId)) return;
            if (grant.lastSyncedState != null && grant.lastSyncedState == grant.active) return;
            grant.syncInProgress = true;
            discordId = grant.discordId;
            active = grant.active;
        }

        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            boolean success = postState(discordId, active);
            synchronized (grant) {
                grant.syncInProgress = false;
                if (success && grant.active == active) grant.lastSyncedState = active;
                else if (!success && grant.active == active) grant.lastSyncedState = null;
            }
        });
    }

    private boolean postState(String discordId, boolean active) {
        try {
            String json = "{\"discord_id\":\"" + escapeJson(discordId) + "\",\"active\":" + active + "}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) return true;
            plugin.getLogManager().warn("ActivePlayer API returned " + code + " for discord_id=" + discordId + ": " + response.body());
        } catch (Exception e) {
            plugin.getLogManager().warn("ActivePlayer API request failed for discord_id=" + discordId + ": " + e.getMessage());
        }
        return false;
    }

    private void reloadSettings() {
        requiredActiveMs = Math.max(1000L, plugin.getConfig().getLong(configKey + ".required-active-seconds", DEFAULT_REQUIRED_ACTIVE_MS / 1000L) * 1000L);
        roleDurationMs = Math.max(1000L, plugin.getConfig().getLong(configKey + ".role-duration-seconds", DEFAULT_ROLE_DURATION_MS / 1000L) * 1000L);
        monitorPeriodTicks = Math.max(20L, plugin.getConfig().getLong(configKey + ".check-period-ticks", 100L));
        savePeriodTicks = Math.max(20L, plugin.getConfig().getLong(configKey + ".save-period-ticks", 1200L));
        requestTimeoutSeconds = Math.max(1, plugin.getConfig().getInt(configKey + ".request-timeout-seconds", 10));
        endpointUrl = normalizeEndpoint(plugin.getConfig().getString(configKey + ".api-url", "http://212.80.7.211:20081/active"));
    }

    private String normalizeEndpoint(String value) {
        String v = value == null ? "" : value.trim();
        if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) v = "http://212.80.7.211:20081/active";
        if (!v.toLowerCase(Locale.ROOT).endsWith("/active")) v = v + "/active";
        return v;
    }

    private void loadData() {
        grantsByUuid.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        if (yaml.getConfigurationSection("players") == null) return;

        for (String key : yaml.getConfigurationSection("players").getKeys(false)) {
            String base = "players." + key;
            GrantState grant = new GrantState();
            grant.discordId = blankToNull(yaml.getString(base + ".discord-id", ""));
            grant.active = yaml.getBoolean(base + ".active", false);
            grant.activeUntilMs = Math.max(0L, yaml.getLong(base + ".active-until-ms", 0L));
            grant.lastSyncedState = grant.active ? null : Boolean.FALSE;
            grant.syncInProgress = false;
            grantsByUuid.put(key, grant);
        }
    }

    private void saveDataSafe() {
        saveNow();
    }

    private void saveNow() {
        if (!saveRunning.compareAndSet(false, true)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, GrantState> entry : grantsByUuid.entrySet()) {
                GrantState grant = entry.getValue();
                String base = "players." + entry.getKey();
                synchronized (grant) {
                    if (!isBlank(grant.discordId)) yaml.set(base + ".discord-id", grant.discordId);
                    yaml.set(base + ".active", grant.active);
                    yaml.set(base + ".active-until-ms", Math.max(0L, grant.activeUntilMs));
                }
            }
            File parent = dataFile.getParentFile();
            if (parent != null) parent.mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogManager().warn("Failed to save active-player data: " + e.getMessage());
        } finally {
            saveRunning.set(false);
        }
    }

    private static void cancel(WrappedTask task) {
        try {
            if (task != null) task.cancel();
        } catch (Exception ignored) {}
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static final class SessionState {
        long sessionStartMs;
        long sessionStartActiveMs;
        boolean initialized;
        boolean grantedThisSession;
    }

    private static final class GrantState {
        String discordId;
        boolean active;
        long activeUntilMs;
        Boolean lastSyncedState;
        boolean syncInProgress;
    }
}
