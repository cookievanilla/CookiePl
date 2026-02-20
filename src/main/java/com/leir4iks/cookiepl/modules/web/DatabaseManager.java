package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DatabaseManager {

    private static final String SKINS_DATABASE_URL = "http://212.80.7.214:20945/skins";
    private static final String EXTERNAL_NAME_URL_DEFAULT = "http://212.80.7.211:20081/name";
    private static final String EXTERNAL_WHITELIST_URL_DEFAULT = "http://212.80.7.211:20081/whitelist";
    private static final String EXTERNAL_STATS_URL_DEFAULT = "http://212.80.7.211:20081/stats";

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final File dataFile;

    private final Map<String, String> externalCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsHeadUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsNameHeadUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsSkinUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsNameSkinUrlCache = new ConcurrentHashMap<>();

    private volatile long userCacheLastModified = -1L;
    private volatile Map<String, String> userCacheMap = Map.of();

    private volatile long accountsLastModified = -1L;
    private volatile List<AccountEntry> accountsList = List.of();

    private volatile List<File> statsFolders = List.of();
    private final Map<String, StatsCacheEntry> statsCache = new ConcurrentHashMap<>();

    private final Object summaryLock = new Object();
    private volatile Map<String, JsonObject> summaryByDiscordId = Map.of();
    private volatile List<String> summaryOrder = List.of();
    private volatile Map<String, String> discordIdByUuid = Map.of();
    private volatile String playersSummaryListCache = "[]";

    private final Set<String> onlineUuids = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean statsRefreshRunning = new AtomicBoolean(false);

    private volatile List<IpRule> whitelistRules = List.of();
    private volatile String ticketsJsonCache = "{}";
    private final AtomicBoolean ticketsRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean whitelistRefreshRunning = new AtomicBoolean(false);

    private WrappedTask updateTask;
    private WrappedTask skinsUpdateTask;
    private WrappedTask playersStatsUpdateTask;
    private WrappedTask whitelistUpdateTask;
    private WrappedTask ticketsUpdateTask;

    private final OnlineListener onlineListener = new OnlineListener();

    private volatile boolean dbReady = false;
    private ConnectionPool pool;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void start() {
        loadDataYml();
        buildStatsFolders();

        try {
            Bukkit.getOnlinePlayers().forEach(p -> onlineUuids.add(p.getUniqueId().toString()));
        } catch (Exception ignored) {
        }

        try {
            Bukkit.getPluginManager().registerEvents(onlineListener, plugin);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to register online listener: " + e.getMessage());
        }

        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            initMysql();
            updateExternalData();
            updateSkinsData();
            updateWhitelistData();
            updateTicketsData();
            refreshPlayersData();
        });

        this.updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateExternalData, 3600L, 3600L);
        this.skinsUpdateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateSkinsData, 300L, 300L);
        this.playersStatsUpdateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::refreshPlayersData, 6000L, 6000L);
        this.whitelistUpdateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateWhitelistData, 1200L, 1200L);
        this.ticketsUpdateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateTicketsData, 1200L, 1200L);
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
        if (skinsUpdateTask != null) skinsUpdateTask.cancel();
        if (playersStatsUpdateTask != null) playersStatsUpdateTask.cancel();
        if (whitelistUpdateTask != null) whitelistUpdateTask.cancel();
        if (ticketsUpdateTask != null) ticketsUpdateTask.cancel();

        updateTask = null;
        skinsUpdateTask = null;
        playersStatsUpdateTask = null;
        whitelistUpdateTask = null;
        ticketsUpdateTask = null;

        try {
            HandlerList.unregisterAll(onlineListener);
        } catch (Exception ignored) {
        }

        if (pool != null) pool.close();
        pool = null;
        dbReady = false;
    }

    private void buildStatsFolders() {
        try {
            List<File> folders = new ArrayList<>();
            Bukkit.getWorlds().forEach(w -> {
                File wf = w.getWorldFolder();
                if (wf != null) folders.add(new File(wf, "stats"));
            });
            this.statsFolders = folders;
        } catch (Exception e) {
            this.statsFolders = List.of();
        }
    }

    private void initMysql() {
        boolean enabled = plugin.getConfig().getBoolean("modules.web-server.mysql.enabled", true);
        if (!enabled) {
            dbReady = false;
            return;
        }

        String host = plugin.getConfig().getString("modules.web-server.mysql.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("modules.web-server.mysql.port", 3306);
        String database = plugin.getConfig().getString("modules.web-server.mysql.database", "cookiepl");
        String user = plugin.getConfig().getString("modules.web-server.mysql.user", "root");
        String password = plugin.getConfig().getString("modules.web-server.mysql.password", "");
        int poolSize = Math.max(2, plugin.getConfig().getInt("modules.web-server.mysql.pool-size", 10));

        String baseParams = "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true&rewriteBatchedStatements=true";
        String serverUrl = "jdbc:mysql://" + host + ":" + port + "/?" + baseParams;
        String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + baseParams;

        try {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                Class.forName("org.mariadb.jdbc.Driver");
            }

            try (Connection c = DriverManager.getConnection(serverUrl, user, password); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            } catch (Exception ignored) {
            }

            this.pool = new ConnectionPool(dbUrl, user, password, poolSize);

            Connection c = null;
            try {
                c = pool.borrow();
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS players (" +
                            "discord_id VARCHAR(32) PRIMARY KEY," +
                            "minecraft_uuid CHAR(36) NOT NULL," +
                            "minecraft_name VARCHAR(32) NOT NULL," +
                            "minecraft_name_lc VARCHAR(32) NOT NULL," +
                            "skin_url TEXT," +
                            "head_url TEXT," +
                            "is_online TINYINT(1) NOT NULL DEFAULT 0," +
                            "play_time_hours DOUBLE NOT NULL DEFAULT 0," +
                            "summary_json MEDIUMTEXT," +
                            "full_json MEDIUMTEXT," +
                            "summary_updated_at BIGINT NOT NULL DEFAULT 0," +
                            "full_updated_at BIGINT NOT NULL DEFAULT 0," +
                            "stats_mtime BIGINT NOT NULL DEFAULT 0," +
                            "INDEX idx_uuid (minecraft_uuid)," +
                            "INDEX idx_name_lc (minecraft_name_lc)" +
                            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    st.execute("CREATE TABLE IF NOT EXISTS meta (" +
                            "k VARCHAR(64) PRIMARY KEY," +
                            "v MEDIUMTEXT," +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
            } finally {
                pool.release(c);
            }

            dbReady = true;

            String cachedPlayers = readMeta("players_summary_json");
            if (cachedPlayers != null && !cachedPlayers.isBlank()) playersSummaryListCache = cachedPlayers;

            String cachedTickets = readMeta("tickets_json");
            if (cachedTickets != null && !cachedTickets.isBlank()) ticketsJsonCache = cachedTickets;

        } catch (Exception e) {
            dbReady = false;
            pool = null;
            plugin.getLogManager().warn("MySQL init failed: " + e.getMessage());
        }
    }

    private void loadDataYml() {
        if (!dataFile.exists()) return;
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
            for (String key : config.getKeys(false)) {
                String v = config.getString(key);
                if (v != null) externalCache.put(key, v);
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to load data.yml: " + e.getMessage());
        }
    }

    private void saveDataYml() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, String> entry : externalCache.entrySet()) {
                config.set(entry.getKey(), entry.getValue());
            }
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    private void updateExternalData() {
        String urlStr = plugin.getConfig().getString("modules.web-server.external-name-url", EXTERNAL_NAME_URL_DEFAULT);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == 200) {
                try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    boolean changed = false;
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        if (line == null) continue;
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            String discordId = parts[0];
                            String nick = parts[1];

                            String prev = externalCache.get(discordId);
                            if (!Objects.equals(prev, nick)) {
                                externalCache.put(discordId, nick);
                                changed = true;
                            }
                        }
                    }
                    if (changed) saveDataYml();
                }
            } else {
                plugin.getLogManager().warn("External name db returned: " + connection.getResponseCode());
            }
            connection.disconnect();
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update external name db: " + e.getMessage());
        }
    }

    private void updateWhitelistData() {
        if (!whitelistRefreshRunning.compareAndSet(false, true)) return;
        try {
            String urlPrimary = plugin.getConfig().getString("modules.web-server.tickets.whitelist-url", EXTERNAL_WHITELIST_URL_DEFAULT);
            String urlFallback = plugin.getConfig().getString("modules.web-server.external-name-url", EXTERNAL_NAME_URL_DEFAULT);

            String text = fetchText(urlPrimary, 3000, 3000);
            List<IpRule> rules = parseWhitelistRules(text);

            if (rules == null || rules.isEmpty()) {
                String text2 = fetchText(urlFallback, 3000, 3000);
                List<IpRule> rules2 = parseWhitelistRules(text2);
                if (rules2 != null && !rules2.isEmpty()) rules = rules2;
            }

            if (rules != null && !rules.isEmpty()) {
                whitelistRules = rules;
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update whitelist: " + e.getMessage());
        } finally {
            whitelistRefreshRunning.set(false);
        }
    }

    private void updateTicketsData() {
        if (!ticketsRefreshRunning.compareAndSet(false, true)) return;
        try {
            String urlStr = plugin.getConfig().getString("modules.web-server.tickets.stats-url", EXTERNAL_STATS_URL_DEFAULT);
            String text = fetchText(urlStr, 5000, 5000);
            if (text == null || text.isBlank()) return;

            JsonElement el;
            try {
                el = JsonParser.parseString(text);
            } catch (Exception ignored) {
                return;
            }

            if (!el.isJsonObject()) return;

            String normalized = el.getAsJsonObject().toString();
            if (normalized == null || normalized.isBlank()) return;

            if (!normalized.equals(ticketsJsonCache)) {
                ticketsJsonCache = normalized;
                if (dbReady) writeMeta("tickets_json", normalized, System.currentTimeMillis());
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update tickets stats: " + e.getMessage());
        } finally {
            ticketsRefreshRunning.set(false);
        }
    }

    private String fetchText(String urlStr, int ct, int rt) {
        if (urlStr == null || urlStr.isBlank()) return null;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(ct);
            connection.setReadTimeout(rt);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() != 200) return null;

            try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<IpRule> parseWhitelistRules(String text) {
        if (text == null || text.isBlank()) return null;

        boolean inIps = false;
        List<IpRule> rules = new ArrayList<>();

        String[] lines = text.split("\n");
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.equalsIgnoreCase("ips:") || line.toLowerCase(Locale.ROOT).startsWith("ips:")) {
                inIps = true;
                continue;
            }

            String token = null;

            if (inIps) {
                if (line.startsWith("-")) {
                    token = stripQuotes(line.substring(1).trim());
                } else {
                    token = stripQuotes(line);
                }
            } else {
                if (line.startsWith("-")) {
                    token = stripQuotes(line.substring(1).trim());
                } else if (looksLikeIpToken(line)) {
                    token = stripQuotes(line);
                }
            }

            if (token == null || token.isBlank()) continue;
            IpRule r = IpRule.parse(token);
            if (r != null) rules.add(r);
        }

        return rules.isEmpty() ? null : List.copyOf(rules);
    }

    private boolean looksLikeIpToken(String s) {
        String t = stripQuotes(s.trim());
        if (t.isEmpty()) return false;
        int slash = t.indexOf('/');
        String base = slash >= 0 ? t.substring(0, slash) : t;
        if (base.contains("*")) return true;
        String[] parts = base.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            if (!p.chars().allMatch(Character::isDigit)) return false;
        }
        return true;
    }

    private String stripQuotes(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2) {
            if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    public String getDatabaseWithTicketsJson(String remoteIp) {
        boolean allowed = isIpAllowed(remoteIp);
        String players = getPlayersSummaryJson();
        String tickets = ticketsJsonCache == null || ticketsJsonCache.isBlank() ? "{}" : ticketsJsonCache;

        StringBuilder sb = new StringBuilder(players.length() + tickets.length() + 64);
        sb.append("{\"players\":").append(players).append(",\"tickets\":");
        if (allowed) sb.append(tickets);
        else sb.append("\"not allowed\"");
        sb.append('}');
        return sb.toString();
    }

    private boolean isIpAllowed(String ip) {
        if (ip == null || ip.isBlank()) return false;
        int v = IpRule.ipv4ToInt(ip.trim());
        if (v == -1) return false;
        List<IpRule> rules = whitelistRules;
        if (rules == null || rules.isEmpty()) return false;
        for (IpRule r : rules) {
            if (r.matches(v)) return true;
        }
        return false;
    }

    private void refreshPlayersData() {
        if (!statsRefreshRunning.compareAndSet(false, true)) return;
        try {
            List<AccountEntry> accounts = loadAccountsIfChanged();
            Map<String, String> userCache = loadUserCacheIfChanged();

            if (accounts.isEmpty()) {
                synchronized (summaryLock) {
                    summaryByDiscordId = Map.of();
                    summaryOrder = List.of();
                    discordIdByUuid = Map.of();
                    playersSummaryListCache = "[]";
                }
                if (dbReady) writeMeta("players_summary_json", "[]", System.currentTimeMillis());
                return;
            }

            long now = System.currentTimeMillis();
            List<PlayerUpsertRow> rows = new ArrayList<>(accounts.size());

            Map<String, JsonObject> newSummaryMap = new HashMap<>(Math.max(16, accounts.size() * 2));
            ArrayList<String> newOrder = new ArrayList<>(accounts.size());
            Map<String, String> newDiscordIdByUuid = new HashMap<>(Math.max(16, accounts.size() * 2));
            JsonArray summaryArray = new JsonArray();

            for (AccountEntry entry : accounts) {
                String discordId = entry.discordId();
                String minecraftUuid = entry.minecraftUuid();
                UUID uuid;
                try {
                    uuid = UUID.fromString(minecraftUuid);
                } catch (Exception ignored) {
                    continue;
                }

                String minecraftName = resolveMinecraftName(discordId, minecraftUuid, userCache);
                SkinLinks skinLinks = getSkinLinksForPlayer(minecraftUuid, minecraftName);

                boolean isOnline = onlineUuids.contains(minecraftUuid);

                StatsSnapshot snapshot = getStatsSnapshot(uuid);
                double playTimeHours = round1(ticksToHours(snapshot.playTimeTicks()));
                JsonObject statsObj = buildStatsJson(snapshot, isOnline);

                JsonObject summary = new JsonObject();
                summary.addProperty("id", discordId);
                summary.addProperty("minecraft_name", minecraftName);
                summary.addProperty("minecraft_uuid", minecraftUuid);
                summary.addProperty("skinUrl", skinLinks.skinUrl());
                summary.addProperty("headUrl", skinLinks.headUrl());
                summary.addProperty("is_online", isOnline);
                summary.addProperty("play_time_hours", playTimeHours);
                summaryArray.add(summary);

                JsonObject full = new JsonObject();
                full.addProperty("id", discordId);
                full.addProperty("minecraft_name", minecraftName);
                full.addProperty("minecraft_uuid", minecraftUuid);
                full.addProperty("skinUrl", skinLinks.skinUrl());
                full.addProperty("headUrl", skinLinks.headUrl());
                full.addProperty("is_online", isOnline);
                full.add("stats", statsObj);

                newSummaryMap.put(discordId, summary);
                newOrder.add(discordId);
                newDiscordIdByUuid.put(minecraftUuid, discordId);

                rows.add(new PlayerUpsertRow(
                        discordId,
                        minecraftUuid,
                        minecraftName,
                        minecraftName == null ? "" : minecraftName.toLowerCase(Locale.ROOT),
                        skinLinks.skinUrl(),
                        skinLinks.headUrl(),
                        isOnline,
                        playTimeHours,
                        summary.toString(),
                        full.toString(),
                        now,
                        now,
                        snapshot.mtime()
                ));
            }

            String summaryStr = summaryArray.toString();
            synchronized (summaryLock) {
                summaryByDiscordId = newSummaryMap;
                summaryOrder = List.copyOf(newOrder);
                discordIdByUuid = newDiscordIdByUuid;
                playersSummaryListCache = summaryStr;
            }

            if (dbReady) {
                upsertPlayers(rows);
                writeMeta("players_summary_json", summaryStr, now);
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to refresh players data: " + e.getMessage());
        } finally {
            statsRefreshRunning.set(false);
        }
    }

    public String getPlayersSummaryJson() {
        String cached = playersSummaryListCache;
        if (cached != null && !cached.isBlank()) return cached;
        if (!dbReady) return "[]";
        String v = readMeta("players_summary_json");
        if (v == null || v.isBlank()) return "[]";
        playersSummaryListCache = v;
        return v;
    }

    public String getPlayerJsonById(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            JsonObject errorObj = new JsonObject();
            errorObj.addProperty("error", "Player not found");
            return errorObj.toString();
        }

        if (dbReady) {
            String fromDb = findFullJsonInDb(q);
            if (fromDb != null) {
                try {
                    JsonObject obj = JsonParser.parseString(fromDb).getAsJsonObject();
                    if (obj.has("minecraft_uuid") && !obj.get("minecraft_uuid").isJsonNull()) {
                        String uuid = obj.get("minecraft_uuid").getAsString();
                        obj.addProperty("is_online", onlineUuids.contains(uuid));
                    }
                    if (obj.has("stats") && obj.get("stats").isJsonObject()) {
                        JsonObject st = obj.getAsJsonObject("stats");
                        if (st.has("joins") && !st.get("joins").isJsonNull()) {
                            long leaveGame = st.get("joins").getAsLong();
                            if (obj.has("is_online") && obj.get("is_online").getAsBoolean()) {
                                if (leaveGame > 0) st.addProperty("joins", leaveGame);
                            }
                        }
                    }
                    return obj.toString();
                } catch (Exception ignored) {
                    return fromDb;
                }
            }
        }

        String fallback = findFullJsonFallback(q);
        if (fallback != null) return fallback;

        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("error", "Player not found");
        return errorObj.toString();
    }

    private void handleOnlineChange(String uuid, boolean isOnline) {
        if (uuid == null || uuid.isBlank()) return;

        if (isOnline) onlineUuids.add(uuid);
        else onlineUuids.remove(uuid);

        String discordId = discordIdByUuid.get(uuid);
        if (discordId == null || discordId.isBlank()) return;

        boolean changed = false;
        String newSummary = null;

        synchronized (summaryLock) {
            JsonObject obj = summaryByDiscordId.get(discordId);
            if (obj == null) return;

            boolean wasOnline = obj.has("is_online") && !obj.get("is_online").isJsonNull() && obj.get("is_online").getAsBoolean();
            if (wasOnline != isOnline) {
                obj.addProperty("is_online", isOnline);
                changed = true;
            }

            if (changed) {
                JsonArray arr = new JsonArray();
                for (String id : summaryOrder) {
                    JsonObject o = summaryByDiscordId.get(id);
                    if (o != null) arr.add(o);
                }
                newSummary = arr.toString();
                playersSummaryListCache = newSummary;
            }
        }

        if (changed && dbReady) {
            boolean onlineValue = isOnline;
            String did = discordId;
            plugin.getFoliaLib().getScheduler().runAsync(task -> updateSingleOnlineInDb(did, onlineValue));
        }
    }

    private void updateSingleOnlineInDb(String discordId, boolean online) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET is_online=? WHERE discord_id=?")) {
                ps.setInt(1, online ? 1 : 0);
                ps.setString(2, discordId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update is_online in MySQL: " + e.getMessage());
        } finally {
            pool.release(c);
        }
    }

    private String findFullJsonInDb(String q) {
        String discordId = q;
        String uuid = q;
        String nameLc = q.toLowerCase(Locale.ROOT);

        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement("SELECT full_json FROM players WHERE discord_id=? OR minecraft_uuid=? OR minecraft_name_lc=? LIMIT 1")) {
                ps.setString(1, discordId);
                ps.setString(2, uuid);
                ps.setString(3, nameLc);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        if (json != null && !json.isBlank()) return json;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to lookup player in MySQL: " + e.getMessage());
        } finally {
            pool.release(c);
        }
        return null;
    }

    private String findFullJsonFallback(String q) {
        List<AccountEntry> accounts = loadAccountsIfChanged();
        Map<String, String> userCache = loadUserCacheIfChanged();

        String nq = q.trim().toLowerCase(Locale.ROOT);
        if (nq.isEmpty()) return null;

        for (AccountEntry entry : accounts) {
            String discordId = entry.discordId();
            String minecraftUuid = entry.minecraftUuid();
            String minecraftName = resolveMinecraftName(discordId, minecraftUuid, userCache);
            if (discordId.equalsIgnoreCase(nq) || minecraftUuid.equalsIgnoreCase(nq) || (minecraftName != null && minecraftName.equalsIgnoreCase(nq))) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(minecraftUuid);
                } catch (Exception ignored) {
                    return null;
                }

                SkinLinks skinLinks = getSkinLinksForPlayer(minecraftUuid, minecraftName);
                boolean isOnline = onlineUuids.contains(minecraftUuid);

                StatsSnapshot snapshot = getStatsSnapshot(uuid);
                JsonObject statsObj = buildStatsJson(snapshot, isOnline);

                JsonObject full = new JsonObject();
                full.addProperty("id", discordId);
                full.addProperty("minecraft_name", minecraftName);
                full.addProperty("minecraft_uuid", minecraftUuid);
                full.addProperty("skinUrl", skinLinks.skinUrl());
                full.addProperty("headUrl", skinLinks.headUrl());
                full.addProperty("is_online", isOnline);
                full.add("stats", statsObj);

                return full.toString();
            }
        }

        return null;
    }

    private void upsertPlayers(List<PlayerUpsertRow> rows) {
        if (rows.isEmpty()) return;

        String sql = "INSERT INTO players (discord_id,minecraft_uuid,minecraft_name,minecraft_name_lc,skin_url,head_url,is_online,play_time_hours,summary_json,full_json,summary_updated_at,full_updated_at,stats_mtime) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "minecraft_uuid=VALUES(minecraft_uuid)," +
                "minecraft_name=VALUES(minecraft_name)," +
                "minecraft_name_lc=VALUES(minecraft_name_lc)," +
                "skin_url=VALUES(skin_url)," +
                "head_url=VALUES(head_url)," +
                "is_online=VALUES(is_online)," +
                "play_time_hours=VALUES(play_time_hours)," +
                "summary_json=VALUES(summary_json)," +
                "full_json=VALUES(full_json)," +
                "summary_updated_at=VALUES(summary_updated_at)," +
                "full_updated_at=VALUES(full_updated_at)," +
                "stats_mtime=VALUES(stats_mtime)";

        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int batch = 0;
                for (PlayerUpsertRow r : rows) {
                    ps.setString(1, r.discordId());
                    ps.setString(2, r.minecraftUuid());
                    ps.setString(3, r.minecraftName());
                    ps.setString(4, r.minecraftNameLc());
                    ps.setString(5, r.skinUrl());
                    ps.setString(6, r.headUrl());
                    ps.setInt(7, r.isOnline() ? 1 : 0);
                    ps.setDouble(8, r.playTimeHours());
                    ps.setString(9, r.summaryJson());
                    ps.setString(10, r.fullJson());
                    ps.setLong(11, r.summaryUpdatedAt());
                    ps.setLong(12, r.fullUpdatedAt());
                    ps.setLong(13, r.statsMtime());
                    ps.addBatch();
                    batch++;
                    if (batch >= 500) {
                        ps.executeBatch();
                        batch = 0;
                    }
                }
                if (batch > 0) ps.executeBatch();
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to upsert players to MySQL: " + e.getMessage());
        } finally {
            pool.release(c);
        }
    }

    private void writeMeta(String key, String value, long now) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO meta (k,v,updated_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE v=VALUES(v), updated_at=VALUES(updated_at)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to write meta: " + e.getMessage());
        } finally {
            pool.release(c);
        }
    }

    private String readMeta(String key) {
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k=? LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to read meta: " + e.getMessage());
        } finally {
            pool.release(c);
        }
        return null;
    }

    private List<AccountEntry> loadAccountsIfChanged() {
        File accountsFile = new File(discordSrvFolder, "accounts.aof");
        if (!accountsFile.exists()) {
            accountsList = List.of();
            accountsLastModified = -1L;
            return accountsList;
        }

        long lm = accountsFile.lastModified();
        List<AccountEntry> cached = accountsList;
        if (lm == accountsLastModified && cached != null) return cached;

        List<AccountEntry> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(accountsFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                list.add(new AccountEntry(parts[0], parts[1]));
            }
        } catch (Exception e) {
            plugin.getLogManager().severe("Failed to read DiscordSRV accounts.aof: " + e.getMessage());
        }

        accountsLastModified = lm;
        accountsList = list;
        return list;
    }

    private Map<String, String> loadUserCacheIfChanged() {
        if (!userCacheFile.exists()) {
            userCacheMap = Map.of();
            userCacheLastModified = -1L;
            return userCacheMap;
        }

        long lm = userCacheFile.lastModified();
        Map<String, String> cached = userCacheMap;
        if (lm == userCacheLastModified && cached != null && !cached.isEmpty()) return cached;

        Map<String, String> map = new HashMap<>();
        try (FileReader reader = new FileReader(userCacheFile)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement entry : array) {
                    if (entry.isJsonObject()) {
                        JsonObject obj = entry.getAsJsonObject();
                        if (obj.has("uuid") && obj.has("name")) {
                            String uuid = obj.get("uuid").getAsString();
                            String name = obj.get("name").getAsString();
                            map.put(uuid, name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to read usercache.json: " + e.getMessage());
        }

        userCacheLastModified = lm;
        userCacheMap = map;
        return map;
    }

    private String resolveMinecraftName(String discordId, String minecraftUuid, Map<String, String> userCacheMap) {
        String fromExternal = externalCache.get(discordId);
        if (fromExternal != null && !fromExternal.isBlank()) return fromExternal;
        String fromUsercache = userCacheMap.get(minecraftUuid);
        if (fromUsercache != null && !fromUsercache.isBlank()) return fromUsercache;
        return "Unknown";
    }

    private SkinLinks getSkinLinksForPlayer(String minecraftUuid, String minecraftName) {
        String uuidKey = normalizeUuidKey(minecraftUuid);
        String nameKey = minecraftName == null ? "" : minecraftName.toLowerCase(Locale.ROOT);

        String skinUrl = skinsSkinUrlCache.get(uuidKey);
        String headUrl = skinsHeadUrlCache.get(uuidKey);

        if ((skinUrl == null || skinUrl.isBlank()) && !nameKey.isBlank()) skinUrl = skinsNameSkinUrlCache.get(nameKey);
        if ((headUrl == null || headUrl.isBlank()) && !nameKey.isBlank()) headUrl = skinsNameHeadUrlCache.get(nameKey);

        if (skinUrl == null || skinUrl.isBlank()) skinUrl = buildFallbackSkinUrl(minecraftUuid, minecraftName);
        if (headUrl == null || headUrl.isBlank()) headUrl = buildFallbackHeadUrl(minecraftUuid, minecraftName);

        return new SkinLinks(skinUrl, headUrl);
    }

    private void updateSkinsData() {
        try {
            URL url = new URL(SKINS_DATABASE_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() != 200) {
                plugin.getLogManager().warn("Skins database returned: " + connection.getResponseCode());
                connection.disconnect();
                return;
            }

            Map<String, String> freshHeadCache = new HashMap<>();
            Map<String, String> freshNameHeadCache = new HashMap<>();
            Map<String, String> freshSkinCache = new HashMap<>();
            Map<String, String> freshNameSkinCache = new HashMap<>();

            String response;
            try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                response = scanner.hasNext() ? scanner.next() : "[]";
            }

            JsonElement parsed = JsonParser.parseString(response);
            if (parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) continue;

                    JsonObject entry = element.getAsJsonObject();
                    if (!entry.has("playerUuid")) continue;

                    String playerUuid = entry.get("playerUuid").getAsString();
                    SkinLinks links = resolveSkinLinksFromEntry(entry, playerUuid);
                    String uuidKey = normalizeUuidKey(playerUuid);

                    freshHeadCache.put(uuidKey, links.headUrl());
                    freshSkinCache.put(uuidKey, links.skinUrl());

                    if (entry.has("lastKnownName") && !entry.get("lastKnownName").isJsonNull()) {
                        String name = entry.get("lastKnownName").getAsString();
                        if (name != null && !name.isBlank()) {
                            String nameKey = name.toLowerCase(Locale.ROOT);
                            freshNameHeadCache.put(nameKey, links.headUrl());
                            freshNameSkinCache.put(nameKey, links.skinUrl());
                        }
                    }
                }
            }

            skinsHeadUrlCache.clear();
            skinsNameHeadUrlCache.clear();
            skinsSkinUrlCache.clear();
            skinsNameSkinUrlCache.clear();
            skinsHeadUrlCache.putAll(freshHeadCache);
            skinsNameHeadUrlCache.putAll(freshNameHeadCache);
            skinsSkinUrlCache.putAll(freshSkinCache);
            skinsNameSkinUrlCache.putAll(freshNameSkinCache);

            connection.disconnect();
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update skins database: " + e.getMessage());
        }
    }

    private SkinLinks resolveSkinLinksFromEntry(JsonObject entry, String playerUuid) {
        String skinUrl = getStringOrNull(entry, "skinUrl");
        String headUrl = getStringOrNull(entry, "headUrl");

        if (skinUrl == null || skinUrl.isBlank()) {
            String sourceUrl = getStringOrNull(entry, "sourceUrl");
            if (sourceUrl != null && !sourceUrl.isBlank()) skinUrl = sourceUrl;
        }

        if ((skinUrl == null || skinUrl.isBlank()) || (headUrl == null || headUrl.isBlank())) {
            String textureId = resolveTextureIdFromSkinEntry(entry);
            if (textureId != null && !textureId.isBlank()) {
                if (skinUrl == null || skinUrl.isBlank()) skinUrl = "https://textures.minecraft.net/texture/" + textureId;
                if (headUrl == null || headUrl.isBlank()) headUrl = "https://mc-heads.net/avatar/" + textureId + ".png";
            }
        }

        if (skinUrl == null || skinUrl.isBlank()) skinUrl = buildFallbackSkinUrl(playerUuid, getStringOrNull(entry, "lastKnownName"));
        if (headUrl == null || headUrl.isBlank()) headUrl = buildFallbackHeadUrl(playerUuid, getStringOrNull(entry, "lastKnownName"));

        return new SkinLinks(skinUrl, headUrl);
    }

    private String resolveTextureIdFromSkinEntry(JsonObject entry) {
        if (entry.has("skinHash")) {
            String skinHash = entry.get("skinHash").getAsString();
            if (skinHash != null && !skinHash.isBlank()) return skinHash;
        }

        if (entry.has("decoded")) {
            try {
                JsonElement decodedElement = entry.get("decoded");
                JsonObject decodedObj = decodedElement.isJsonObject()
                        ? decodedElement.getAsJsonObject()
                        : JsonParser.parseString(decodedElement.getAsString()).getAsJsonObject();
                String fromDecoded = extractTextureIdFromDecodedObject(decodedObj);
                if (fromDecoded != null && !fromDecoded.isBlank()) return fromDecoded;
            } catch (Exception ignored) {
            }
        }

        if (entry.has("value")) {
            try {
                String value = entry.get("value").getAsString();
                String decodedValue = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                String fromValue = extractTextureIdFromDecoded(decodedValue);
                if (fromValue != null && !fromValue.isBlank()) return fromValue;
            } catch (Exception ignored) {
            }
        }

        String skinUrlRaw = getStringOrNull(entry, "skinUrlRaw");
        String fromSkinUrlRaw = extractTextureIdFromUrl(skinUrlRaw);
        if (fromSkinUrlRaw != null) return fromSkinUrlRaw;

        String skinUrl = getStringOrNull(entry, "skinUrl");
        return extractTextureIdFromUrl(skinUrl);
    }

    private String getStringOrNull(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return null;
        return json.get(key).getAsString();
    }

    private String buildFallbackSkinUrl(String minecraftUuid, String minecraftName) {
        String identifier = resolveFallbackIdentifier(minecraftUuid, minecraftName);
        return "https://mc-heads.net/skin/" + identifier;
    }

    private String buildFallbackHeadUrl(String minecraftUuid, String minecraftName) {
        String identifier = resolveFallbackIdentifier(minecraftUuid, minecraftName);
        return "https://mc-heads.net/avatar/" + identifier;
    }

    private String resolveFallbackIdentifier(String minecraftUuid, String minecraftName) {
        if (minecraftName != null && !minecraftName.isBlank() && !minecraftName.equalsIgnoreCase("Unknown")) return minecraftName;
        if (minecraftUuid != null && !minecraftUuid.isBlank()) return minecraftUuid;
        return "Steve";
    }

    private String normalizeUuidKey(String uuid) {
        if (uuid == null) return "";
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String extractTextureIdFromDecoded(String decodedJson) {
        try {
            JsonObject decoded = JsonParser.parseString(decodedJson).getAsJsonObject();
            return extractTextureIdFromDecodedObject(decoded);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractTextureIdFromDecodedObject(JsonObject decoded) {
        if (!decoded.has("textures")) return null;

        JsonObject textures = decoded.getAsJsonObject("textures");
        if (!textures.has("SKIN")) return null;

        JsonObject skin = textures.getAsJsonObject("SKIN");
        if (!skin.has("url")) return null;

        String textureUrl = skin.get("url").getAsString();
        return extractTextureIdFromUrl(textureUrl);
    }

    private String extractTextureIdFromUrl(String textureUrl) {
        if (textureUrl == null || textureUrl.isBlank()) return null;
        int index = textureUrl.lastIndexOf('/');
        return index >= 0 ? textureUrl.substring(index + 1) : textureUrl;
    }

    private StatsSnapshot getStatsSnapshot(UUID uuid) {
        String key = uuid.toString();
        StatsCacheEntry cached = statsCache.get(key);

        StatsFileRef ref = findLatestStatsFile(uuid);
        long mtime = ref == null ? 0L : ref.mtime();

        if (cached != null && cached.mtime() == mtime) return cached.snapshot();

        StatsSnapshot snapshot = ref == null ? StatsSnapshot.empty(0L) : parseStatsFile(ref.path(), mtime);
        statsCache.put(key, new StatsCacheEntry(mtime, snapshot));
        return snapshot;
    }

    private StatsFileRef findLatestStatsFile(UUID uuid) {
        String fn = uuid.toString() + ".json";
        long best = 0L;
        Path bestPath = null;

        for (File folder : statsFolders) {
            if (folder == null) continue;
            try {
                File f = new File(folder, fn);
                if (!f.exists() || !f.isFile()) continue;
                long lm = f.lastModified();
                if (lm > best) {
                    best = lm;
                    bestPath = f.toPath();
                }
            } catch (Exception ignored) {
            }
        }

        if (bestPath == null) return null;
        return new StatsFileRef(bestPath, best);
    }

    private StatsSnapshot parseStatsFile(Path path, long mtime) {
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootEl = JsonParser.parseReader(br);
            if (!rootEl.isJsonObject()) return StatsSnapshot.empty(mtime);

            JsonObject root = rootEl.getAsJsonObject();
            JsonObject statsRoot = root.has("stats") && root.get("stats").isJsonObject() ? root.getAsJsonObject("stats") : null;
            if (statsRoot == null) return StatsSnapshot.empty(mtime);

            JsonObject custom = statsRoot.has("minecraft:custom") && statsRoot.get("minecraft:custom").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:custom")
                    : null;

            long playTime = getLong(custom, "minecraft:play_time", "minecraft:play_one_minute", "stat.playOneMinute");
            long leaveGame = getLong(custom, "minecraft:leave_game", "stat.leaveGame");
            long deaths = getLong(custom, "minecraft:deaths", "stat.deaths");
            long playerKills = getLong(custom, "minecraft:player_kills", "stat.playerKills");
            long mobKills = getLong(custom, "minecraft:mob_kills", "stat.mobKills");
            long damageDealt = getLong(custom, "minecraft:damage_dealt", "stat.damageDealt");
            long damageTaken = getLong(custom, "minecraft:damage_taken", "stat.damageTaken");

            long walkCm = getLong(custom, "minecraft:walk_one_cm", "stat.walkOneCm");
            long flyCm = getLong(custom, "minecraft:aviate_one_cm", "stat.aviateOneCm");
            long swimCm = getLong(custom, "minecraft:swim_one_cm", "stat.swimOneCm");
            long sprintCm = getLong(custom, "minecraft:sprint_one_cm", "stat.sprintOneCm");
            long crouchCm = getLong(custom, "minecraft:crouch_one_cm", "stat.crouchOneCm");
            long climbCm = getLong(custom, "minecraft:climb_one_cm", "stat.climbOneCm");
            long fallCm = getLong(custom, "minecraft:fall_one_cm", "stat.fallOneCm");
            long minecartCm = getLong(custom, "minecraft:minecart_one_cm", "stat.minecartOneCm");
            long boatCm = getLong(custom, "minecraft:boat_one_cm", "stat.boatOneCm");
            long pigCm = getLong(custom, "minecraft:pig_one_cm", "stat.pigOneCm");
            long horseCm = getLong(custom, "minecraft:horse_one_cm", "stat.horseOneCm");
            long striderCm = getLong(custom, "minecraft:strider_one_cm", "stat.striderOneCm");

            long jumps = getLong(custom, "minecraft:jump", "stat.jump");
            long animalsBred = getLong(custom, "minecraft:animals_bred", "stat.animalsBred");
            long fishCaught = getLong(custom, "minecraft:fish_caught", "stat.fishCaught");
            long villagerTrades = getLong(custom, "minecraft:traded_with_villager", "stat.tradedWithVillager");
            long enchantments = getLong(custom, "minecraft:item_enchanted", "stat.itemEnchanted");

            JsonObject minedObj = statsRoot.has("minecraft:mined") && statsRoot.get("minecraft:mined").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:mined")
                    : null;

            JsonObject usedObj = statsRoot.has("minecraft:used") && statsRoot.get("minecraft:used").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:used")
                    : null;

            JsonObject craftedObj = statsRoot.has("minecraft:crafted") && statsRoot.get("minecraft:crafted").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:crafted")
                    : null;

            JsonObject pickedObj = statsRoot.has("minecraft:picked_up") && statsRoot.get("minecraft:picked_up").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:picked_up")
                    : null;

            JsonObject droppedObj = statsRoot.has("minecraft:dropped") && statsRoot.get("minecraft:dropped").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:dropped")
                    : null;

            long minedTotal = 0L;
            long usedTotal = 0L;
            long craftedTotal = sumValues(craftedObj);
            long pickedUpTotal = sumValues(pickedObj);
            long droppedTotal = sumValues(droppedObj);

            TopK topMined = new TopK(5);
            TopK topUsed = new TopK(5);

            if (minedObj != null) {
                for (Map.Entry<String, JsonElement> e : minedObj.entrySet()) {
                    long v = asLong(e.getValue());
                    if (v <= 0) continue;
                    minedTotal += v;
                    topMined.add(stripNamespace(e.getKey()).toLowerCase(Locale.ROOT), v);
                }
            }

            if (usedObj != null) {
                for (Map.Entry<String, JsonElement> e : usedObj.entrySet()) {
                    long v = asLong(e.getValue());
                    if (v <= 0) continue;
                    usedTotal += v;
                    topUsed.add(stripNamespace(e.getKey()).toLowerCase(Locale.ROOT), v);
                }
            }

            List<Map.Entry<String, Long>> topMinedList = topMined.toSortedDesc();
            List<Map.Entry<String, Long>> topUsedList = topUsed.toSortedDesc();

            return new StatsSnapshot(
                    mtime,
                    playTime,
                    leaveGame,
                    deaths,
                    playerKills,
                    mobKills,
                    damageDealt,
                    damageTaken,
                    walkCm,
                    flyCm,
                    swimCm,
                    sprintCm,
                    crouchCm,
                    climbCm,
                    fallCm,
                    minecartCm,
                    boatCm,
                    pigCm,
                    horseCm,
                    striderCm,
                    jumps,
                    animalsBred,
                    fishCaught,
                    villagerTrades,
                    enchantments,
                    minedTotal,
                    usedTotal,
                    craftedTotal,
                    pickedUpTotal,
                    droppedTotal,
                    topMinedList,
                    topUsedList
            );
        } catch (Exception ignored) {
            return StatsSnapshot.empty(mtime);
        }
    }

    private JsonObject buildStatsJson(StatsSnapshot s, boolean isOnline) {
        JsonObject stats = new JsonObject();

        double playTimeHours = round1(ticksToHours(s.playTimeTicks()));
        stats.addProperty("play_time_hours", playTimeHours);

        long joins = isOnline ? s.leaveGame() + 1L : s.leaveGame();
        stats.addProperty("joins", joins);
        stats.addProperty("deaths", s.deaths());

        JsonObject kills = new JsonObject();
        kills.addProperty("players", s.playerKills());
        kills.addProperty("mobs", s.mobKills());
        stats.add("kills", kills);

        JsonObject damage = new JsonObject();
        damage.addProperty("dealt", s.damageDealt());
        damage.addProperty("taken", s.damageTaken());
        stats.add("damage", damage);

        long totalCm = s.walkCm() + s.flyCm() + s.swimCm() + s.sprintCm() + s.crouchCm() + s.climbCm() + s.fallCm()
                + s.minecartCm() + s.boatCm() + s.pigCm() + s.horseCm() + s.striderCm();

        JsonObject distance = new JsonObject();
        distance.addProperty("total_km", toKilometers(totalCm));
        distance.addProperty("walk_km", toKilometers(s.walkCm()));
        distance.addProperty("fly_km", toKilometers(s.flyCm()));
        distance.addProperty("swim_km", toKilometers(s.swimCm()));
        stats.add("distance", distance);

        JsonObject blocks = new JsonObject();
        blocks.addProperty("mined_total", s.minedTotal());
        stats.add("blocks", blocks);

        JsonObject items = new JsonObject();
        items.addProperty("used_total", s.usedTotal());
        items.addProperty("crafted_total", s.craftedTotal());
        items.addProperty("picked_up_total", s.pickedUpTotal());
        items.addProperty("dropped_total", s.droppedTotal());
        stats.add("items", items);

        JsonObject fun = new JsonObject();
        fun.addProperty("jumps", s.jumps());
        fun.addProperty("animals_bred", s.animalsBred());
        fun.addProperty("fish_caught", s.fishCaught());
        fun.addProperty("villager_trades", s.villagerTrades());
        fun.addProperty("enchantments", s.enchantments());
        stats.add("fun", fun);

        JsonObject top = new JsonObject();
        top.add("mined", toTopArray(s.topMined()));
        top.add("used", toTopArray(s.topUsed()));
        stats.add("top", top);

        stats.addProperty("favorite_mined", s.topMined().isEmpty() ? "" : s.topMined().get(0).getKey());
        stats.addProperty("favorite_used", s.topUsed().isEmpty() ? "" : s.topUsed().get(0).getKey());

        return stats;
    }

    private JsonArray toTopArray(List<Map.Entry<String, Long>> entries) {
        JsonArray array = new JsonArray();
        int limit = Math.min(entries.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("material", entry.getKey());
            obj.addProperty("count", entry.getValue());
            array.add(obj);
        }
        return array;
    }

    private long getLong(JsonObject obj, String... keys) {
        if (obj == null) return 0L;
        for (String k : keys) {
            if (k == null) continue;
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                try {
                    return obj.get(k).getAsLong();
                } catch (Exception ignored) {
                }
            }
        }
        return 0L;
    }

    private long asLong(JsonElement el) {
        try {
            return el == null || el.isJsonNull() ? 0L : el.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long sumValues(JsonObject obj) {
        if (obj == null) return 0L;
        long sum = 0L;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            long v = asLong(e.getValue());
            if (v > 0) sum += v;
        }
        return sum;
    }

    private String stripNamespace(String key) {
        if (key == null) return "";
        int idx = key.indexOf(':');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private double ticksToHours(long ticks) {
        return ticks / 20.0 / 3600.0;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double toKilometers(long centimeters) {
        double kilometers = centimeters / 100000.0;
        return Math.round(kilometers * 100.0) / 100.0;
    }

    private static final class TopK {
        private final int k;
        private final PriorityQueue<Map.Entry<String, Long>> pq;

        private TopK(int k) {
            this.k = k;
            this.pq = new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
        }

        private void add(String key, long value) {
            if (value <= 0) return;
            Map.Entry<String, Long> e = Map.entry(key, value);
            if (pq.size() < k) {
                pq.offer(e);
                return;
            }
            Map.Entry<String, Long> min = pq.peek();
            if (min != null && value > min.getValue()) {
                pq.poll();
                pq.offer(e);
            }
        }

        private List<Map.Entry<String, Long>> toSortedDesc() {
            ArrayList<Map.Entry<String, Long>> list = new ArrayList<>(pq);
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return list;
        }
    }

    private record AccountEntry(String discordId, String minecraftUuid) {
    }

    private record SkinLinks(String skinUrl, String headUrl) {
    }

    private record PlayerUpsertRow(
            String discordId,
            String minecraftUuid,
            String minecraftName,
            String minecraftNameLc,
            String skinUrl,
            String headUrl,
            boolean isOnline,
            double playTimeHours,
            String summaryJson,
            String fullJson,
            long summaryUpdatedAt,
            long fullUpdatedAt,
            long statsMtime
    ) {
    }

    private record StatsFileRef(Path path, long mtime) {
    }

    private record StatsCacheEntry(long mtime, StatsSnapshot snapshot) {
    }

    private record StatsSnapshot(
            long mtime,
            long playTimeTicks,
            long leaveGame,
            long deaths,
            long playerKills,
            long mobKills,
            long damageDealt,
            long damageTaken,
            long walkCm,
            long flyCm,
            long swimCm,
            long sprintCm,
            long crouchCm,
            long climbCm,
            long fallCm,
            long minecartCm,
            long boatCm,
            long pigCm,
            long horseCm,
            long striderCm,
            long jumps,
            long animalsBred,
            long fishCaught,
            long villagerTrades,
            long enchantments,
            long minedTotal,
            long usedTotal,
            long craftedTotal,
            long pickedUpTotal,
            long droppedTotal,
            List<Map.Entry<String, Long>> topMined,
            List<Map.Entry<String, Long>> topUsed
    ) {
        static StatsSnapshot empty(long mtime) {
            return new StatsSnapshot(
                    mtime,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L,
                    List.of(), List.of()
            );
        }
    }

    private final class OnlineListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            handleOnlineChange(e.getPlayer().getUniqueId().toString(), true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            handleOnlineChange(e.getPlayer().getUniqueId().toString(), false);
        }
    }

    private record IpRule(int network, int maskBits, int mask) {

        static IpRule parse(String s) {
            if (s == null) return null;
            String t = s.trim();
            if (t.isEmpty()) return null;

            if (t.contains("/")) {
                String[] parts = t.split("/", 2);
                if (parts.length != 2) return null;
                int base = ipv4ToInt(parts[0].trim());
                if (base == -1) return null;
                int bits;
                try {
                    bits = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {
                    return null;
                }
                if (bits < 0) bits = 0;
                if (bits > 32) bits = 32;
                int m = mask(bits);
                return new IpRule(base, bits, m);
            }

            if (t.contains("*")) {
                String[] parts = t.split("\\.");
                if (parts.length != 4) return null;
                int bits = 0;
                int[] oct = new int[4];
                for (int i = 0; i < 4; i++) {
                    String p = parts[i].trim();
                    if (p.equals("*")) {
                        oct[i] = 0;
                    } else {
                        if (!p.chars().allMatch(Character::isDigit)) return null;
                        int v = Integer.parseInt(p);
                        if (v < 0 || v > 255) return null;
                        oct[i] = v;
                        bits += 8;
                    }
                }
                int base = (oct[0] << 24) | (oct[1] << 16) | (oct[2] << 8) | oct[3];
                int m = mask(bits);
                return new IpRule(base, bits, m);
            }

            int base = ipv4ToInt(t);
            if (base == -1) return null;

            int bits = 32;
            if (t.endsWith(".0.0.0")) bits = 8;
            else if (t.endsWith(".0.0")) bits = 16;
            else if (t.endsWith(".0")) bits = 24;

            int m = mask(bits);
            return new IpRule(base, bits, m);
        }

        boolean matches(int ip) {
            return (ip & mask) == (network & mask);
        }

        static int mask(int bits) {
            if (bits <= 0) return 0;
            if (bits >= 32) return -1;
            long m = 0xFFFFFFFFL << (32 - bits);
            return (int) m;
        }

        static int ipv4ToInt(String ip) {
            if (ip == null) return -1;
            String t = ip.trim();
            String[] parts = t.split("\\.");
            if (parts.length != 4) return -1;
            int[] oct = new int[4];
            for (int i = 0; i < 4; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) return -1;
                if (!p.chars().allMatch(Character::isDigit)) return -1;
                int v;
                try {
                    v = Integer.parseInt(p);
                } catch (Exception ignored) {
                    return -1;
                }
                if (v < 0 || v > 255) return -1;
                oct[i] = v;
            }
            return (oct[0] << 24) | (oct[1] << 16) | (oct[2] << 8) | oct[3];
        }
    }

    private static final class ConnectionPool {
        private final String url;
        private final String user;
        private final String pass;
        private final ArrayBlockingQueue<Connection> queue;
        private final int max;
        private final AtomicInteger created = new AtomicInteger(0);

        private ConnectionPool(String url, String user, String pass, int max) {
            this.url = url;
            this.user = user;
            this.pass = pass;
            this.max = max;
            this.queue = new ArrayBlockingQueue<>(max);
        }

        private Connection borrow() throws Exception {
            Connection c = queue.poll();
            if (c != null) {
                if (isOk(c)) return c;
                closeQuietly(c);
                created.decrementAndGet();
            }

            if (created.get() < max) {
                int n = created.incrementAndGet();
                if (n <= max) {
                    Connection nc = DriverManager.getConnection(url, user, pass);
                    nc.setAutoCommit(true);
                    return nc;
                } else {
                    created.decrementAndGet();
                }
            }

            c = queue.poll(10, TimeUnit.SECONDS);
            if (c != null) {
                if (isOk(c)) return c;
                closeQuietly(c);
                created.decrementAndGet();
            }

            throw new IllegalStateException("No DB connections available");
        }

        private void release(Connection c) {
            if (c == null) return;
            try {
                if (!isOk(c)) {
                    closeQuietly(c);
                    created.decrementAndGet();
                    return;
                }
                if (!queue.offer(c)) {
                    closeQuietly(c);
                    created.decrementAndGet();
                }
            } catch (Exception e) {
                closeQuietly(c);
                created.decrementAndGet();
            }
        }

        private boolean isOk(Connection c) {
            try {
                return !c.isClosed() && c.isValid(2);
            } catch (Exception e) {
                return false;
            }
        }

        private void close() {
            Connection c;
            while ((c = queue.poll()) != null) closeQuietly(c);
        }

        private void closeQuietly(Connection c) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}