package com.leir4iks.cookiepl.modules.web;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.afk.AFKManager;
import com.leir4iks.cookiepl.modules.tags.TagsManager;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseManager {
    private static final String SKINS_DATABASE_URL = "http://212.80.7.215:20200/skins";
    private static final String EXTERNAL_NAME_URL_DEFAULT = "http://212.80.7.211:20081/name";
    private static final String EXTERNAL_STATS_URL_DEFAULT = "http://212.80.7.211:20081/stats";
    private static final String EXTERNAL_WHITELIST_URL_DEFAULT = "http://212.80.7.211:20081/whitelist";
    private static final String EXTERNAL_DISCORD_URL_DEFAULT = "http://212.80.7.211:20081/discord";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Pattern AUTH_INSERT_PATTERN = Pattern.compile(
            "INSERT\\s+INTO\\s+AUTH\\s*\\((.*?)\\)\\s*VALUES\\s*\\((.*?)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final CookiePl plugin;
    private final File discordSrvFolder, userCacheFile, dataFile;
    private final RegionMsptBridge regionMsptBridge = new RegionMsptBridge();
    private final RegionTpsBridge regionTpsBridge = new RegionTpsBridge();
    private final LiteBansBridge liteBansBridge;

    private final Map<String, String> externalCache = new ConcurrentHashMap<>();
    private final Map<String, String> ticketNameCache = new ConcurrentHashMap<>();

    private final Map<String, String> skinsHeadUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsNameHeadUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsSkinUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> skinsNameSkinUrlCache = new ConcurrentHashMap<>();

    private volatile long userCacheLastModified = -1L;
    private volatile Map<String, String> userCacheMap = Map.of();

    private volatile long accountsLastModified = -1L, accountsOldLastModified = -1L;
    private volatile List<AccountEntry> accountsList = List.of();

    private volatile List<File> statsFolders = List.of();
    private volatile List<File> advancementsFolders = List.of();

    private final Map<String, StatsCacheEntry> statsCache = new ConcurrentHashMap<>();
    private final Map<String, AdvCacheEntry> advCache = new ConcurrentHashMap<>();

    private final Object summaryLock = new Object();
    private volatile Map<String, JsonObject> summaryByDiscordId = Map.of();
    private volatile List<String> summaryOrder = List.of();
    private volatile Map<String, String> discordIdByUuid = Map.of();
    private volatile Map<String, Boolean> activeStateByDiscordId = Map.of();
    private volatile String playersSummaryListCache = "[]";

    private final Set<String> onlineUuids = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean playersRefreshRunning = new AtomicBoolean(false);

    private volatile List<IpRule> whitelistRules = List.of();
    private final AtomicBoolean whitelistRefreshRunning = new AtomicBoolean(false);

    private final AtomicBoolean statsRefreshRunning = new AtomicBoolean(false);
    private volatile Map<String, List<TicketInfo>> ticketsByUserId = Map.of();
    private volatile int lastStatsHash;
    private volatile int lastDiscordStatsHash;

    private WrappedTask skinsUpdateTask, playersStatsUpdateTask, whitelistUpdateTask, statsUpdateTask, nameUpdateTask;
    private WrappedTask timeTrackTask, timeFlushTask, extraFlushTask;
    private WrappedTask discordUpdateTask, worldsUpdateTask, foliaRegionsUpdateTask;
    private final OnlineListener onlineListener = new OnlineListener();

    private volatile boolean dbReady;
    private ConnectionPool pool;
    private volatile LuckPerms luckPerms;

    private volatile String seasonId = "default";
    private volatile int seasonPeakOnline;

    private final Set<String> pendingSeasonJoins = ConcurrentHashMap.newKeySet();

    private final Map<String, TimeState> timeByUuid = new ConcurrentHashMap<>();
    private final Map<String, Long> onlineTickMs = new ConcurrentHashMap<>();
    private final Set<String> dirtyTimes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean timeFlushRunning = new AtomicBoolean(false);

    private final Map<String, ExtraState> extraByUuid = new ConcurrentHashMap<>();
    private final Set<String> dirtyExtra = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean extraFlushRunning = new AtomicBoolean(false);

    private final Map<String, Set<Long>> loadedChunksByWorld = new ConcurrentHashMap<>();

    private volatile String serverStatsJsonCache = "{}";
    private volatile String discordStatsJsonCache = "{}";
    private volatile String worldsJsonCache = "[]";
    private volatile String foliaRegionsJsonCache = "[]";

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        File parent = plugin.getDataFolder().getParentFile();
        this.discordSrvFolder = new File(parent, "DiscordSRV");
        this.userCacheFile = new File(parent.getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.liteBansBridge = new LiteBansBridge(plugin);
    }

    public void start() {
        loadDataYml();
        buildStatsFolders();
        buildAdvancementsFolders();
        indexLoadedChunksFromWorlds();
        updateWorldsSnapshot();

        try { Bukkit.getOnlinePlayers().forEach(p -> onlineUuids.add(p.getUniqueId().toString())); } catch (Exception ignored) {}
        try { Bukkit.getPluginManager().registerEvents(onlineListener, plugin); }
        catch (Exception e) { plugin.getLogManager().warn("Failed to register online listener: " + e.getMessage()); }

        try {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                String u = p.getUniqueId().toString();
                onlineTickMs.put(u, now);
                ensureTimeStateLoaded(u, now);
                ensureExtraStateLoaded(u);
            }
        } catch (Exception ignored) {}

        plugin.getFoliaLib().getScheduler().runAsync(t -> {
            initH2();
            liteBansBridge.warmup();
            importLegacyIpsFromDumpIfNeeded();
            updateExternalNameData();
            updateWhitelistData();
            updateExternalStatsData();
            updateDiscordStatsData();
            updateSkinsData();
            refreshPlayersData();
            flushPendingSeasonJoins();
            updateFoliaRegionsSnapshot();
        });

        timeTrackTask = plugin.getFoliaLib().getScheduler().runTimer(this::trackOnlineTimeTick, 20L, 20L);
        timeFlushTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::flushDirtyTimeRows, 200L, 200L);
        extraFlushTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::flushDirtyExtraRows, 200L, 200L);

        nameUpdateTask = timer(this::updateExternalNameData, 3600L, 3600L);
        skinsUpdateTask = timer(this::updateSkinsData, 300L, 300L);
        playersStatsUpdateTask = timer(this::refreshPlayersData, 6000L, 6000L);
        whitelistUpdateTask = timer(this::updateWhitelistData, 1200L, 1200L);
        statsUpdateTask = timer(this::updateExternalStatsData, 300L, 300L);
        discordUpdateTask = timer(this::updateDiscordStatsData, 300L, 300L);
        worldsUpdateTask = plugin.getFoliaLib().getScheduler().runTimer(this::updateWorldsSnapshot, 200L, 200L);
        foliaRegionsUpdateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateFoliaRegionsSnapshot, 200L, 200L);
    }

    public void stop() {
        cancel(timeTrackTask);
        cancel(timeFlushTask);
        cancel(extraFlushTask);
        timeTrackTask = timeFlushTask = extraFlushTask = null;

        cancel(nameUpdateTask);
        cancel(skinsUpdateTask);
        cancel(playersStatsUpdateTask);
        cancel(whitelistUpdateTask);
        cancel(statsUpdateTask);
        cancel(discordUpdateTask);
        cancel(worldsUpdateTask);
        cancel(foliaRegionsUpdateTask);
        nameUpdateTask = skinsUpdateTask = playersStatsUpdateTask = whitelistUpdateTask = statsUpdateTask = null;
        discordUpdateTask = worldsUpdateTask = foliaRegionsUpdateTask = null;

        try { flushDirtyTimeRows(); } catch (Exception ignored) {}
        try { flushDirtyExtraRows(); } catch (Exception ignored) {}

        try { HandlerList.unregisterAll(onlineListener); } catch (Exception ignored) {}

        if (pool != null) pool.close();
        pool = null;
        dbReady = false;

        liteBansBridge.close();
        onlineUuids.clear();
        onlineTickMs.clear();
        dirtyTimes.clear();
        timeByUuid.clear();
        dirtyExtra.clear();
        extraByUuid.clear();
        pendingSeasonJoins.clear();
        loadedChunksByWorld.clear();
        serverStatsJsonCache = "{}";
        discordStatsJsonCache = "{}";
        worldsJsonCache = "[]";
        foliaRegionsJsonCache = "[]";
    }

    public JsonObject newJsonObject() { return new JsonObject(); }
    public String toJsonString(JsonElement el) { return GSON.toJson(el); }

    public long getActivePlaytimeMillis(UUID minecraftUuid) {
        if (minecraftUuid == null) return 0L;
        TimeState st = ensureTimeStateLoaded(minecraftUuid.toString(), System.currentTimeMillis());
        if (st == null) return 0L;
        synchronized (st) {
            return Math.max(0L, st.activeTotalMs);
        }
    }

    public long getActivePlaytimeTodayMillis(UUID minecraftUuid) {
        if (minecraftUuid == null) return 0L;
        long now = System.currentTimeMillis();
        TimeState st = ensureTimeStateLoaded(minecraftUuid.toString(), now);
        if (st == null) return 0L;
        int currentDayKey = dayKey(now);
        synchronized (st) {
            if (st.dayKey != currentDayKey) return 0L;
            return Math.max(0L, st.activeTodayMs);
        }
    }

    public String getDiscordIdByMinecraftUuid(UUID minecraftUuid) {
        if (minecraftUuid == null) return null;
        String uuid = minecraftUuid.toString();
        String cached = discordIdByUuid.get(uuid);
        if (!isBlank(cached)) return cached;
        if (!hasDb()) return null;
        return withConn("Failed to resolve discord id by uuid: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT discord_id FROM players WHERE minecraft_uuid=? LIMIT 1")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String discordId = rs.getString(1);
                        return isBlank(discordId) ? null : discordId;
                    }
                }
            }
            return null;
        });
    }

    public boolean isPlayerActive(String discordId) {
        if (isBlank(discordId)) return false;
        Boolean cached = activeStateByDiscordId.get(discordId);
        if (cached != null) return cached;
        if (!hasDb()) return false;
        Boolean fromDb = withConn("Failed to read active state from H2: ", Boolean.FALSE, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT active FROM players WHERE discord_id=? LIMIT 1")) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getBoolean(1);
                }
            }
            return false;
        });
        return fromDb != null && fromDb;
    }

    public void setPlayerActive(UUID minecraftUuid, boolean active) {
        String discordId = getDiscordIdByMinecraftUuid(minecraftUuid);
        if (isBlank(discordId)) return;
        setPlayerActive(discordId, active);
    }

    public void setPlayerActive(String discordId, boolean active) {
        if (isBlank(discordId)) return;

        HashMap<String, Boolean> updated = new HashMap<>(activeStateByDiscordId);
        updated.put(discordId, active);
        activeStateByDiscordId = Map.copyOf(updated);

        updateCachedPlayerActive(discordId, active);
        updatePlayerActiveInDb(discordId, active);
    }

    private WrappedTask timer(Runnable r, long delay, long period) {
        return plugin.getFoliaLib().getScheduler().runTimerAsync(r, delay, period);
    }

    private void cancel(WrappedTask t) { try { if (t != null) t.cancel(); } catch (Exception ignored) {} }

    private boolean hasDb() { return dbReady && pool != null; }

    @FunctionalInterface
    private interface ConnFn<T> { T run(Connection c) throws Exception; }

    private <T> T withConn(String errPrefix, T def, ConnFn<T> fn) {
        if (!hasDb()) return def;
        Connection c = null;
        try { c = pool.borrow(); return fn.run(c); }
        catch (Exception e) { if (errPrefix != null) plugin.getLogManager().warn(errPrefix + e.getMessage()); return def; }
        finally { pool.release(c); }
    }

    private void initH2() {
        if (!plugin.getConfig().getBoolean("modules.web-server.enabled", true)) { dbReady = false; return; }

        String fileName = nz(plugin.getConfig().getString("modules.web-server.h2.file", "webdb"));
        File dbFile = new File(plugin.getDataFolder(), fileName.isBlank() ? "webdb" : fileName);
        try { File p = dbFile.getParentFile(); if (p != null) p.mkdirs(); } catch (Exception ignored) {}

        String url = "jdbc:h2:file:" + dbFile.getAbsolutePath() + ";MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        try {
            Class.forName("org.h2.Driver");
            pool = new ConnectionPool(url, "sa", "", Math.max(2, plugin.getConfig().getInt("modules.web-server.h2.pool-size", 10)));

            Connection c = null;
            try {
                c = pool.borrow();
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS players (" +
                            "discord_id VARCHAR(32) PRIMARY KEY," +
                            "minecraft_uuid CHAR(36) NOT NULL," +
                            "minecraft_name VARCHAR(32) NOT NULL," +
                            "minecraft_name_lc VARCHAR(32) NOT NULL," +
                            "skin_url CLOB," +
                            "head_url CLOB," +
                            "is_online BOOLEAN NOT NULL DEFAULT FALSE," +
                            "active BOOLEAN NOT NULL DEFAULT FALSE," +
                            "play_time_hours DOUBLE NOT NULL DEFAULT 0," +
                            "summary_json CLOB," +
                            "full_json CLOB," +
                            "summary_updated_at BIGINT NOT NULL DEFAULT 0," +
                            "full_updated_at BIGINT NOT NULL DEFAULT 0," +
                            "stats_mtime BIGINT NOT NULL DEFAULT 0" +
                            ")");

                    try { st.execute("ALTER TABLE players ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE"); } catch (Exception ignored) {}
                    try { st.execute("CREATE INDEX idx_uuid ON players (minecraft_uuid)"); } catch (Exception ignored) {}
                    try { st.execute("CREATE INDEX idx_name_lc ON players (minecraft_name_lc)"); } catch (Exception ignored) {}

                    st.execute("CREATE TABLE IF NOT EXISTS meta (" +
                            "k VARCHAR(64) PRIMARY KEY," +
                            "v CLOB," +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ")");

                    st.execute("CREATE TABLE IF NOT EXISTS flex (" +
                            "discord_id VARCHAR(32) PRIMARY KEY," +
                            "bank BIGINT NOT NULL DEFAULT 0," +
                            "color VARCHAR(256) NOT NULL DEFAULT ''," +
                            "subscription CLOB," +
                            "command VARCHAR(256) NOT NULL DEFAULT ''" +
                            ")");

                    st.execute("CREATE TABLE IF NOT EXISTS player_time (" +
                            "minecraft_uuid CHAR(36) PRIMARY KEY," +
                            "last_join_ms BIGINT NOT NULL DEFAULT 0," +
                            "last_seen_ms BIGINT NOT NULL DEFAULT 0," +
                            "active_ms_total BIGINT NOT NULL DEFAULT 0," +
                            "afk_ms_total BIGINT NOT NULL DEFAULT 0," +
                            "active_ms_today BIGINT NOT NULL DEFAULT 0," +
                            "active_ms_week BIGINT NOT NULL DEFAULT 0," +
                            "active_ms_month BIGINT NOT NULL DEFAULT 0," +
                            "day_key INT NOT NULL DEFAULT 0," +
                            "week_key INT NOT NULL DEFAULT 0," +
                            "month_key INT NOT NULL DEFAULT 0," +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ")");

                    st.execute("CREATE TABLE IF NOT EXISTS player_extra (" +
                            "minecraft_uuid CHAR(36) PRIMARY KEY," +
                            "slaps_sent BIGINT NOT NULL DEFAULT 0," +
                            "slaps_received BIGINT NOT NULL DEFAULT 0," +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ")");

                    st.execute("CREATE TABLE IF NOT EXISTS player_ips (" +
                            "minecraft_uuid CHAR(36) NOT NULL," +
                            "ip VARCHAR(64) NOT NULL," +
                            "first_seen_ms BIGINT NOT NULL DEFAULT 0," +
                            "last_seen_ms BIGINT NOT NULL DEFAULT 0," +
                            "cnt BIGINT NOT NULL DEFAULT 0," +
                            "PRIMARY KEY(minecraft_uuid, ip)" +
                            ")");
                    try { st.execute("CREATE INDEX idx_player_ips_uuid ON player_ips (minecraft_uuid)"); } catch (Exception ignored) {}
                    try { st.execute("CREATE INDEX idx_player_ips_ip ON player_ips (ip)"); } catch (Exception ignored) {}

                    st.execute("CREATE TABLE IF NOT EXISTS season_players (" +
                            "season_id VARCHAR(64) NOT NULL," +
                            "minecraft_uuid CHAR(36) NOT NULL," +
                            "joined_at BIGINT NOT NULL DEFAULT 0," +
                            "PRIMARY KEY(season_id, minecraft_uuid)" +
                            ")");

                    st.execute("CREATE TABLE IF NOT EXISTS season_meta (" +
                            "season_id VARCHAR(64) PRIMARY KEY," +
                            "peak_online INT NOT NULL DEFAULT 0," +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ")");
                }
            } finally {
                pool.release(c);
            }

            dbReady = true;

            long now = System.currentTimeMillis();
            String cfgSeason = nz(plugin.getConfig().getString("modules.web-server.season-id")).trim();
            String metaSeason = nz(readMeta("season_id")).trim();
            seasonId = !cfgSeason.isEmpty() ? cfgSeason : (!metaSeason.isEmpty() ? metaSeason : "default");
            writeMeta("season_id", seasonId, now);
            activeStateByDiscordId = readAllActiveStates();

            seasonPeakOnline = readSeasonPeakOnline(seasonId);
            upsertSeasonPeakOnline(seasonId, Math.max(seasonPeakOnline, safeOnlineCount()), now);

            String cached = readMeta("players_summary_json");
            if (!isBlank(cached)) playersSummaryListCache = cached;

            String sstats = readMeta("server_stats_json");
            if (!isBlank(sstats)) serverStatsJsonCache = sstats;

            String discordStats = readMeta("discord_stats_json");
            if (!isBlank(discordStats)) discordStatsJsonCache = discordStats;

        } catch (Exception e) {
            dbReady = false;
            pool = null;
            plugin.getLogManager().warn("H2 init failed: " + e.getMessage());
        }
    }

    private int safeOnlineCount() {
        try { return Bukkit.getOnlinePlayers().size(); } catch (Exception ignored) { return 0; }
    }

    private int readSeasonPeakOnline(String sid) {
        if (!hasDb() || isBlank(sid)) return 0;
        return withConn(null, 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT peak_online FROM season_meta WHERE season_id=? LIMIT 1")) {
                ps.setString(1, sid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return 0;
                    return Math.max(0, rs.getInt(1));
                }
            }
        });
    }

    private void upsertSeasonPeakOnline(String sid, int peak, long now) {
        if (!hasDb() || isBlank(sid)) return;
        withConn(null, null, c -> {
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO season_meta (season_id,peak_online,updated_at) KEY(season_id) VALUES (?,?,?)")) {
                ps.setString(1, sid);
                ps.setInt(2, Math.max(0, peak));
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void flushPendingSeasonJoins() {
        if (!hasDb()) return;
        if (pendingSeasonJoins.isEmpty()) return;
        ArrayList<String> list = new ArrayList<>(pendingSeasonJoins);
        pendingSeasonJoins.clear();
        for (String u : list) markSeasonJoin(u, System.currentTimeMillis());
    }

    private void markSeasonJoin(String minecraftUuid, long now) {
        if (!hasDb() || isBlank(seasonId) || isBlank(minecraftUuid)) return;
        withConn(null, null, c -> {
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO season_players (season_id,minecraft_uuid,joined_at) KEY(season_id,minecraft_uuid) VALUES (?,?,?)")) {
                ps.setString(1, seasonId);
                ps.setString(2, minecraftUuid);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int readSeasonPlayersCount() {
        if (!hasDb() || isBlank(seasonId)) return 0;
        return withConn(null, 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM season_players WHERE season_id=?")) {
                ps.setString(1, seasonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return 0;
                    long v = rs.getLong(1);
                    if (v < 0) v = 0;
                    if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
                    return (int) v;
                }
            }
        });
    }

    private void updateSeasonPeakOnlineIfNeeded(int currentOnline) {
        int cur = Math.max(0, currentOnline);
        if (cur <= seasonPeakOnline) return;
        seasonPeakOnline = cur;
        long now = System.currentTimeMillis();
        if (hasDb()) upsertSeasonPeakOnline(seasonId, seasonPeakOnline, now);
    }

    private LuckPerms getLuckPerms() {
        LuckPerms lp = luckPerms;
        if (lp != null) return lp;
        try { luckPerms = LuckPermsProvider.get(); return luckPerms; }
        catch (Exception ignored) { return null; }
    }

    private JsonObject getLuckPermsGroups(UUID uuid) {
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        out.add("groups", arr);
        if (uuid == null) return out;

        LuckPerms lp = getLuckPerms();
        if (lp == null) return out;

        User user = null;
        boolean loadedTemp = false;
        try {
            user = lp.getUserManager().getUser(uuid);
            if (user == null) { user = lp.getUserManager().loadUser(uuid).get(2, TimeUnit.SECONDS); loadedTemp = true; }
            if (user == null) return out;

            QueryOptions qo = lp.getContextManager().getQueryOptions(user).orElseGet(() -> lp.getContextManager().getStaticQueryOptions());
            HashSet<String> names = new HashSet<>();

            Collection<Group> groups = user.getInheritedGroups(qo);
            if (groups != null) for (Group g : groups) if (g != null && !isBlank(g.getName())) names.add(g.getName());

            String primary = user.getPrimaryGroup();
            if (!isBlank(primary)) names.add(primary);

            ArrayList<String> sorted = new ArrayList<>(names);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            for (String n : sorted) arr.add(n);
        } catch (Exception ignored) {
        } finally {
            if (loadedTemp && user != null) try { lp.getUserManager().cleanupUser(user); } catch (Exception ignored) {}
        }
        return out;
    }

    private void buildStatsFolders() {
        try {
            ArrayList<File> folders = new ArrayList<>();
            Bukkit.getWorlds().forEach(w -> { File wf = w.getWorldFolder(); if (wf != null) folders.add(new File(wf, "stats")); });
            statsFolders = folders;
        } catch (Exception ignored) {
            statsFolders = List.of();
        }
    }

    private void buildAdvancementsFolders() {
        try {
            ArrayList<File> folders = new ArrayList<>();
            Bukkit.getWorlds().forEach(w -> { File wf = w.getWorldFolder(); if (wf != null) folders.add(new File(wf, "advancements")); });
            advancementsFolders = folders;
        } catch (Exception ignored) {
            advancementsFolders = List.of();
        }
    }

    private void loadDataYml() {
        if (!dataFile.exists()) return;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
            for (String k : cfg.getKeys(false)) {
                String v = cfg.getString(k);
                if (v != null) externalCache.put(k, v);
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to load data.yml: " + e.getMessage());
        }
    }

    private void saveDataYml() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            externalCache.forEach(cfg::set);
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    private void updateExternalNameData() {
        String urlStr = plugin.getConfig().getString("modules.web-server.external-name-url", EXTERNAL_NAME_URL_DEFAULT);
        String text = fetchText(urlStr, 4000, 4000);
        if (isBlank(text)) return;

        boolean changed = false;
        for (String raw : text.split("\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            String discordId = parts[0].trim();
            String nick = parts[1].trim();
            if (discordId.isEmpty() || nick.isEmpty()) continue;
            String old = externalCache.put(discordId, nick);
            if (!Objects.equals(old, nick)) changed = true;
        }
        if (changed) saveDataYml();
    }

    private void updateWhitelistData() {
        if (!whitelistRefreshRunning.compareAndSet(false, true)) return;
        try {
            String url = plugin.getConfig().getString("modules.web-server.tickets.whitelist-url", EXTERNAL_WHITELIST_URL_DEFAULT);
            List<IpRule> rules = parseWhitelistRules(fetchText(url, 3000, 3000));
            if (rules != null && !rules.isEmpty()) whitelistRules = rules;
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update whitelist: " + e.getMessage());
        } finally {
            whitelistRefreshRunning.set(false);
        }
    }

    private void updateExternalStatsData() {
        if (!statsRefreshRunning.compareAndSet(false, true)) return;
        try {
            String url = plugin.getConfig().getString("modules.web-server.external-stats-url", EXTERNAL_STATS_URL_DEFAULT);
            String text = fetchText(url, 8000, 8000);
            if (isBlank(text)) return;

            int h = text.hashCode();
            if (h == lastStatsHash) return;
            lastStatsHash = h;

            JsonObject root = parseLenientObject(text);
            if (root == null) return;

            Map<String, String> userToModerator = new HashMap<>();
            JsonObject ut = obj(root, "user_tickets");
            if (ut != null) for (Map.Entry<String, JsonElement> e : ut.entrySet()) {
                String k = e.getKey();
                String v = safeString(e.getValue());
                if (!isBlank(k) && !isBlank(v)) userToModerator.put(k, v);
            }

            Map<String, List<TicketInfo>> byUser = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            collectTickets(root, "tickets", userToModerator, byUser, names);
            collectTickets(root, "closed", userToModerator, byUser, names);

            names.forEach((k, v) -> { if (!isBlank(k) && !isBlank(v)) ticketNameCache.put(k, v); });

            Map<String, List<TicketInfo>> compact = new HashMap<>(byUser.size());
            for (Map.Entry<String, List<TicketInfo>> e : byUser.entrySet()) {
                List<TicketInfo> list = e.getValue();
                if (list == null || list.isEmpty()) continue;
                list.sort(Comparator.comparing(t -> nz(t.timeKey())));
                compact.put(e.getKey(), List.copyOf(list));
            }
            ticketsByUserId = Map.copyOf(compact);

        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update external stats: " + e.getMessage());
        } finally {
            statsRefreshRunning.set(false);
        }
    }

    private void updateDiscordStatsData() {
        try {
            String url = plugin.getConfig().getString("modules.web-server.external-discord-url", EXTERNAL_DISCORD_URL_DEFAULT);
            String text = fetchText(url, 5000, 5000);
            if (isBlank(text)) return;

            int h = text.hashCode();
            if (h == lastDiscordStatsHash) return;

            JsonObject root = parseLenientObject(text);
            if (root == null) return;

            lastDiscordStatsHash = h;
            String json = GSON.toJson(root);
            discordStatsJsonCache = json;

            if (hasDb()) writeMeta("discord_stats_json", json, System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update discord stats: " + e.getMessage());
        }
    }

    private void collectTickets(JsonObject root, String key, Map<String, String> userToModerator, Map<String, List<TicketInfo>> byUser, Map<String, String> names) {
        JsonObject src = obj(root, key);
        if (src == null) return;

        for (Map.Entry<String, JsonElement> e : src.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
            String ticketId = e.getKey();
            JsonObject t = e.getValue().getAsJsonObject();
            String userId = getStringOrNull(t, "userId");
            if (isBlank(userId)) continue;

            String name = getStringOrNull(t, "name");
            if (!isBlank(name)) names.put(userId, name);

            String modId = firstNonBlank(userToModerator.get(userId), getStringOrNull(t, "processedByID"), getStringOrNull(t, "closed_by"), "unknown");
            byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(new TicketInfo(modId, nz(ticketId, "unknown"), t, timeKey(t)));
        }
    }

    private String timeKey(JsonObject t) {
        String best = "";
        best = maxIso(best, getStringOrNull(t, "processedAt"));
        best = maxIso(best, getStringOrNull(t, "closed_at"));
        best = maxIso(best, getStringOrNull(t, "createdAt"));
        best = maxIso(best, getStringOrNull(t, "created_at"));
        return best;
    }

    private String maxIso(String a, String b) {
        if (isBlank(b)) return a;
        if (isBlank(a)) return b;
        return b.compareTo(a) > 0 ? b : a;
    }

    private JsonObject parseLenientObject(String text) {
        try {
            JsonReader jr = new JsonReader(new StringReader(text));
            jr.setLenient(true);
            JsonElement el = JsonParser.parseReader(jr);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeString(JsonElement el) {
        try { return el == null || el.isJsonNull() ? null : el.getAsString(); }
        catch (Exception ignored) { return null; }
    }

    private String fetchText(String urlStr, int ct, int rt) {
        if (isBlank(urlStr)) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(ct);
            c.setReadTimeout(rt);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            try (Scanner s = new Scanner(c.getInputStream(), StandardCharsets.UTF_8)) {
                s.useDelimiter("\\A");
                return s.hasNext() ? s.next() : null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private List<IpRule> parseWhitelistRules(String text) {
        if (isBlank(text)) return null;

        boolean inIps = false;
        ArrayList<IpRule> rules = new ArrayList<>();

        for (String raw : text.split("\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;

            String low = line.toLowerCase(Locale.ROOT);
            if (low.equals("ips:") || low.startsWith("ips:")) { inIps = true; continue; }

            String token = null;
            if (line.startsWith("-")) token = stripQuotes(line.substring(1).trim());
            else if (inIps || looksLikeIpToken(line)) token = stripQuotes(line);

            if (isBlank(token)) continue;
            IpRule r = IpRule.parse(token);
            if (r != null) rules.add(r);
        }
        return rules.isEmpty() ? null : List.copyOf(rules);
    }

    private boolean looksLikeIpToken(String s) {
        String t = stripQuotes(nz(s).trim());
        if (t.isEmpty()) return false;
        int slash = t.indexOf('/');
        String base = slash >= 0 ? t.substring(0, slash) : t;
        if (base.contains("*")) return true;
        String[] parts = base.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) if (p.isEmpty() || !p.chars().allMatch(Character::isDigit)) return false;
        return true;
    }

    private String stripQuotes(String s) {
        String t = nz(s).trim();
        if (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private boolean isIpAllowed(String ip) {
        String norm = normalizeIp(ip);
        if (isBlank(norm)) return false;
        int v = IpRule.ipv4ToInt(norm);
        if (v == -1) return false;
        List<IpRule> rules = whitelistRules;
        if (rules == null || rules.isEmpty()) return false;
        for (IpRule r : rules) if (r.matches(v)) return true;
        return false;
    }

    private void refreshPlayersData() {
        if (!playersRefreshRunning.compareAndSet(false, true)) return;
        try {
            List<AccountEntry> accounts = loadAccountsIfChanged();
            Map<String, String> userCache = loadUserCacheIfChanged();
            Map<String, FlexData> flexByDiscordId = hasDb() ? readAllFlex() : Map.of();
            Map<String, Boolean> activeByDiscordId = hasDb() ? readAllActiveStates() : activeStateByDiscordId;

            if (accounts.isEmpty()) {
                synchronized (summaryLock) {
                    summaryByDiscordId = Map.of();
                    summaryOrder = List.of();
                    discordIdByUuid = Map.of();
                    playersSummaryListCache = "[]";
                }
                if (hasDb()) writeMeta("players_summary_json", "[]", System.currentTimeMillis());
                return;
            }

            long now = System.currentTimeMillis();
            ArrayList<PlayerUpsertRow> rows = new ArrayList<>(accounts.size());

            HashMap<String, JsonObject> newSummaryMap = new HashMap<>(Math.max(16, accounts.size() * 2));
            ArrayList<String> newOrder = new ArrayList<>(accounts.size());
            HashMap<String, String> newDiscordIdByUuid = new HashMap<>(Math.max(16, accounts.size() * 2));
            JsonArray summaryArray = new JsonArray();

            long sumBlocksPlaced = 0L, sumBlocksBroken = 0L, sumWalkBlocks = 0L, sumSwimBlocks = 0L, sumFlyBlocks = 0L;
            long sumJumps = 0L, sumAdv = 0L, sumKillsPlayers = 0L, sumDeaths = 0L, sumKillsMobs = 0L, sumSlapsSent = 0L, sumSlapsRecv = 0L, sumCrafted = 0L;

            for (AccountEntry entry : accounts) {
                String discordId = entry.discordId();
                String minecraftUuid = entry.minecraftUuid();

                UUID uuid;
                try { uuid = UUID.fromString(minecraftUuid); } catch (Exception ignored) { continue; }

                String minecraftName = resolveMinecraftName(discordId, minecraftUuid, userCache);
                SkinLinks links = getSkinLinksForPlayer(minecraftUuid, minecraftName);
                boolean isOnline = onlineUuids.contains(minecraftUuid);

                StatsSnapshot snap = getStatsSnapshot(uuid);
                AdvSnapshot adv = getAdvSnapshot(uuid);

                double playHours = round1(ticksToHours(snap.playTimeTicks()));
                JsonObject statsObj = buildStatsJson(snap, adv, minecraftUuid, isOnline, false);
                JsonObject lpGroups = getLuckPermsGroups(uuid);
                FlexData flexData = flexByDiscordId.getOrDefault(discordId, FlexData.empty());
                boolean active = activeByDiscordId.getOrDefault(discordId, false);

                JsonObject summary = new JsonObject();
                putBase(summary, discordId, minecraftName, minecraftUuid, links, isOnline, active);
                summary.addProperty("play_time_hours", playHours);
                summary.add("flex", buildFlexJson(flexData));
                summary.add("tags", buildTagsJsonForDiscordId(discordId));
                summary.add("luckperms", lpGroups);
                summaryArray.add(summary);

                JsonObject full = new JsonObject();
                putBase(full, discordId, minecraftName, minecraftUuid, links, isOnline, active);
                full.add("tags", buildTagsJsonForDiscordId(discordId));
                full.add("luckperms", lpGroups);
                full.add("stats", statsObj);

                newSummaryMap.put(discordId, summary);
                newOrder.add(discordId);
                newDiscordIdByUuid.put(minecraftUuid, discordId);

                rows.add(new PlayerUpsertRow(
                        discordId, minecraftUuid, minecraftName,
                        nz(minecraftName).toLowerCase(Locale.ROOT),
                        links.skinUrl(), links.headUrl(),
                        isOnline, active, playHours,
                        GSON.toJson(summary), GSON.toJson(full),
                        now, now, snap.mtime()
                ));

                sumBlocksPlaced += Math.max(0L, snap.blocksPlaced());
                sumBlocksBroken += Math.max(0L, snap.blocksBroken());
                sumWalkBlocks += Math.max(0L, cmToBlocks(snap.walkCm() + snap.sprintCm() + snap.crouchCm() + snap.climbCm()));
                sumSwimBlocks += Math.max(0L, cmToBlocks(snap.swimCm()));
                sumFlyBlocks += Math.max(0L, cmToBlocks(snap.flyCm()));
                sumJumps += Math.max(0L, snap.jumps());
                sumAdv += Math.max(0L, adv.completed());
                sumKillsPlayers += Math.max(0L, snap.playerKills());
                sumDeaths += Math.max(0L, snap.deaths());
                sumKillsMobs += Math.max(0L, snap.mobKills());

                ExtraState ex = ensureExtraStateLoaded(minecraftUuid);
                synchronized (ex) {
                    sumSlapsSent += Math.max(0L, ex.slapsSent);
                    sumSlapsRecv += Math.max(0L, ex.slapsReceived);
                }

                sumCrafted += Math.max(0L, snap.craftedTotal());
            }

            String summaryStr = GSON.toJson(summaryArray);
            synchronized (summaryLock) {
                summaryByDiscordId = newSummaryMap;
                summaryOrder = List.copyOf(newOrder);
                discordIdByUuid = newDiscordIdByUuid;
                playersSummaryListCache = summaryStr;
            }

            if (hasDb()) {
                upsertPlayers(rows);
                writeMeta("players_summary_json", summaryStr, now);
            }

            JsonObject server = new JsonObject();
            server.addProperty("season_id", nz(seasonId));
            server.addProperty("season_players_total", readSeasonPlayersCount());
            server.addProperty("season_peak_online", Math.max(0, seasonPeakOnline));

            JsonObject sstats = new JsonObject();
            sstats.addProperty("blocks_placed", sumBlocksPlaced);
            sstats.addProperty("blocks_broken", sumBlocksBroken);

            JsonObject travel = new JsonObject();
            travel.addProperty("walked", sumWalkBlocks);
            travel.addProperty("swum", sumSwimBlocks);
            travel.addProperty("flown", sumFlyBlocks);
            travel.addProperty("total", Math.max(0L, sumWalkBlocks + sumSwimBlocks + sumFlyBlocks));
            sstats.add("travel_blocks", travel);

            sstats.addProperty("jumps", sumJumps);
            sstats.addProperty("advancements", sumAdv);
            sstats.addProperty("kills_players", sumKillsPlayers);
            sstats.addProperty("deaths", sumDeaths);
            sstats.addProperty("kills_mobs", sumKillsMobs);
            sstats.addProperty("slaps_received", sumSlapsRecv);
            sstats.addProperty("slaps_sent", sumSlapsSent);
            sstats.addProperty("items_crafted", sumCrafted);

            server.add("stats", sstats);

            String serverStr = GSON.toJson(server);
            serverStatsJsonCache = serverStr;
            if (hasDb()) writeMeta("server_stats_json", serverStr, now);

        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to refresh players data: " + e.getMessage());
        } finally {
            playersRefreshRunning.set(false);
        }
    }

    private void putBase(JsonObject o, String did, String name, String uuid, SkinLinks links, boolean online, boolean active) {
        o.addProperty("id", nz(did));
        o.addProperty("minecraft_name", nz(name, "Unknown"));
        o.addProperty("minecraft_uuid", nz(uuid));
        o.addProperty("skinUrl", nz(links == null ? null : links.skinUrl()));
        o.addProperty("headUrl", nz(links == null ? null : links.headUrl()));
        o.addProperty("is_online", online);
        o.addProperty("active", active);
    }

    public String getPlayersSummaryJson() {
        String cached = playersSummaryListCache;
        if (!isBlank(cached)) return cached;
        if (!hasDb()) return "[]";
        String v = readMeta("players_summary_json");
        if (isBlank(v)) return "[]";
        playersSummaryListCache = v;
        return v;
    }

    public String getServerStatsJson() {
        String cached = serverStatsJsonCache;
        if (!isBlank(cached) && !cached.equals("{}")) return cached;
        if (!hasDb()) return "{}";
        String v = readMeta("server_stats_json");
        if (isBlank(v)) return "{}";
        serverStatsJsonCache = v;
        return v;
    }

    public String getDiscordStatsJson() {
        String cached = discordStatsJsonCache;
        if (!isBlank(cached) && !cached.equals("{}")) return cached;
        if (!hasDb()) return "{}";
        String v = readMeta("discord_stats_json");
        if (isBlank(v)) return "{}";
        discordStatsJsonCache = v;
        return v;
    }

    public String getWorldsJson() {
        String cached = worldsJsonCache;
        return isBlank(cached) ? "[]" : cached;
    }

    public String getFoliaRegionsJson() {
        String cached = foliaRegionsJsonCache;
        return isBlank(cached) ? "[]" : cached;
    }

    public String getPlayerJsonById(String query) { return getPlayerJsonById(query, null); }

    public String getPlayerJsonById(String query, String remoteIp) {
        String q = nz(query).trim();
        if (q.isEmpty()) return jsonError("Player not found");

        boolean allowed = isIpAllowed(remoteIp);
        String baseJson = hasDb() ? findFullJsonInDb(q) : null;
        if (baseJson == null) baseJson = findFullJsonFallback(q);
        if (baseJson == null) return jsonError("Player not found");

        try {
            JsonObject obj = JsonParser.parseString(baseJson).getAsJsonObject();

            String mcUuidStr = obj.has("minecraft_uuid") && !obj.get("minecraft_uuid").isJsonNull() ? obj.get("minecraft_uuid").getAsString() : null;
            boolean isOnline = !isBlank(mcUuidStr) && onlineUuids.contains(mcUuidStr);
            if (!isBlank(mcUuidStr)) obj.addProperty("is_online", isOnline);

            String discordId = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : null;
            if (!isBlank(discordId)) obj.addProperty("active", isPlayerActive(discordId));

            if (!allowed) obj.addProperty("tickets", "not allowed");
            else obj.add("tickets", buildTicketsJson(discordId));

            UUID mcUuid = null;
            try { if (!isBlank(mcUuidStr)) mcUuid = UUID.fromString(mcUuidStr); } catch (Exception ignored) {}
            obj.add("litebans", liteBansBridge.getLiteBansJson(mcUuid, allowed, remoteIp));

            if (mcUuid != null && !isBlank(mcUuidStr)) {
                StatsSnapshot snap = getStatsSnapshot(mcUuid);
                AdvSnapshot adv = getAdvSnapshot(mcUuid);
                obj.add("stats", buildStatsJson(snap, adv, mcUuidStr, isOnline, allowed));
            }

            JsonObject flex = buildFlexJsonForDiscordId(discordId);
            JsonObject tags = buildTagsJsonForDiscordId(discordId);
            return GSON.toJson(reorderWithFlexAndTags(obj, flex, tags));
        } catch (Exception ignored) {
            return baseJson;
        }
    }

    public String getPlayersByIpJson(String ip, String remoteIp) {
        if (!isIpAllowed(remoteIp)) return "{\"error\":\"Forbidden\"}";
        String normIp = normalizeIp(ip);
        if (isBlank(normIp)) return "{\"error\":\"Invalid IP\"}";

        JsonObject out = new JsonObject();
        out.addProperty("ip", normIp);
        out.add("players", readPlayersByIpJson(normIp));
        return GSON.toJson(out);
    }

    private JsonObject buildTicketsJson(String discordId) {
        JsonObject out = new JsonObject();
        if (isBlank(discordId)) return out;
        List<TicketInfo> list = ticketsByUserId.get(discordId);
        if (list == null || list.isEmpty()) return out;

        for (TicketInfo ti : list) {
            JsonObject t = ti.ticket();
            if (t == null) continue;

            String modId = nz(ti.moderatorId(), "unknown");
            String ticketId = nz(ti.ticketId(), "unknown");

            JsonObject modObj = out.has(modId) && out.get(modId).isJsonObject() ? out.getAsJsonObject(modId) : new JsonObject();
            out.add(modId, modObj);
            modObj.add(ticketId, t);
        }
        return out;
    }

    private JsonObject reorderWithFlexAndTags(JsonObject src, JsonObject flexObj, JsonObject tagsObj) {
        JsonObject out = new JsonObject();
        String[] first = {"id", "minecraft_name", "minecraft_uuid", "skinUrl", "headUrl", "is_online", "active"};
        String[] after = {"luckperms", "stats", "tickets", "litebans"};

        for (String k : first) if (src.has(k)) out.add(k, src.get(k));
        out.add("flex", flexObj == null ? emptyFlexJson() : flexObj);
        out.add("tags", tagsObj == null ? emptyTagsJson() : tagsObj);
        for (String k : after) if (src.has(k)) out.add(k, src.get(k));

        HashSet<String> used = new HashSet<>();
        Collections.addAll(used, first);
        used.add("flex");
        used.add("tags");
        Collections.addAll(used, after);

        for (Map.Entry<String, JsonElement> e : src.entrySet()) {
            String k = e.getKey();
            if (k != null && !used.contains(k)) out.add(k, e.getValue());
        }
        return out;
    }

    private JsonObject buildFlexJson(FlexData d) {
        FlexData v = d == null ? FlexData.empty() : d;
        JsonObject o = new JsonObject();
        o.addProperty("bank", Math.max(0L, v.bank()));
        o.addProperty("color", nz(v.color()));
        o.add("subscription", toJsonArray(v.subscription()));
        o.addProperty("command", nz(v.command()));
        return o;
    }

    private JsonObject buildTagsJsonForDiscordId(String discordId) {
        TagsManager tagsManager = plugin.getTagsManager();
        if (tagsManager == null) {
            return emptyTagsJson();
        }
        try {
            return tagsManager.buildPlayerTagsJson(discordId);
        } catch (Exception ignored) {
            return emptyTagsJson();
        }
    }

    private JsonObject emptyTagsJson() {
        JsonObject out = new JsonObject();
        out.addProperty("guild_id", TagsManager.REQUIRED_GUILD_ID);
        out.addProperty("separator", " ");
        out.addProperty("synced_at", "");
        out.addProperty("count", 0);
        out.addProperty("rendered", "");
        out.add("roles", new JsonArray());
        return out;
    }

    public void refreshPlayersDataAsync() {
        plugin.getFoliaLib().getScheduler().runAsync(task -> refreshPlayersData());
    }

    private Map<String, Boolean> readAllActiveStates() {
        if (!hasDb()) return activeStateByDiscordId;
        return withConn("Failed to load active states from H2: ", activeStateByDiscordId, c -> {
            HashMap<String, Boolean> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT discord_id,active FROM players")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String discordId = rs.getString(1);
                        if (isBlank(discordId)) continue;
                        out.put(discordId, rs.getBoolean(2));
                    }
                }
            }
            Map<String, Boolean> result = Map.copyOf(out);
            activeStateByDiscordId = result;
            return result;
        });
    }

    private JsonObject buildFlexJsonForDiscordId(String discordId) {
        FlexData d = (hasDb() && !isBlank(discordId)) ? readFlex(discordId) : FlexData.empty();
        return buildFlexJson(d);
    }

    private JsonObject emptyFlexJson() {
        return buildFlexJson(FlexData.empty());
    }

    private JsonArray toJsonArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) for (String s : list) if (s != null) arr.add(s);
        return arr;
    }

    public record AdminResponse(int status, String body) {}

    public AdminResponse adminGetFlex(String query) {
        String did = resolveDiscordIdFromAny(query);
        if (did == null) return new AdminResponse(404, "{\"error\":\"Player not found\"}");
        return new AdminResponse(200, GSON.toJson(buildFlexJsonForDiscordId(did)));
    }

    public AdminResponse adminPostFlex(String query, String body) {
        String did = resolveDiscordIdFromAny(query);
        if (did == null) return new AdminResponse(404, "{\"error\":\"Player not found\"}");

        JsonObject patch;
        try {
            JsonElement el = JsonParser.parseString(nz(body));
            if (el == null || !el.isJsonObject()) return new AdminResponse(400, "{\"error\":\"Invalid JSON\"}");
            patch = el.getAsJsonObject();
        } catch (Exception ignored) {
            return new AdminResponse(400, "{\"error\":\"Invalid JSON\"}");
        }

        try {
            FlexData merged = mergeFlex(readFlex(did), patch);
            upsertFlex(did, merged);
            updateCachedPlayerFlex(did, merged);
        } catch (Exception ignored) {
            return new AdminResponse(500, "{\"error\":\"Failed to save\"}");
        }

        return new AdminResponse(200, GSON.toJson(buildFlexJsonForDiscordId(did)));
    }

    private FlexData mergeFlex(FlexData cur, JsonObject patch) {
        long bank = Math.max(0L, cur.bank());
        String color = nz(cur.color());
        List<String> sub = cur.subscription() == null ? List.of() : cur.subscription();
        String command = nz(cur.command());

        if (patch.has("bank") && !patch.get("bank").isJsonNull()) try { bank = Math.max(0L, patch.get("bank").getAsLong()); } catch (Exception ignored) {}
        if (patch.has("color") && !patch.get("color").isJsonNull()) try { color = nz(patch.get("color").getAsString()); } catch (Exception ignored) {}
        if (patch.has("command") && !patch.get("command").isJsonNull()) try { command = nz(patch.get("command").getAsString()); } catch (Exception ignored) {}

        if (patch.has("subscription") && !patch.get("subscription").isJsonNull()) {
            ArrayList<String> list = new ArrayList<>();
            try {
                JsonElement el = patch.get("subscription");
                if (el.isJsonArray()) for (JsonElement it : el.getAsJsonArray()) if (it != null && !it.isJsonNull()) list.add(nz(it.getAsString()));
                else if (el.isJsonPrimitive()) list.add(nz(el.getAsString()));
            } catch (Exception ignored) {}
            sub = List.copyOf(list);
        }

        return new FlexData(bank, color, sub, command);
    }

    private void upsertFlex(String discordId, FlexData data) throws Exception {
        String subJson = GSON.toJson(toJsonArray(data.subscription()));
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO flex (discord_id,bank,color,subscription,command) KEY(discord_id) VALUES (?,?,?,?,?)")) {
                ps.setString(1, discordId);
                ps.setLong(2, Math.max(0L, data.bank()));
                ps.setString(3, nz(data.color()));
                ps.setString(4, subJson);
                ps.setString(5, nz(data.command()));
                ps.executeUpdate();
            }
        } finally {
            pool.release(c);
        }
    }

    private FlexData readFlex(String discordId) {
        if (isBlank(discordId) || !hasDb()) return FlexData.empty();
        return withConn(null, FlexData.empty(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT bank,color,subscription,command FROM flex WHERE discord_id=? LIMIT 1")) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return FlexData.empty();
                    long bank = Math.max(0L, rs.getLong(1));
                    String color = nz(rs.getString(2));
                    String subStr = rs.getString(3);
                    String command = nz(rs.getString(4));

                    List<String> subs = List.of();
                    if (!isBlank(subStr)) {
                        try {
                            JsonElement el = JsonParser.parseString(subStr);
                            if (el != null && el.isJsonArray()) {
                                ArrayList<String> tmp = new ArrayList<>();
                                for (JsonElement it : el.getAsJsonArray()) if (it != null && !it.isJsonNull()) tmp.add(nz(it.getAsString()));
                                subs = List.copyOf(tmp);
                            }
                        } catch (Exception ignored) {}
                    }
                    return new FlexData(bank, color, subs, command);
                }
            }
        });
    }

    private Map<String, FlexData> readAllFlex() {
        if (!hasDb()) return Map.of();
        return withConn(null, Map.of(), c -> {
            HashMap<String, FlexData> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT discord_id,bank,color,subscription,command FROM flex");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String discordId = rs.getString(1);
                    if (isBlank(discordId)) continue;

                    long bank = Math.max(0L, rs.getLong(2));
                    String color = nz(rs.getString(3));
                    String subStr = rs.getString(4);
                    String command = nz(rs.getString(5));

                    List<String> subs = List.of();
                    if (!isBlank(subStr)) {
                        try {
                            JsonElement el = JsonParser.parseString(subStr);
                            if (el != null && el.isJsonArray()) {
                                ArrayList<String> tmp = new ArrayList<>();
                                for (JsonElement it : el.getAsJsonArray()) if (it != null && !it.isJsonNull()) tmp.add(nz(it.getAsString()));
                                subs = List.copyOf(tmp);
                            }
                        } catch (Exception ignored) {}
                    }

                    out.put(discordId, new FlexData(bank, color, subs, command));
                }
            }
            return Map.copyOf(out);
        });
    }

    private void updateCachedPlayerFlex(String discordId, FlexData flexData) {
        if (isBlank(discordId)) return;

        String newSummary = null;
        synchronized (summaryLock) {
            JsonObject obj = summaryByDiscordId.get(discordId);
            if (obj == null) return;

            obj.add("flex", buildFlexJson(flexData));

            JsonArray arr = new JsonArray();
            for (String id : summaryOrder) {
                JsonObject o = summaryByDiscordId.get(id);
                if (o != null) arr.add(o);
            }

            newSummary = GSON.toJson(arr);
            playersSummaryListCache = newSummary;
        }

        if (hasDb() && newSummary != null) writeMeta("players_summary_json", newSummary, System.currentTimeMillis());
    }

    private void updateCachedPlayerActive(String discordId, boolean active) {
        if (isBlank(discordId)) return;

        String newSummary = null;
        synchronized (summaryLock) {
            JsonObject obj = summaryByDiscordId.get(discordId);
            if (obj != null) obj.addProperty("active", active);

            JsonArray arr = new JsonArray();
            for (String id : summaryOrder) {
                JsonObject o = summaryByDiscordId.get(id);
                if (o != null) arr.add(o);
            }

            newSummary = GSON.toJson(arr);
            playersSummaryListCache = newSummary;
        }

        if (hasDb() && newSummary != null) writeMeta("players_summary_json", newSummary, System.currentTimeMillis());
    }

    private String injectActiveIntoJson(String json, boolean active) {
        if (isBlank(json)) return json;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                obj.addProperty("active", active);
                return GSON.toJson(obj);
            }
        } catch (Exception ignored) {}
        return json;
    }

    private void updatePlayerActiveInDb(String discordId, boolean active) {
        withConn("Failed to update active state in H2: ", null, c -> {
            String summaryJson = null;
            String fullJson = null;

            try (PreparedStatement ps = c.prepareStatement("SELECT summary_json,full_json FROM players WHERE discord_id=? LIMIT 1")) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        summaryJson = rs.getString(1);
                        fullJson = rs.getString(2);
                    }
                }
            }

            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET active=?,summary_json=?,full_json=?,summary_updated_at=?,full_updated_at=? WHERE discord_id=?")) {
                ps.setBoolean(1, active);
                ps.setString(2, injectActiveIntoJson(summaryJson, active));
                ps.setString(3, injectActiveIntoJson(fullJson, active));
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.setString(6, discordId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String resolveDiscordIdFromAny(String q) {
        String query = nz(q).trim();
        if (query.isEmpty()) return null;

        if (hasDb()) {
            String fromDb = withConn(null, null, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT discord_id FROM players WHERE discord_id=? OR minecraft_uuid=? OR minecraft_name_lc=? LIMIT 1")) {
                    ps.setString(1, query);
                    ps.setString(2, query);
                    ps.setString(3, query.toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String did = rs.getString(1);
                            return isBlank(did) ? null : did;
                        }
                    }
                }
                return null;
            });
            if (fromDb != null) return fromDb;
        }

        List<AccountEntry> accounts = loadAccountsIfChanged();
        Map<String, String> userCache = loadUserCacheIfChanged();
        String nq = query.toLowerCase(Locale.ROOT);

        for (AccountEntry e : accounts) {
            String did = e.discordId();
            String uuid = e.minecraftUuid();
            String name = resolveMinecraftName(did, uuid, userCache);
            if (did != null && did.equalsIgnoreCase(nq)) return did;
            if (uuid != null && uuid.equalsIgnoreCase(nq)) return did;
            if (name != null && name.equalsIgnoreCase(nq)) return did;
        }
        return null;
    }

    private String findFullJsonInDb(String q) {
        return withConn("Failed to lookup player in H2: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT full_json FROM players WHERE discord_id=? OR minecraft_uuid=? OR minecraft_name_lc=? LIMIT 1")) {
                ps.setString(1, q);
                ps.setString(2, q);
                ps.setString(3, q.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        return isBlank(json) ? null : json;
                    }
                }
            }
            return null;
        });
    }

    private String findFullJsonFallback(String q) {
        List<AccountEntry> accounts = loadAccountsIfChanged();
        Map<String, String> userCache = loadUserCacheIfChanged();

        String nq = nz(q).trim().toLowerCase(Locale.ROOT);
        if (nq.isEmpty()) return null;

        for (AccountEntry entry : accounts) {
            String did = entry.discordId();
            String mcUuid = entry.minecraftUuid();
            String mcName = resolveMinecraftName(did, mcUuid, userCache);

            if (!(did.equalsIgnoreCase(nq) || mcUuid.equalsIgnoreCase(nq) || (mcName != null && mcName.equalsIgnoreCase(nq)))) continue;

            UUID uuid;
            try { uuid = UUID.fromString(mcUuid); } catch (Exception ignored) { return null; }

            SkinLinks links = getSkinLinksForPlayer(mcUuid, mcName);
            boolean online = onlineUuids.contains(mcUuid);

            StatsSnapshot snap = getStatsSnapshot(uuid);
            AdvSnapshot adv = getAdvSnapshot(uuid);

            JsonObject full = new JsonObject();
            putBase(full, did, mcName, mcUuid, links, online, activeStateByDiscordId.getOrDefault(did, false));
            full.add("tags", buildTagsJsonForDiscordId(did));
            full.add("luckperms", getLuckPermsGroups(uuid));
            full.add("stats", buildStatsJson(snap, adv, mcUuid, online, false));
            return GSON.toJson(full);
        }
        return null;
    }

    private void updateSingleOnlineInDb(String discordId, boolean online) {
        withConn("Failed to update is_online in H2: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET is_online=? WHERE discord_id=?")) {
                ps.setBoolean(1, online);
                ps.setString(2, discordId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void upsertPlayers(List<PlayerUpsertRow> rows) {
        if (rows == null || rows.isEmpty()) return;

        withConn("Failed to upsert players to H2: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO players (discord_id,minecraft_uuid,minecraft_name,minecraft_name_lc,skin_url,head_url,is_online,active,play_time_hours,summary_json,full_json,summary_updated_at,full_updated_at,stats_mtime) " +
                            "KEY(discord_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )) {
                int batch = 0;
                for (PlayerUpsertRow r : rows) {
                    ps.setString(1, r.discordId());
                    ps.setString(2, r.minecraftUuid());
                    ps.setString(3, r.minecraftName());
                    ps.setString(4, r.minecraftNameLc());
                    ps.setString(5, r.skinUrl());
                    ps.setString(6, r.headUrl());
                    ps.setBoolean(7, r.isOnline());
                    ps.setBoolean(8, r.active());
                    ps.setDouble(9, r.playTimeHours());
                    ps.setString(10, r.summaryJson());
                    ps.setString(11, r.fullJson());
                    ps.setLong(12, r.summaryUpdatedAt());
                    ps.setLong(13, r.fullUpdatedAt());
                    ps.setLong(14, r.statsMtime());
                    ps.addBatch();
                    if (++batch >= 500) { ps.executeBatch(); batch = 0; }
                }
                if (batch > 0) ps.executeBatch();
            }
            return null;
        });
    }

    private void writeMeta(String key, String value, long now) {
        withConn("Failed to write meta: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO meta (k,v,updated_at) KEY(k) VALUES (?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String readMeta(String key) {
        return withConn("Failed to read meta: ", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k=? LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private void handleOnlineChange(String uuid, boolean isOnline) {
        if (isBlank(uuid)) return;

        if (isOnline) onlineUuids.add(uuid);
        else onlineUuids.remove(uuid);

        if (isOnline) updateSeasonPeakOnlineIfNeeded(safeOnlineCount());

        String discordId = discordIdByUuid.get(uuid);
        if (isBlank(discordId)) return;

        boolean changed;
        synchronized (summaryLock) {
            JsonObject obj = summaryByDiscordId.get(discordId);
            if (obj == null) return;

            boolean wasOnline = obj.has("is_online") && !obj.get("is_online").isJsonNull() && obj.get("is_online").getAsBoolean();
            changed = (wasOnline != isOnline);
            if (changed) obj.addProperty("is_online", isOnline);

            if (changed) {
                JsonArray arr = new JsonArray();
                for (String id : summaryOrder) {
                    JsonObject o = summaryByDiscordId.get(id);
                    if (o != null) arr.add(o);
                }
                playersSummaryListCache = GSON.toJson(arr);
            }
        }

        if (changed && hasDb()) plugin.getFoliaLib().getScheduler().runAsync(t -> updateSingleOnlineInDb(discordId, isOnline));
    }

    private List<AccountEntry> loadAccountsIfChanged() {
        File a = new File(discordSrvFolder, "accounts.aof");
        File b = new File(discordSrvFolder, "accounts-old.aof");
        long lmA = a.exists() ? a.lastModified() : -1L;
        long lmB = b.exists() ? b.lastModified() : -1L;

        List<AccountEntry> cached = accountsList;
        if (lmA == accountsLastModified && lmB == accountsOldLastModified && cached != null) return cached;

        if (lmA == -1L && lmB == -1L) {
            accountsList = List.of();
            accountsLastModified = accountsOldLastModified = -1L;
            return accountsList;
        }

        List<AccountEntry> freshA = a.exists() ? readAccountsFile(a) : List.of();
        List<AccountEntry> freshB = b.exists() ? readAccountsFile(b) : List.of();

        LinkedHashMap<String, AccountEntry> byDiscord = new LinkedHashMap<>(Math.max(16, freshA.size() + freshB.size()));
        HashMap<String, String> discordByUuid = new HashMap<>(Math.max(16, freshA.size() + freshB.size()));

        for (AccountEntry e : freshA) putAccountPreferNew(byDiscord, discordByUuid, e);
        for (AccountEntry e : freshB) putAccountIfNoConflicts(byDiscord, discordByUuid, e);

        accountsLastModified = lmA;
        accountsOldLastModified = lmB;
        accountsList = List.copyOf(byDiscord.values());
        return accountsList;
    }

    private List<AccountEntry> readAccountsFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return List.of();
        ArrayList<AccountEntry> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 2) continue;
                String did = parts[0].trim();
                String uuid = parts[1].trim();
                if (!did.isEmpty() && !uuid.isEmpty()) list.add(new AccountEntry(did, uuid));
            }
        } catch (Exception e) {
            plugin.getLogManager().severe("Failed to read DiscordSRV " + file.getName() + ": " + e.getMessage());
        }
        return list;
    }

    private void putAccountPreferNew(LinkedHashMap<String, AccountEntry> byDiscord, Map<String, String> discordByUuid, AccountEntry e) {
        if (e == null || isBlank(e.discordId()) || isBlank(e.minecraftUuid())) return;

        String did = e.discordId(), uuidKey = e.minecraftUuid().toLowerCase(Locale.ROOT);

        AccountEntry prev = byDiscord.get(did);
        if (prev != null && !isBlank(prev.minecraftUuid())) discordByUuid.remove(prev.minecraftUuid().toLowerCase(Locale.ROOT));

        String otherDid = discordByUuid.get(uuidKey);
        if (otherDid != null && !otherDid.equals(did)) byDiscord.remove(otherDid);

        byDiscord.put(did, e);
        discordByUuid.put(uuidKey, did);
    }

    private void putAccountIfNoConflicts(LinkedHashMap<String, AccountEntry> byDiscord, Map<String, String> discordByUuid, AccountEntry e) {
        if (e == null || isBlank(e.discordId()) || isBlank(e.minecraftUuid())) return;
        if (byDiscord.containsKey(e.discordId())) return;
        String uuidKey = e.minecraftUuid().toLowerCase(Locale.ROOT);
        if (discordByUuid.containsKey(uuidKey)) return;
        byDiscord.put(e.discordId(), e);
        discordByUuid.put(uuidKey, e.discordId());
    }

    private Map<String, String> loadUserCacheIfChanged() {
        if (!userCacheFile.exists()) {
            userCacheMap = Map.of();
            userCacheLastModified = -1L;
            return userCacheMap;
        }

        long lm = userCacheFile.lastModified();
        Map<String, String> cached = userCacheMap;
        if (lm == userCacheLastModified && cached != null) return cached;

        HashMap<String, String> map = new HashMap<>();
        try (FileReader r = new FileReader(userCacheFile)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el.isJsonArray()) {
                for (JsonElement it : el.getAsJsonArray()) {
                    if (!it.isJsonObject()) continue;
                    JsonObject o = it.getAsJsonObject();
                    if (o.has("uuid") && o.has("name")) map.put(o.get("uuid").getAsString(), o.get("name").getAsString());
                }
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to read usercache.json: " + e.getMessage());
        }

        userCacheLastModified = lm;
        userCacheMap = map;
        return map;
    }

    private String resolveMinecraftName(String discordId, String minecraftUuid, Map<String, String> userCache) {
        String a = externalCache.get(discordId);
        if (!isBlank(a)) return a;

        String b = userCache.get(minecraftUuid);
        if (!isBlank(b)) return b;

        String c = ticketNameCache.get(discordId);
        if (!isBlank(c)) return c;

        List<TicketInfo> list = ticketsByUserId.get(discordId);
        if (list != null && !list.isEmpty()) {
            for (int i = list.size() - 1; i >= 0; i--) {
                JsonObject t = list.get(i).ticket();
                String name = getStringOrNull(t, "name");
                if (!isBlank(name)) return name;
            }
        }
        return "Unknown";
    }

    private void updateSkinsData() {
        try {
            String response = fetchText(SKINS_DATABASE_URL, 5000, 5000);
            if (isBlank(response)) return;

            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonArray()) return;

            HashMap<String, String> head = new HashMap<>();
            HashMap<String, String> headByName = new HashMap<>();
            HashMap<String, String> skin = new HashMap<>();
            HashMap<String, String> skinByName = new HashMap<>();

            for (JsonElement el : parsed.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject entry = el.getAsJsonObject();
                if (!entry.has("playerUuid")) continue;

                String playerUuid = entry.get("playerUuid").getAsString();
                SkinLinks links = resolveSkinLinksFromEntry(entry, playerUuid);
                String uuidKey = normalizeUuidKey(playerUuid);

                head.put(uuidKey, links.headUrl());
                skin.put(uuidKey, links.skinUrl());

                String name = getStringOrNull(entry, "lastKnownName");
                if (!isBlank(name)) {
                    String nameKey = name.toLowerCase(Locale.ROOT);
                    headByName.put(nameKey, links.headUrl());
                    skinByName.put(nameKey, links.skinUrl());
                }
            }

            skinsHeadUrlCache.clear();
            skinsNameHeadUrlCache.clear();
            skinsSkinUrlCache.clear();
            skinsNameSkinUrlCache.clear();
            skinsHeadUrlCache.putAll(head);
            skinsNameHeadUrlCache.putAll(headByName);
            skinsSkinUrlCache.putAll(skin);
            skinsNameSkinUrlCache.putAll(skinByName);

        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update skins database: " + e.getMessage());
        }
    }

    private SkinLinks getSkinLinksForPlayer(String minecraftUuid, String minecraftName) {
        String uuidKey = normalizeUuidKey(minecraftUuid);
        String nameKey = nz(minecraftName).toLowerCase(Locale.ROOT);

        String skinUrl = skinsSkinUrlCache.get(uuidKey);
        String headUrl = skinsHeadUrlCache.get(uuidKey);

        if (isBlank(skinUrl) && !nameKey.isBlank()) skinUrl = skinsNameSkinUrlCache.get(nameKey);
        if (isBlank(headUrl) && !nameKey.isBlank()) headUrl = skinsNameHeadUrlCache.get(nameKey);

        if (isBlank(skinUrl)) skinUrl = buildFallbackSkinUrl(minecraftUuid, minecraftName);
        if (isBlank(headUrl)) headUrl = buildFallbackHeadUrl(minecraftUuid, minecraftName);

        return new SkinLinks(skinUrl, headUrl);
    }

    private SkinLinks resolveSkinLinksFromEntry(JsonObject entry, String playerUuid) {
        String skinUrl = getStringOrNull(entry, "skinUrl");
        String headUrl = getStringOrNull(entry, "headUrl");

        if (isBlank(skinUrl)) {
            String sourceUrl = getStringOrNull(entry, "sourceUrl");
            if (!isBlank(sourceUrl)) skinUrl = sourceUrl;
        }

        if (isBlank(skinUrl) || isBlank(headUrl)) {
            String textureId = resolveTextureIdFromSkinEntry(entry);
            if (!isBlank(textureId)) {
                if (isBlank(skinUrl)) skinUrl = "https://textures.minecraft.net/texture/" + textureId;
                if (isBlank(headUrl)) headUrl = "https://mc-heads.net/avatar/" + textureId + ".png";
            }
        }

        if (isBlank(skinUrl)) skinUrl = buildFallbackSkinUrl(playerUuid, getStringOrNull(entry, "lastKnownName"));
        if (isBlank(headUrl)) headUrl = buildFallbackHeadUrl(playerUuid, getStringOrNull(entry, "lastKnownName"));

        return new SkinLinks(skinUrl, headUrl);
    }

    private String resolveTextureIdFromSkinEntry(JsonObject entry) {
        String skinHash = getStringOrNull(entry, "skinHash");
        if (!isBlank(skinHash)) return skinHash;

        if (entry.has("decoded")) {
            try {
                JsonElement decodedElement = entry.get("decoded");
                JsonObject decodedObj = decodedElement.isJsonObject()
                        ? decodedElement.getAsJsonObject()
                        : JsonParser.parseString(decodedElement.getAsString()).getAsJsonObject();
                String v = extractTextureIdFromDecodedObject(decodedObj);
                if (!isBlank(v)) return v;
            } catch (Exception ignored) {}
        }

        String value = getStringOrNull(entry, "value");
        if (!isBlank(value)) {
            try {
                String decodedValue = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                String v = extractTextureIdFromDecoded(decodedValue);
                if (!isBlank(v)) return v;
            } catch (Exception ignored) {}
        }

        String raw = getStringOrNull(entry, "skinUrlRaw");
        String v = extractTextureIdFromUrl(raw);
        if (!isBlank(v)) return v;

        return extractTextureIdFromUrl(getStringOrNull(entry, "skinUrl"));
    }

    private String getStringOrNull(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return null;
        try { return json.get(key).getAsString(); } catch (Exception ignored) { return null; }
    }

    private String buildFallbackSkinUrl(String minecraftUuid, String minecraftName) {
        return "https://mc-heads.net/skin/" + resolveFallbackIdentifier(minecraftUuid, minecraftName);
    }

    private String buildFallbackHeadUrl(String minecraftUuid, String minecraftName) {
        return "https://mc-heads.net/avatar/" + resolveFallbackIdentifier(minecraftUuid, minecraftName);
    }

    private String resolveFallbackIdentifier(String minecraftUuid, String minecraftName) {
        if (!isBlank(minecraftName) && !minecraftName.equalsIgnoreCase("Unknown")) return minecraftName;
        if (!isBlank(minecraftUuid)) return minecraftUuid;
        return "Steve";
    }

    private String normalizeUuidKey(String uuid) { return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT); }

    private String extractTextureIdFromDecoded(String decodedJson) {
        try { return extractTextureIdFromDecodedObject(JsonParser.parseString(decodedJson).getAsJsonObject()); }
        catch (Exception ignored) { return null; }
    }

    private String extractTextureIdFromDecodedObject(JsonObject decoded) {
        if (decoded == null || !decoded.has("textures")) return null;
        JsonObject textures = decoded.getAsJsonObject("textures");
        if (textures == null || !textures.has("SKIN")) return null;
        JsonObject skin = textures.getAsJsonObject("SKIN");
        if (skin == null || !skin.has("url")) return null;
        return extractTextureIdFromUrl(skin.get("url").getAsString());
    }

    private String extractTextureIdFromUrl(String textureUrl) {
        if (isBlank(textureUrl)) return null;
        int i = textureUrl.lastIndexOf('/');
        return i >= 0 ? textureUrl.substring(i + 1) : textureUrl;
    }

    private StatsSnapshot getStatsSnapshot(UUID uuid) {
        String key = uuid.toString();
        StatsCacheEntry cached = statsCache.get(key);

        StatsFileRef ref = findLatestStatsFile(uuid);
        long mtime = ref == null ? 0L : ref.mtime();

        if (cached != null && cached.mtime() == mtime) return cached.snapshot();

        StatsSnapshot snap = ref == null ? StatsSnapshot.empty(0L) : parseStatsFile(ref.path(), mtime);
        statsCache.put(key, new StatsCacheEntry(mtime, snap));
        return snap;
    }

    private StatsFileRef findLatestStatsFile(UUID uuid) {
        String fn = uuid + ".json";
        long best = 0L;
        Path bestPath = null;

        for (File folder : statsFolders) {
            if (folder == null) continue;
            try {
                File f = new File(folder, fn);
                if (!f.exists() || !f.isFile()) continue;
                long lm = f.lastModified();
                if (lm > best) { best = lm; bestPath = f.toPath(); }
            } catch (Exception ignored) {}
        }
        return bestPath == null ? null : new StatsFileRef(bestPath, best);
    }

    private StatsSnapshot parseStatsFile(Path path, long mtime) {
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootEl = JsonParser.parseReader(br);
            if (!rootEl.isJsonObject()) return StatsSnapshot.empty(mtime);

            JsonObject root = rootEl.getAsJsonObject();
            JsonObject statsRoot = obj(root, "stats");
            if (statsRoot == null) return StatsSnapshot.empty(mtime);

            JsonObject custom = obj(statsRoot, "minecraft:custom");

            long playTime = getLong(custom, "minecraft:play_time", "minecraft:play_one_minute", "stat.playOneMinute");
            long deaths = getLong(custom, "minecraft:deaths", "stat.deaths");
            long playerKills = getLong(custom, "minecraft:player_kills", "stat.playerKills");
            long mobKills = getLong(custom, "minecraft:mob_kills", "stat.mobKills");
            long walkCm = getLong(custom, "minecraft:walk_one_cm", "minecraft:walked_one_cm", "stat.walkOneCm");
            long sprintCm = getLong(custom, "minecraft:sprint_one_cm", "stat.sprintOneCm");
            long crouchCm = getLong(custom, "minecraft:crouch_one_cm", "stat.crouchOneCm");
            long climbCm = getLong(custom, "minecraft:climb_one_cm", "stat.climbOneCm");
            long swimCm = getLong(custom, "minecraft:swim_one_cm", "stat.swimOneCm");
            long flyCm = getLong(custom, "minecraft:aviate_one_cm", "stat.aviateOneCm");
            long jumps = getLong(custom, "minecraft:jump", "stat.jump");

            JsonObject minedObj = obj(statsRoot, "minecraft:mined");
            JsonObject usedObj = obj(statsRoot, "minecraft:used");
            JsonObject craftedObj = obj(statsRoot, "minecraft:crafted");

            long blocksBroken = sumValues(minedObj);
            long blocksPlaced = sumBlockValues(usedObj);
            long craftedTotal = sumValues(craftedObj);

            return new StatsSnapshot(
                    mtime,
                    playTime,
                    deaths,
                    playerKills,
                    mobKills,
                    walkCm,
                    sprintCm,
                    crouchCm,
                    climbCm,
                    swimCm,
                    flyCm,
                    jumps,
                    blocksBroken,
                    blocksPlaced,
                    craftedTotal
            );
        } catch (Exception ignored) {
            return StatsSnapshot.empty(mtime);
        }
    }

    private AdvSnapshot getAdvSnapshot(UUID uuid) {
        String key = uuid.toString();
        AdvCacheEntry cached = advCache.get(key);

        AdvFileRef ref = findLatestAdvFile(uuid);
        long mtime = ref == null ? 0L : ref.mtime();

        if (cached != null && cached.mtime() == mtime) return cached.snapshot();

        AdvSnapshot snap = ref == null ? new AdvSnapshot(0L, 0L) : parseAdvFile(ref.path(), mtime);
        advCache.put(key, new AdvCacheEntry(mtime, snap));
        return snap;
    }

    private AdvFileRef findLatestAdvFile(UUID uuid) {
        String fn = uuid + ".json";
        long best = 0L;
        Path bestPath = null;

        for (File folder : advancementsFolders) {
            if (folder == null) continue;
            try {
                File f = new File(folder, fn);
                if (!f.exists() || !f.isFile()) continue;
                long lm = f.lastModified();
                if (lm > best) { best = lm; bestPath = f.toPath(); }
            } catch (Exception ignored) {}
        }
        return bestPath == null ? null : new AdvFileRef(bestPath, best);
    }

    private AdvSnapshot parseAdvFile(Path path, long mtime) {
        long done = 0L;
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootEl = JsonParser.parseReader(br);
            if (rootEl == null || !rootEl.isJsonObject()) return new AdvSnapshot(mtime, 0L);
            JsonObject root = rootEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String key = e.getKey();
                if (isBlank(key)) continue;
                String low = key.toLowerCase(Locale.ROOT);
                if (low.startsWith("minecraft:recipes/") || low.contains(":recipes/")) continue;

                JsonElement val = e.getValue();
                if (val == null || !val.isJsonObject()) continue;
                JsonObject o = val.getAsJsonObject();
                if (!o.has("done") || o.get("done").isJsonNull()) continue;
                boolean d;
                try { d = o.get("done").getAsBoolean(); } catch (Exception ignored) { continue; }
                if (d) done++;
            }
            return new AdvSnapshot(mtime, done);
        } catch (Exception ignored) {
            return new AdvSnapshot(mtime, 0L);
        }
    }

    private JsonObject buildStatsJson(StatsSnapshot s, AdvSnapshot a, String minecraftUuid, boolean isOnline, boolean includeIps) {
        long now = System.currentTimeMillis();

        TimeState t = ensureTimeStateLoaded(minecraftUuid, now);
        ExtraState ex = ensureExtraStateLoaded(minecraftUuid);

        long lastSeenMs;
        long activeTodayMs;
        long activeWeekMs;
        long activeMonthMs;
        long afkTotalMs;

        synchronized (t) {
            lastSeenMs = t.lastSeenMs;
            activeTodayMs = t.activeTodayMs;
            activeWeekMs = t.activeWeekMs;
            activeMonthMs = t.activeMonthMs;
            afkTotalMs = t.afkTotalMs;
        }

        if (lastSeenMs <= 0 && s != null && s.mtime() > 0) lastSeenMs = s.mtime();

        double hoursAgo = 0.0;
        if (!isOnline && lastSeenMs > 0) {
            long diff = Math.max(0L, now - lastSeenMs);
            hoursAgo = round1(diff / 3600000.0);
        }

        JsonObject out = new JsonObject();

        JsonObject time = new JsonObject();
        time.addProperty("last_seen_hours_ago", hoursAgo);
        time.add("total_played", hmFromTicks(s == null ? 0L : s.playTimeTicks()));
        time.add("afk_time", hmFromMs(afkTotalMs));
        time.add("played_today", hmFromMs(activeTodayMs));
        time.add("played_week", hmFromMs(activeWeekMs));
        time.add("played_month", hmFromMs(activeMonthMs));
        out.add("time", time);

        JsonObject player = new JsonObject();
        player.addProperty("blocks_placed", s == null ? 0L : Math.max(0L, s.blocksPlaced()));
        player.addProperty("blocks_broken", s == null ? 0L : Math.max(0L, s.blocksBroken()));

        long walked = s == null ? 0L : cmToBlocks(s.walkCm() + s.sprintCm() + s.crouchCm() + s.climbCm());
        long swum = s == null ? 0L : cmToBlocks(s.swimCm());
        long flown = s == null ? 0L : cmToBlocks(s.flyCm());

        JsonObject travel = new JsonObject();
        travel.addProperty("walked", Math.max(0L, walked));
        travel.addProperty("swum", Math.max(0L, swum));
        travel.addProperty("flown", Math.max(0L, flown));
        travel.addProperty("total", Math.max(0L, walked + swum + flown));
        player.add("travel_blocks", travel);

        player.addProperty("jumps", s == null ? 0L : Math.max(0L, s.jumps()));
        player.addProperty("advancements", a == null ? 0L : Math.max(0L, a.completed()));
        player.addProperty("kills_players", s == null ? 0L : Math.max(0L, s.playerKills()));
        player.addProperty("deaths", s == null ? 0L : Math.max(0L, s.deaths()));
        player.addProperty("kills_mobs", s == null ? 0L : Math.max(0L, s.mobKills()));

        synchronized (ex) {
            player.addProperty("slaps_received", Math.max(0L, ex.slapsReceived));
            player.addProperty("slaps_sent", Math.max(0L, ex.slapsSent));
        }

        player.addProperty("items_crafted", s == null ? 0L : Math.max(0L, s.craftedTotal()));

        if (includeIps) player.add("linked_ips", readLinkedIpsJson(minecraftUuid));
        else player.addProperty("linked_ips", "not allowed");

        out.add("player", player);
        return out;
    }

    private JsonObject readLinkedIpsJson(String minecraftUuid) {
        JsonObject out = new JsonObject();
        if (!hasDb() || isBlank(minecraftUuid)) return out;

        return withConn(null, out, c -> {
            JsonObject result = new JsonObject();
            ArrayList<IpEntry> ownIps = new ArrayList<>();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ip, first_seen_ms, last_seen_ms, cnt " +
                            "FROM player_ips WHERE minecraft_uuid=? " +
                            "ORDER BY last_seen_ms DESC, ip ASC")) {
                ps.setString(1, minecraftUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String ip = normalizeIp(rs.getString(1));
                        if (isBlank(ip)) continue;
                        ownIps.add(new IpEntry(
                                ip,
                                Math.max(0L, rs.getLong(2)),
                                Math.max(0L, rs.getLong(3)),
                                Math.max(0L, rs.getLong(4))
                        ));
                    }
                }
            }

            for (IpEntry entry : ownIps) {
                LinkedHashSet<String> otherPlayers = new LinkedHashSet<>();

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT DISTINCT p.minecraft_uuid, COALESCE(pl.minecraft_name, '') " +
                                "FROM player_ips p " +
                                "LEFT JOIN players pl ON pl.minecraft_uuid = p.minecraft_uuid " +
                                "WHERE p.ip=? AND p.minecraft_uuid<>?")) {
                    ps.setString(1, entry.ip());
                    ps.setString(2, minecraftUuid);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String otherUuid = nz(rs.getString(1));
                            String otherName = nz(rs.getString(2)).trim();

                            if (isBlank(otherName)) otherName = resolveFallbackMinecraftNameByUuid(otherUuid);
                            if (!isBlank(otherName)) otherPlayers.add(otherName);
                        }
                    }
                }

                JsonObject ipObj = new JsonObject();
                if (otherPlayers.isEmpty()) ipObj.add("players", JsonNull.INSTANCE);
                else ipObj.addProperty("players", String.join(", ", otherPlayers));

                ipObj.addProperty("first_seen_ms", entry.firstSeenMs());
                ipObj.addProperty("last_seen_ms", entry.lastSeenMs());
                ipObj.addProperty("count", entry.count());

                result.add(entry.ip(), ipObj);
            }

            return result;
        });
    }

    private JsonArray readPlayersByIpJson(String ip) {
        JsonArray arr = new JsonArray();
        if (!hasDb() || isBlank(ip)) return arr;

        return withConn(null, arr, c -> {
            JsonArray out = new JsonArray();
            String sql =
                    "SELECT COALESCE(pl.minecraft_name, ''), p.minecraft_uuid, p.first_seen_ms, p.last_seen_ms, p.cnt " +
                            "FROM player_ips p " +
                            "LEFT JOIN players pl ON pl.minecraft_uuid = p.minecraft_uuid " +
                            "WHERE p.ip=? " +
                            "ORDER BY p.last_seen_ms DESC, p.minecraft_uuid";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();

                        String name = nz(rs.getString(1)).trim();
                        String uuid = nz(rs.getString(2)).trim();
                        if (isBlank(name)) name = resolveFallbackMinecraftNameByUuid(uuid);

                        o.addProperty("minecraft_name", nz(name));
                        o.addProperty("minecraft_uuid", uuid);
                        o.addProperty("first_seen_ms", Math.max(0L, rs.getLong(3)));
                        o.addProperty("last_seen_ms", Math.max(0L, rs.getLong(4)));
                        o.addProperty("count", Math.max(0L, rs.getLong(5)));
                        out.add(o);
                    }
                }
            }
            return out;
        });
    }

    private void recordPlayerIp(String minecraftUuid, String ip, long now) {
        String normIp = normalizeIp(ip);
        if (isBlank(minecraftUuid) || isBlank(normIp)) return;
        if (!hasDb()) return;

        withConn(null, null, c -> {
            int updated;
            try (PreparedStatement ps = c.prepareStatement("UPDATE player_ips SET last_seen_ms=?, cnt=cnt+1 WHERE minecraft_uuid=? AND ip=?")) {
                ps.setLong(1, now);
                ps.setString(2, minecraftUuid);
                ps.setString(3, normIp);
                updated = ps.executeUpdate();
            }
            if (updated <= 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_ips (minecraft_uuid,ip,first_seen_ms,last_seen_ms,cnt) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, minecraftUuid);
                    ps.setString(2, normIp);
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                    ps.setLong(5, 1L);
                    ps.executeUpdate();
                } catch (Exception ignored) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE player_ips SET first_seen_ms=LEAST(first_seen_ms, ?), last_seen_ms=GREATEST(last_seen_ms, ?), cnt=cnt+1 WHERE minecraft_uuid=? AND ip=?")) {
                        ps.setLong(1, now);
                        ps.setLong(2, now);
                        ps.setString(3, minecraftUuid);
                        ps.setString(4, normIp);
                        ps.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    private void importLegacyIpsFromDumpIfNeeded() {
        if (!hasDb()) return;

        File dumpFile = new File(plugin.getDataFolder(), "dump.sql");
        if (!dumpFile.isFile()) return;

        String sig = dumpFile.lastModified() + ":" + dumpFile.length();
        String oldSig = nz(readMeta("legacy_dump_import_sig")).trim();
        if (sig.equals(oldSig)) return;

        try {
            String text = Files.readString(dumpFile.toPath(), StandardCharsets.UTF_8);
            if (isBlank(text)) {
                writeMeta("legacy_dump_import_sig", sig, System.currentTimeMillis());
                return;
            }

            List<AccountEntry> accounts = loadAccountsIfChanged();
            Map<String, String> userCache = loadUserCacheIfChanged();

            Map<String, String> nickToUuid = new HashMap<>();
            for (AccountEntry ae : accounts) {
                String did = ae.discordId();
                String uuid = ae.minecraftUuid();
                String name = resolveMinecraftName(did, uuid, userCache);
                if (!isBlank(name) && !isBlank(uuid)) nickToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
            }

            int imported = 0;
            Matcher matcher = AUTH_INSERT_PATTERN.matcher(text);
            while (matcher.find()) {
                String colsPart = matcher.group(1);
                String valsPart = matcher.group(2);

                List<String> cols = splitSqlCsv(colsPart);
                List<String> vals = splitSqlCsv(valsPart);
                if (cols.isEmpty() || vals.isEmpty() || cols.size() != vals.size()) continue;

                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    row.put(cols.get(i).trim().toUpperCase(Locale.ROOT), sqlUnquote(vals.get(i)));
                }

                String minecraftUuid = normalizeImportedDumpUuid(firstNonBlank(
                        row.get("UUID"),
                        row.get("PREMIUMUUID")
                ));

                if (isBlank(minecraftUuid)) {
                    String nickname = firstNonBlank(row.get("NICKNAME"), row.get("LOWERCASENICKNAME"));
                    if (!isBlank(nickname)) minecraftUuid = nickToUuid.get(nickname.toLowerCase(Locale.ROOT));
                }

                if (isBlank(minecraftUuid)) continue;

                String regIp = normalizeIp(row.get("IP"));
                String loginIp = normalizeIp(row.get("LOGINIP"));

                long regDate = parseLongSafe(row.get("REGDATE"));
                long loginDate = parseLongSafe(row.get("LOGINDATE"));
                long issuedTime = parseLongSafe(row.get("ISSUEDTIME"));

                long baseFirst = firstPositive(regDate, issuedTime, loginDate, System.currentTimeMillis());
                long baseLast = firstPositive(loginDate, regDate, issuedTime, baseFirst);

                if (!isBlank(regIp)) {
                    upsertImportedIp(minecraftUuid, regIp, baseFirst, Math.max(baseFirst, baseLast), 1L);
                    imported++;
                }

                if (!isBlank(loginIp)) {
                    if (loginIp.equals(regIp)) {
                        upsertImportedIp(minecraftUuid, loginIp, baseFirst, Math.max(baseFirst, baseLast), 2L);
                    } else {
                        long first = firstPositive(loginDate, regDate, issuedTime, System.currentTimeMillis());
                        long last = firstPositive(loginDate, regDate, issuedTime, first);
                        upsertImportedIp(minecraftUuid, loginIp, first, Math.max(first, last), 1L);
                    }
                    imported++;
                }
            }

            long now = System.currentTimeMillis();
            writeMeta("legacy_dump_import_sig", sig, now);
            writeMeta("legacy_dump_import_done", "1", now);

            plugin.getLogManager().info("Legacy IP import finished, processed entries: " + imported);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to import dump.sql IPs: " + e.getMessage());
        }
    }

    private void upsertImportedIp(String minecraftUuid, String ip, long firstSeenMs, long lastSeenMs, long count) {
        String normIp = normalizeIp(ip);
        if (!hasDb() || isBlank(minecraftUuid) || isBlank(normIp)) return;

        long first = Math.max(0L, firstSeenMs);
        long last = Math.max(first, lastSeenMs);
        long cnt = Math.max(1L, count);

        withConn(null, null, c -> {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player_ips " +
                            "SET first_seen_ms=CASE WHEN first_seen_ms<=0 THEN ? ELSE LEAST(first_seen_ms, ?) END, " +
                            "    last_seen_ms=GREATEST(last_seen_ms, ?), " +
                            "    cnt=GREATEST(cnt, ?) " +
                            "WHERE minecraft_uuid=? AND ip=?")) {
                ps.setLong(1, first);
                ps.setLong(2, first);
                ps.setLong(3, last);
                ps.setLong(4, cnt);
                ps.setString(5, minecraftUuid);
                ps.setString(6, normIp);
                updated = ps.executeUpdate();
            }

            if (updated <= 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO player_ips (minecraft_uuid,ip,first_seen_ms,last_seen_ms,cnt) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, minecraftUuid);
                    ps.setString(2, normIp);
                    ps.setLong(3, first);
                    ps.setLong(4, last);
                    ps.setLong(5, cnt);
                    ps.executeUpdate();
                } catch (Exception ignored) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE player_ips " +
                                    "SET first_seen_ms=CASE WHEN first_seen_ms<=0 THEN ? ELSE LEAST(first_seen_ms, ?) END, " +
                                    "    last_seen_ms=GREATEST(last_seen_ms, ?), " +
                                    "    cnt=GREATEST(cnt, ?) " +
                                    "WHERE minecraft_uuid=? AND ip=?")) {
                        ps.setLong(1, first);
                        ps.setLong(2, first);
                        ps.setLong(3, last);
                        ps.setLong(4, cnt);
                        ps.setString(5, minecraftUuid);
                        ps.setString(6, normIp);
                        ps.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    private String resolveFallbackMinecraftNameByUuid(String minecraftUuid) {
        if (isBlank(minecraftUuid)) return "";

        Map<String, String> userCache = loadUserCacheIfChanged();

        String direct = userCache.get(minecraftUuid);
        if (!isBlank(direct)) return direct;

        List<AccountEntry> accounts = loadAccountsIfChanged();
        for (AccountEntry entry : accounts) {
            if (entry == null || isBlank(entry.minecraftUuid())) continue;
            if (minecraftUuid.equalsIgnoreCase(entry.minecraftUuid())) {
                return resolveMinecraftName(entry.discordId(), entry.minecraftUuid(), userCache);
            }
        }

        return "";
    }

    private String normalizeImportedDumpUuid(String raw) {
        String t = nz(raw).trim();
        if (t.isEmpty()) return null;

        try {
            return UUID.fromString(t).toString();
        } catch (Exception ignored) {}

        String hex = t.replace("-", "").trim();
        if (hex.length() != 32) return null;

        for (int i = 0; i < hex.length(); i++) {
            char ch = Character.toLowerCase(hex.charAt(i));
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!ok) return null;
        }

        String dashed =
                hex.substring(0, 8) + "-" +
                        hex.substring(8, 12) + "-" +
                        hex.substring(12, 16) + "-" +
                        hex.substring(16, 20) + "-" +
                        hex.substring(20, 32);

        try {
            return UUID.fromString(dashed).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> splitSqlCsv(String src) {
        ArrayList<String> out = new ArrayList<>();
        if (src == null) return out;

        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);

            if (ch == '\'') {
                cur.append(ch);
                if (inQuote) {
                    if (i + 1 < src.length() && src.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    inQuote = true;
                }
                continue;
            }

            if (ch == ',' && !inQuote) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }

            cur.append(ch);
        }

        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private String sqlUnquote(String raw) {
        String t = nz(raw).trim();
        if (t.equalsIgnoreCase("NULL")) return null;
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            t = t.substring(1, t.length() - 1).replace("''", "'");
        }
        return t;
    }

    private long parseLongSafe(String s) {
        if (isBlank(s)) return 0L;
        try { return Long.parseLong(s.trim()); } catch (Exception ignored) { return 0L; }
    }

    private long firstPositive(long... vals) {
        if (vals == null) return 0L;
        for (long v : vals) if (v > 0L) return v;
        return 0L;
    }

    private String normalizePlayersCsv(String csv) {
        if (isBlank(csv)) return null;
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = nz(part).trim();
            if (!t.isEmpty()) uniq.add(t);
        }
        if (uniq.isEmpty()) return null;
        return String.join(", ", uniq);
    }

    private String normalizeIp(String ip) {
        if (isBlank(ip)) return null;
        String s = ip.trim();

        if (s.startsWith("/")) s = s.substring(1).trim();

        int colonCount = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ':') colonCount++;

        if (colonCount == 1 && s.contains(".") && !s.startsWith("[")) {
            int idx = s.lastIndexOf(':');
            if (idx > 0) s = s.substring(0, idx).trim();
        }

        if (s.startsWith("[") && s.contains("]")) {
            int end = s.indexOf(']');
            if (end > 1) s = s.substring(1, end).trim();
        }

        try {
            InetSocketAddress unresolved = InetSocketAddress.createUnresolved(s, 0);
            String host = unresolved.getHostString();
            if (!isBlank(host)) s = host.trim();
        } catch (Exception ignored) {}

        if (IpRule.ipv4ToInt(s) == -1) return null;
        return s;
    }

    private TimeState ensureTimeStateLoaded(String minecraftUuid, long now) {
        TimeState cached = timeByUuid.get(minecraftUuid);
        if (cached != null) return cached;

        TimeState loaded = hasDb() ? readTimeStateFromDb(minecraftUuid) : null;
        if (loaded == null) loaded = new TimeState();

        synchronized (loaded) {
            if (loaded.dayKey == 0) loaded.dayKey = dayKey(now);
            if (loaded.weekKey == 0) loaded.weekKey = weekKey(now);
            if (loaded.monthKey == 0) loaded.monthKey = monthKey(now);
            if (loaded.updatedAtMs == 0L) loaded.updatedAtMs = now;
        }

        TimeState prev = timeByUuid.putIfAbsent(minecraftUuid, loaded);
        return prev != null ? prev : loaded;
    }

    private TimeState readTimeStateFromDb(String minecraftUuid) {
        if (!hasDb() || isBlank(minecraftUuid)) return null;
        return withConn(null, null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_join_ms,last_seen_ms,active_ms_total,afk_ms_total,active_ms_today,active_ms_week,active_ms_month,day_key,week_key,month_key,updated_at FROM player_time WHERE minecraft_uuid=? LIMIT 1")) {
                ps.setString(1, minecraftUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        TimeState st = new TimeState();
                        return st;
                    }
                    TimeState st = new TimeState();
                    st.lastJoinMs = Math.max(0L, rs.getLong(1));
                    st.lastSeenMs = Math.max(0L, rs.getLong(2));
                    st.activeTotalMs = Math.max(0L, rs.getLong(3));
                    st.afkTotalMs = Math.max(0L, rs.getLong(4));
                    st.activeTodayMs = Math.max(0L, rs.getLong(5));
                    st.activeWeekMs = Math.max(0L, rs.getLong(6));
                    st.activeMonthMs = Math.max(0L, rs.getLong(7));
                    st.dayKey = Math.max(0, rs.getInt(8));
                    st.weekKey = Math.max(0, rs.getInt(9));
                    st.monthKey = Math.max(0, rs.getInt(10));
                    st.updatedAtMs = Math.max(0L, rs.getLong(11));
                    return st;
                }
            }
        });
    }

    private void upsertTimeStateToDb(String minecraftUuid, TimeState st, long now) throws Exception {
        if (!hasDb() || isBlank(minecraftUuid) || st == null) return;
        Connection c = null;
        try {
            c = pool.borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO player_time (minecraft_uuid,last_join_ms,last_seen_ms,active_ms_total,afk_ms_total,active_ms_today,active_ms_week,active_ms_month,day_key,week_key,month_key,updated_at) " +
                            "KEY(minecraft_uuid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            )) {
                long lj, ls, at, afk, td, wk, mo;
                int dk, wkKey, mk;
                long upd;
                synchronized (st) {
                    lj = st.lastJoinMs;
                    ls = st.lastSeenMs;
                    at = st.activeTotalMs;
                    afk = st.afkTotalMs;
                    td = st.activeTodayMs;
                    wk = st.activeWeekMs;
                    mo = st.activeMonthMs;
                    dk = st.dayKey;
                    wkKey = st.weekKey;
                    mk = st.monthKey;
                    upd = st.updatedAtMs;
                }
                ps.setString(1, minecraftUuid);
                ps.setLong(2, Math.max(0L, lj));
                ps.setLong(3, Math.max(0L, ls));
                ps.setLong(4, Math.max(0L, at));
                ps.setLong(5, Math.max(0L, afk));
                ps.setLong(6, Math.max(0L, td));
                ps.setLong(7, Math.max(0L, wk));
                ps.setLong(8, Math.max(0L, mo));
                ps.setInt(9, Math.max(0, dk));
                ps.setInt(10, Math.max(0, wkKey));
                ps.setInt(11, Math.max(0, mk));
                ps.setLong(12, Math.max(0L, upd == 0L ? now : upd));
                ps.executeUpdate();
            }
        } finally {
            pool.release(c);
        }
    }

    private void trackOnlineTimeTick() {
        long now = System.currentTimeMillis();
        int dkNow = dayKey(now);
        int wkNow = weekKey(now);
        int mkNow = monthKey(now);

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String u = p.getUniqueId().toString();
            long prev = onlineTickMs.getOrDefault(u, now);
            long delta = now - prev;
            if (delta < 0L) delta = 0L;
            if (delta > 60000L) delta = 60000L;
            onlineTickMs.put(u, now);

            TimeState st = ensureTimeStateLoaded(u, now);

            boolean afk = AFKManager.isPlayerAfk(p);

            synchronized (st) {
                if (st.dayKey != dkNow) { st.dayKey = dkNow; st.activeTodayMs = 0L; }
                if (st.weekKey != wkNow) { st.weekKey = wkNow; st.activeWeekMs = 0L; }
                if (st.monthKey != mkNow) { st.monthKey = mkNow; st.activeMonthMs = 0L; }

                if (afk) st.afkTotalMs += delta;
                else {
                    st.activeTotalMs += delta;
                    st.activeTodayMs += delta;
                    st.activeWeekMs += delta;
                    st.activeMonthMs += delta;
                }
                st.updatedAtMs = now;
            }

            dirtyTimes.add(u);
        }

        updateSeasonPeakOnlineIfNeeded(safeOnlineCount());
    }

    private void flushDirtyTimeRows() {
        if (!hasDb()) return;
        if (!timeFlushRunning.compareAndSet(false, true)) return;
        try {
            if (dirtyTimes.isEmpty()) return;

            ArrayList<String> ids = new ArrayList<>(dirtyTimes);
            dirtyTimes.removeAll(ids);

            Connection c = null;
            try {
                c = pool.borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO player_time (minecraft_uuid,last_join_ms,last_seen_ms,active_ms_total,afk_ms_total,active_ms_today,active_ms_week,active_ms_month,day_key,week_key,month_key,updated_at) " +
                                "KEY(minecraft_uuid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                )) {
                    int batch = 0;
                    long now = System.currentTimeMillis();
                    for (String u : ids) {
                        TimeState st = timeByUuid.get(u);
                        if (st == null) continue;

                        long lj, ls, at, afk, td, wk, mo;
                        int dk, wkKey, mk;
                        long upd;

                        synchronized (st) {
                            lj = st.lastJoinMs;
                            ls = st.lastSeenMs;
                            at = st.activeTotalMs;
                            afk = st.afkTotalMs;
                            td = st.activeTodayMs;
                            wk = st.activeWeekMs;
                            mo = st.activeMonthMs;
                            dk = st.dayKey;
                            wkKey = st.weekKey;
                            mk = st.monthKey;
                            upd = st.updatedAtMs;
                        }

                        ps.setString(1, u);
                        ps.setLong(2, Math.max(0L, lj));
                        ps.setLong(3, Math.max(0L, ls));
                        ps.setLong(4, Math.max(0L, at));
                        ps.setLong(5, Math.max(0L, afk));
                        ps.setLong(6, Math.max(0L, td));
                        ps.setLong(7, Math.max(0L, wk));
                        ps.setLong(8, Math.max(0L, mo));
                        ps.setInt(9, Math.max(0, dk));
                        ps.setInt(10, Math.max(0, wkKey));
                        ps.setInt(11, Math.max(0, mk));
                        ps.setLong(12, Math.max(0L, upd == 0L ? now : upd));
                        ps.addBatch();

                        if (++batch >= 500) { ps.executeBatch(); batch = 0; }
                    }
                    if (batch > 0) ps.executeBatch();
                }
            } finally {
                pool.release(c);
            }

        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to flush time rows: " + e.getMessage());
        } finally {
            timeFlushRunning.set(false);
        }
    }

    private ExtraState ensureExtraStateLoaded(String minecraftUuid) {
        ExtraState cached = extraByUuid.get(minecraftUuid);
        if (cached != null) return cached;

        ExtraState loaded = hasDb() ? readExtraStateFromDb(minecraftUuid) : null;
        if (loaded == null) loaded = new ExtraState();

        ExtraState prev = extraByUuid.putIfAbsent(minecraftUuid, loaded);
        return prev != null ? prev : loaded;
    }

    private ExtraState readExtraStateFromDb(String minecraftUuid) {
        if (!hasDb() || isBlank(minecraftUuid)) return null;
        return withConn(null, null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT slaps_sent,slaps_received,updated_at FROM player_extra WHERE minecraft_uuid=? LIMIT 1")) {
                ps.setString(1, minecraftUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    ExtraState st = new ExtraState();
                    if (!rs.next()) return st;
                    st.slapsSent = Math.max(0L, rs.getLong(1));
                    st.slapsReceived = Math.max(0L, rs.getLong(2));
                    st.updatedAtMs = Math.max(0L, rs.getLong(3));
                    return st;
                }
            }
        });
    }

    public void addSlapSent(UUID uuid, long delta) {
        if (uuid == null) return;
        if (delta <= 0) return;
        String u = uuid.toString();
        ExtraState st = ensureExtraStateLoaded(u);
        long now = System.currentTimeMillis();
        synchronized (st) {
            st.slapsSent = Math.max(0L, st.slapsSent + delta);
            st.updatedAtMs = now;
        }
        dirtyExtra.add(u);
    }

    public void addSlapReceived(UUID uuid, long delta) {
        if (uuid == null) return;
        if (delta <= 0) return;
        String u = uuid.toString();
        ExtraState st = ensureExtraStateLoaded(u);
        long now = System.currentTimeMillis();
        synchronized (st) {
            st.slapsReceived = Math.max(0L, st.slapsReceived + delta);
            st.updatedAtMs = now;
        }
        dirtyExtra.add(u);
    }

    private void flushDirtyExtraRows() {
        if (!hasDb()) return;
        if (!extraFlushRunning.compareAndSet(false, true)) return;
        try {
            if (dirtyExtra.isEmpty()) return;

            ArrayList<String> ids = new ArrayList<>(dirtyExtra);
            dirtyExtra.removeAll(ids);

            Connection c = null;
            try {
                c = pool.borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "MERGE INTO player_extra (minecraft_uuid,slaps_sent,slaps_received,updated_at) KEY(minecraft_uuid) VALUES (?,?,?,?)"
                )) {
                    int batch = 0;
                    long now = System.currentTimeMillis();
                    for (String u : ids) {
                        ExtraState st = extraByUuid.get(u);
                        if (st == null) continue;

                        long ss, sr, upd;
                        synchronized (st) {
                            ss = st.slapsSent;
                            sr = st.slapsReceived;
                            upd = st.updatedAtMs;
                        }

                        ps.setString(1, u);
                        ps.setLong(2, Math.max(0L, ss));
                        ps.setLong(3, Math.max(0L, sr));
                        ps.setLong(4, Math.max(0L, upd == 0L ? now : upd));
                        ps.addBatch();

                        if (++batch >= 500) { ps.executeBatch(); batch = 0; }
                    }
                    if (batch > 0) ps.executeBatch();
                }
            } finally {
                pool.release(c);
            }

        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to flush extra rows: " + e.getMessage());
        } finally {
            extraFlushRunning.set(false);
        }
    }

    private void indexLoadedChunksFromWorlds() {
        try {
            HashMap<String, Set<Long>> fresh = new HashMap<>();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                HashSet<Long> set = new HashSet<>();
                for (Chunk chunk : world.getLoadedChunks()) set.add(packChunk(chunk.getX(), chunk.getZ()));
                fresh.put(world.getName(), ConcurrentHashMap.newKeySet());
                fresh.get(world.getName()).addAll(set);
            }

            loadedChunksByWorld.clear();
            loadedChunksByWorld.putAll(fresh);
        } catch (Exception ignored) {
        }
    }

    private void updateWorldsSnapshot() {
        try {
            JsonArray arr = new JsonArray();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("name", world.getName());
                o.addProperty("environment", String.valueOf(world.getEnvironment()));
                o.addProperty("time", world.getTime());
                o.addProperty("is_storming", world.hasStorm());
                o.addProperty("is_thundering", world.isThundering());

                Set<Long> loaded = loadedChunksByWorld.get(world.getName());
                o.addProperty("loaded_chunks", loaded == null ? 0 : loaded.size());

                arr.add(o);
            }
            worldsJsonCache = GSON.toJson(arr);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update worlds snapshot: " + e.getMessage());
        }
    }

    private void updateFoliaRegionsSnapshot() {
        try {
            Map<String, Set<Long>> snapshot = snapshotLoadedChunks();
            if (snapshot.isEmpty()) {
                foliaRegionsJsonCache = "[]";
                return;
            }

            ArrayList<JsonObject> regions = new ArrayList<>();

            for (Map.Entry<String, Set<Long>> entry : snapshot.entrySet()) {
                String worldName = entry.getKey();
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                HashSet<Long> remaining = new HashSet<>(entry.getValue());
                while (!remaining.isEmpty()) {
                    long seed = remaining.iterator().next();
                    RegionSnapshot rs = collectRegionSnapshot(world, unpackChunkX(seed), unpackChunkZ(seed), remaining);
                    if (rs == null) {
                        remaining.remove(seed);
                        continue;
                    }

                    remaining.removeAll(rs.chunkKeys());
                    regions.add(toJson(rs));
                }
            }

            regions.sort(Comparator
                    .comparing((JsonObject o) -> nz(getAsStringOrEmpty(o, "world")))
                    .thenComparingInt(o -> getAsIntOrZero(o, "anchor_chunk_x"))
                    .thenComparingInt(o -> getAsIntOrZero(o, "anchor_chunk_z")));

            JsonArray out = new JsonArray();
            for (JsonObject o : regions) out.add(o);
            foliaRegionsJsonCache = GSON.toJson(out);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update Folia regions snapshot: " + e.getMessage());
        }
    }

    private Map<String, Set<Long>> snapshotLoadedChunks() {
        HashMap<String, Set<Long>> out = new HashMap<>();
        for (Map.Entry<String, Set<Long>> e : loadedChunksByWorld.entrySet()) {
            Set<Long> src = e.getValue();
            if (src == null || src.isEmpty()) continue;
            out.put(e.getKey(), new HashSet<>(src));
        }
        return out;
    }

    private RegionSnapshot collectRegionSnapshot(World world, int seedChunkX, int seedChunkZ, Set<Long> knownChunks) {
        if (world == null || knownChunks == null || knownChunks.isEmpty()) return null;

        CountDownLatch latch = new CountDownLatch(1);
        RegionSnapshot[] holder = new RegionSnapshot[1];

        try {
            Location loc = new Location(world, (seedChunkX << 4) + 8.0, world.getMinHeight(), (seedChunkZ << 4) + 8.0);
            plugin.getFoliaLib().getScheduler().runAtLocation(loc, task -> {
                try {
                    holder[0] = buildRegionSnapshot(world, seedChunkX, seedChunkZ, knownChunks);
                } catch (Exception ignored) {
                    holder[0] = null;
                } finally {
                    latch.countDown();
                }
            });

            if (!latch.await(10L, TimeUnit.SECONDS)) return null;
            return holder[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    private RegionSnapshot buildRegionSnapshot(World world, int seedChunkX, int seedChunkZ, Set<Long> knownChunks) {
        if (world == null || knownChunks == null || knownChunks.isEmpty()) return null;
        if (!knownChunks.contains(packChunk(seedChunkX, seedChunkZ))) return null;
        if (!Bukkit.isOwnedByCurrentRegion(world, seedChunkX, seedChunkZ)) return null;

        HashSet<Long> visited = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{seedChunkX, seedChunkZ});

        int minX = seedChunkX, maxX = seedChunkX, minZ = seedChunkZ, maxZ = seedChunkZ;
        int entityCount = 0;
        int playerCount = 0;

        while (!queue.isEmpty()) {
            long[] pos = queue.poll();
            int x = (int) pos[0];
            int z = (int) pos[1];
            long key = packChunk(x, z);

            if (!knownChunks.contains(key) || !visited.add(key)) continue;
            if (!Bukkit.isOwnedByCurrentRegion(world, x, z)) continue;

            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;

            if (world.isChunkLoaded(x, z)) {
                try {
                    Chunk chunk = world.getChunkAt(x, z);
                    for (Entity entity : chunk.getEntities()) {
                        if (entity == null) continue;
                        entityCount++;
                        if (entity instanceof Player) playerCount++;
                    }
                } catch (Exception ignored) {
                }
            }

            queue.add(new long[]{x + 1, z});
            queue.add(new long[]{x - 1, z});
            queue.add(new long[]{x, z + 1});
            queue.add(new long[]{x, z - 1});
        }

        if (visited.isEmpty()) return null;

        double[] tps = regionTpsBridge.get(world, seedChunkX, seedChunkZ);
        double[] mspt = regionMsptBridge.get(world, seedChunkX, seedChunkZ);

        return new RegionSnapshot(
                world.getName(),
                seedChunkX,
                seedChunkZ,
                minX,
                maxX,
                minZ,
                maxZ,
                visited.size(),
                playerCount,
                entityCount,
                visited,
                tps,
                mspt
        );
    }

    private JsonObject toJson(RegionSnapshot rs) {
        JsonObject o = new JsonObject();
        o.addProperty("world", rs.worldName());
        o.addProperty("anchor_chunk_x", rs.anchorChunkX());
        o.addProperty("anchor_chunk_z", rs.anchorChunkZ());
        o.addProperty("min_chunk_x", rs.minChunkX());
        o.addProperty("max_chunk_x", rs.maxChunkX());
        o.addProperty("min_chunk_z", rs.minChunkZ());
        o.addProperty("max_chunk_z", rs.maxChunkZ());
        o.addProperty("chunk_count", rs.chunkCount());
        o.addProperty("players", rs.playerCount());
        o.addProperty("entities", rs.entityCount());
        o.add("tps", toWindowStats(rs.tps()));
        o.add("mspt", toWindowStats(rs.mspt()));
        return o;
    }

    private JsonObject toWindowStats(double[] arr) {
        JsonObject o = new JsonObject();
        if (arr == null || arr.length == 0) {
            o.add("5s", JsonNull.INSTANCE);
            o.add("15s", JsonNull.INSTANCE);
            o.add("1m", JsonNull.INSTANCE);
            o.add("5m", JsonNull.INSTANCE);
            o.add("15m", JsonNull.INSTANCE);
            return o;
        }
        o.addProperty("5s", arr.length > 0 ? round2(arr[0]) : 0.0);
        o.addProperty("15s", arr.length > 1 ? round2(arr[1]) : 0.0);
        o.addProperty("1m", arr.length > 2 ? round2(arr[2]) : 0.0);
        o.addProperty("5m", arr.length > 3 ? round2(arr[3]) : 0.0);
        o.addProperty("15m", arr.length > 4 ? round2(arr[4]) : 0.0);
        return o;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String getAsStringOrEmpty(JsonObject o, String key) {
        try { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private int getAsIntOrZero(JsonObject o, String key) {
        try { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0; }
        catch (Exception ignored) { return 0; }
    }

    private long packChunk(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    private int unpackChunkZ(long packed) {
        return (int) packed;
    }

    private JsonObject obj(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonObject()) return null;
        return root.getAsJsonObject(key);
    }

    private long getLong(JsonObject obj, String... keys) {
        if (obj == null) return 0L;
        for (String k : keys) {
            if (k == null) continue;
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                try { return obj.get(k).getAsLong(); } catch (Exception ignored) {}
            }
        }
        return 0L;
    }

    private long asLong(JsonElement el) {
        try { return el == null || el.isJsonNull() ? 0L : el.getAsLong(); }
        catch (Exception ignored) { return 0L; }
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

    private long sumBlockValues(JsonObject usedObj) {
        if (usedObj == null) return 0L;
        long sum = 0L;
        for (Map.Entry<String, JsonElement> e : usedObj.entrySet()) {
            long v = asLong(e.getValue());
            if (v <= 0) continue;

            Material m = materialFromStatKey(e.getKey());
            if (m != null && m.isBlock()) sum += v;
        }
        return sum;
    }

    private Material materialFromStatKey(String key) {
        if (isBlank(key)) return null;
        String k = stripNamespace(key);
        if (k.isEmpty()) return null;
        String up = k.toUpperCase(Locale.ROOT);
        try { return Material.valueOf(up); } catch (Exception ignored) {}
        try { return Material.matchMaterial(up); } catch (Exception ignored) {}
        return null;
    }

    private String stripNamespace(String key) {
        if (key == null) return "";
        int idx = key.indexOf(':');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private double ticksToHours(long ticks) { return ticks / 20.0 / 3600.0; }
    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private long cmToBlocks(long cm) {
        if (cm <= 0) return 0L;
        return cm / 100L;
    }

    private JsonObject hmFromMs(long ms) {
        long m = Math.max(0L, ms) / 60000L;
        long h = m / 60L;
        long mm = m % 60L;
        JsonObject o = new JsonObject();
        o.addProperty("hours", h);
        o.addProperty("minutes", mm);
        return o;
    }

    private JsonObject hmFromTicks(long ticks) {
        long t = Math.max(0L, ticks);
        long sec = t / 20L;
        long m = sec / 60L;
        long h = m / 60L;
        long mm = m % 60L;
        JsonObject o = new JsonObject();
        o.addProperty("hours", h);
        o.addProperty("minutes", mm);
        return o;
    }

    private int dayKey(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private int monthKey(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
        return d.getYear() * 100 + d.getMonthValue();
    }

    private int weekKey(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
        WeekFields wf = WeekFields.ISO;
        int wy = d.get(wf.weekBasedYear());
        int w = d.get(wf.weekOfWeekBasedYear());
        return wy * 100 + w;
    }

    private void handleJoin(Player p) {
        if (p == null) return;
        String u = p.getUniqueId().toString();
        long now = System.currentTimeMillis();
        onlineTickMs.put(u, now);

        TimeState st = ensureTimeStateLoaded(u, now);
        synchronized (st) {
            st.lastJoinMs = now;
            st.updatedAtMs = now;
            if (st.dayKey == 0) st.dayKey = dayKey(now);
            if (st.weekKey == 0) st.weekKey = weekKey(now);
            if (st.monthKey == 0) st.monthKey = monthKey(now);
        }
        dirtyTimes.add(u);

        if (hasDb()) {
            plugin.getFoliaLib().getScheduler().runAsync(t -> markSeasonJoin(u, now));
        } else {
            pendingSeasonJoins.add(u);
        }

        updateSeasonPeakOnlineIfNeeded(safeOnlineCount());
    }

    private void handleQuit(Player p) {
        if (p == null) return;
        String u = p.getUniqueId().toString();
        long now = System.currentTimeMillis();

        Long prev = onlineTickMs.get(u);
        if (prev != null) {
            long delta = now - prev;
            if (delta < 0L) delta = 0L;
            if (delta > 60000L) delta = 60000L;
            TimeState st = ensureTimeStateLoaded(u, now);

            boolean afk = AFKManager.isPlayerAfk(p);

            synchronized (st) {
                if (afk) st.afkTotalMs += delta;
                else {
                    st.activeTotalMs += delta;
                    st.activeTodayMs += delta;
                    st.activeWeekMs += delta;
                    st.activeMonthMs += delta;
                }
            }
        }

        TimeState st = ensureTimeStateLoaded(u, now);
        synchronized (st) {
            st.lastSeenMs = now;
            st.updatedAtMs = now;
        }
        dirtyTimes.add(u);
        onlineTickMs.remove(u);
    }

    private static String jsonError(String msg) { JsonObject o = new JsonObject(); o.addProperty("error", msg); return GSON.toJson(o); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz(String s, String def) { return isBlank(s) ? def : s; }
    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) if (!isBlank(v)) return v;
        return "";
    }

    private record AccountEntry(String discordId, String minecraftUuid) {}
    private record SkinLinks(String skinUrl, String headUrl) {}
    private record PlayerUpsertRow(
            String discordId, String minecraftUuid, String minecraftName, String minecraftNameLc,
            String skinUrl, String headUrl, boolean isOnline, boolean active, double playTimeHours,
            String summaryJson, String fullJson, long summaryUpdatedAt, long fullUpdatedAt, long statsMtime
    ) {}
    private record StatsFileRef(Path path, long mtime) {}
    private record StatsCacheEntry(long mtime, StatsSnapshot snapshot) {}
    private record StatsSnapshot(
            long mtime,
            long playTimeTicks,
            long deaths,
            long playerKills,
            long mobKills,
            long walkCm,
            long sprintCm,
            long crouchCm,
            long climbCm,
            long swimCm,
            long flyCm,
            long jumps,
            long blocksBroken,
            long blocksPlaced,
            long craftedTotal
    ) {
        static StatsSnapshot empty(long mtime) {
            return new StatsSnapshot(
                    mtime,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L
            );
        }
    }

    private record AdvFileRef(Path path, long mtime) {}
    private record AdvCacheEntry(long mtime, AdvSnapshot snapshot) {}
    private record AdvSnapshot(long mtime, long completed) {}

    private record TicketInfo(String moderatorId, String ticketId, JsonObject ticket, String timeKey) {}
    private record FlexData(long bank, String color, List<String> subscription, String command) {
        static FlexData empty() { return new FlexData(0L, "", List.of(), ""); }
    }

    private record IpEntry(String ip, long firstSeenMs, long lastSeenMs, long count) {}

    private record RegionSnapshot(
            String worldName,
            int anchorChunkX,
            int anchorChunkZ,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            int chunkCount,
            int playerCount,
            int entityCount,
            Set<Long> chunkKeys,
            double[] tps,
            double[] mspt
    ) {}

    private static final class TimeState {
        long lastJoinMs;
        long lastSeenMs;
        long activeTotalMs;
        long afkTotalMs;
        long activeTodayMs;
        long activeWeekMs;
        long activeMonthMs;
        int dayKey;
        int weekKey;
        int monthKey;
        long updatedAtMs;
    }

    private static final class ExtraState {
        long slapsSent;
        long slapsReceived;
        long updatedAtMs;
    }

    private final class OnlineListener implements Listener {
        @EventHandler public void onLogin(PlayerLoginEvent e) {
            try {
                String u = e.getPlayer() == null ? null : e.getPlayer().getUniqueId().toString();
                String ip = e.getAddress() == null ? null : e.getAddress().getHostAddress();
                long now = System.currentTimeMillis();
                if (!isBlank(u) && !isBlank(ip) && hasDb()) plugin.getFoliaLib().getScheduler().runAsync(t -> recordPlayerIp(u, ip, now));
            } catch (Exception ignored) {}
        }

        @EventHandler public void onJoin(PlayerJoinEvent e) {
            try { handleOnlineChange(e.getPlayer().getUniqueId().toString(), true); } catch (Exception ignored) {}
            try { handleJoin(e.getPlayer()); } catch (Exception ignored) {}
        }

        @EventHandler public void onQuit(PlayerQuitEvent e) {
            try { handleQuit(e.getPlayer()); } catch (Exception ignored) {}
            try { handleOnlineChange(e.getPlayer().getUniqueId().toString(), false); } catch (Exception ignored) {}
        }

        @EventHandler public void onChunkLoad(ChunkLoadEvent e) {
            try {
                Chunk chunk = e.getChunk();
                if (chunk == null || chunk.getWorld() == null) return;
                loadedChunksByWorld.computeIfAbsent(chunk.getWorld().getName(), k -> ConcurrentHashMap.newKeySet())
                        .add(packChunk(chunk.getX(), chunk.getZ()));
            } catch (Exception ignored) {}
        }

        @EventHandler public void onChunkUnload(ChunkUnloadEvent e) {
            try {
                Chunk chunk = e.getChunk();
                if (chunk == null || chunk.getWorld() == null) return;
                Set<Long> set = loadedChunksByWorld.get(chunk.getWorld().getName());
                if (set != null) set.remove(packChunk(chunk.getX(), chunk.getZ()));
            } catch (Exception ignored) {}
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
                try { bits = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) { return null; }
                bits = Math.max(0, Math.min(32, bits));
                return new IpRule(base, bits, mask(bits));
            }

            if (t.contains("*")) {
                String[] parts = t.split("\\.");
                if (parts.length != 4) return null;
                int bits = 0;
                int[] oct = new int[4];
                for (int i = 0; i < 4; i++) {
                    String p = parts[i].trim();
                    if (p.equals("*")) oct[i] = 0;
                    else {
                        if (!p.chars().allMatch(Character::isDigit)) return null;
                        int v = Integer.parseInt(p);
                        if (v < 0 || v > 255) return null;
                        oct[i] = v;
                        bits += 8;
                    }
                }
                int base = (oct[0] << 24) | (oct[1] << 16) | (oct[2] << 8) | oct[3];
                return new IpRule(base, bits, mask(bits));
            }

            int base = ipv4ToInt(t);
            if (base == -1) return null;

            int bits = 32;
            if (t.endsWith(".0.0.0")) bits = 8;
            else if (t.endsWith(".0.0")) bits = 16;
            else if (t.endsWith(".0")) bits = 24;

            return new IpRule(base, bits, mask(bits));
        }

        boolean matches(int ip) { return (ip & mask) == (network & mask); }

        static int mask(int bits) {
            if (bits <= 0) return 0;
            if (bits >= 32) return -1;
            return (int) (0xFFFFFFFFL << (32 - bits));
        }

        static int ipv4ToInt(String ip) {
            if (ip == null) return -1;
            String[] parts = ip.trim().split("\\.");
            if (parts.length != 4) return -1;

            int[] oct = new int[4];
            for (int i = 0; i < 4; i++) {
                String p = parts[i].trim();
                if (p.isEmpty() || !p.chars().allMatch(Character::isDigit)) return -1;
                int v;
                try { v = Integer.parseInt(p); } catch (Exception ignored) { return -1; }
                if (v < 0 || v > 255) return -1;
                oct[i] = v;
            }
            return (oct[0] << 24) | (oct[1] << 16) | (oct[2] << 8) | oct[3];
        }
    }

    private static final class RegionTpsBridge {
        private final Method staticWorldChunkMethod;
        private final Method serverWorldChunkMethod;
        private final Method staticChunkMethod;
        private final Method serverChunkMethod;

        private RegionTpsBridge() {
            this.staticWorldChunkMethod = findStatic("getRegionTPS", World.class, int.class, int.class);
            this.serverWorldChunkMethod = findServer("getRegionTPS", World.class, int.class, int.class);
            this.staticChunkMethod = findStatic("getRegionTPS", Chunk.class);
            this.serverChunkMethod = findServer("getRegionTPS", Chunk.class);
        }

        private Method findStatic(String name, Class<?>... params) {
            try { return Bukkit.class.getMethod(name, params); } catch (Exception ignored) { return null; }
        }

        private Method findServer(String name, Class<?>... params) {
            try { return Bukkit.getServer().getClass().getMethod(name, params); } catch (Exception ignored) { return null; }
        }

        private double[] get(World world, int chunkX, int chunkZ) {
            if (world == null) return null;

            double[] a = invoke(staticWorldChunkMethod, null, world, chunkX, chunkZ);
            if (a != null) return a;

            a = invoke(serverWorldChunkMethod, Bukkit.getServer(), world, chunkX, chunkZ);
            if (a != null) return a;

            if (world.isChunkLoaded(chunkX, chunkZ)) {
                try {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                    a = invoke(staticChunkMethod, null, chunk);
                    if (a != null) return a;

                    a = invoke(serverChunkMethod, Bukkit.getServer(), chunk);
                    if (a != null) return a;
                } catch (Exception ignored) {
                }
            }

            return null;
        }

        private double[] invoke(Method method, Object target, Object... args) {
            if (method == null) return null;
            try {
                Object out = method.invoke(target, args);

                if (out instanceof double[] d) return d.clone();

                if (out instanceof float[] f) {
                    double[] r = new double[f.length];
                    for (int i = 0; i < f.length; i++) r[i] = f[i];
                    return r;
                }

                if (out instanceof long[] l) {
                    double[] r = new double[l.length];
                    for (int i = 0; i < l.length; i++) r[i] = l[i];
                    return r;
                }

                if (out instanceof int[] v) {
                    double[] r = new double[v.length];
                    for (int i = 0; i < v.length; i++) r[i] = v[i];
                    return r;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static final class RegionMsptBridge {
        private final Method staticWorldChunkMethod;
        private final Method serverWorldChunkMethod;
        private final Method staticChunkMethod;
        private final Method serverChunkMethod;

        private RegionMsptBridge() {
            this.staticWorldChunkMethod = findStatic("getRegionAverageTickTimes", World.class, int.class, int.class);
            this.serverWorldChunkMethod = findServer("getRegionAverageTickTimes", World.class, int.class, int.class);
            this.staticChunkMethod = findStatic("getRegionAverageTickTimes", Chunk.class);
            this.serverChunkMethod = findServer("getRegionAverageTickTimes", Chunk.class);
        }

        private Method findStatic(String name, Class<?>... params) {
            try { return Bukkit.class.getMethod(name, params); } catch (Exception ignored) { return null; }
        }

        private Method findServer(String name, Class<?>... params) {
            try { return Bukkit.getServer().getClass().getMethod(name, params); } catch (Exception ignored) { return null; }
        }

        private double[] get(World world, int chunkX, int chunkZ) {
            if (world == null) return null;

            double[] a = invoke(staticWorldChunkMethod, null, world, chunkX, chunkZ);
            if (a != null) return a;

            a = invoke(serverWorldChunkMethod, Bukkit.getServer(), world, chunkX, chunkZ);
            if (a != null) return a;

            if (world.isChunkLoaded(chunkX, chunkZ)) {
                try {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    a = invoke(staticChunkMethod, null, chunk);
                    if (a != null) return a;

                    a = invoke(serverChunkMethod, Bukkit.getServer(), chunk);
                    if (a != null) return a;
                } catch (Exception ignored) {
                }
            }

            return null;
        }

        private double[] invoke(Method method, Object target, Object... args) {
            if (method == null) return null;
            try {
                Object out = method.invoke(target, args);
                if (out instanceof double[] d) return d.clone();
                if (out instanceof float[] f) {
                    double[] r = new double[f.length];
                    for (int i = 0; i < f.length; i++) r[i] = f[i];
                    return r;
                }
                if (out instanceof long[] l) {
                    double[] r = new double[l.length];
                    for (int i = 0; i < l.length; i++) r[i] = l[i];
                    return r;
                }
                if (out instanceof int[] v) {
                    double[] r = new double[v.length];
                    for (int i = 0; i < v.length; i++) r[i] = v[i];
                    return r;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static final class ConnectionPool {
        private final String url, user, pass;
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
                }
                created.decrementAndGet();
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
                if (!isOk(c)) { closeQuietly(c); created.decrementAndGet(); return; }
                if (!queue.offer(c)) { closeQuietly(c); created.decrementAndGet(); }
            } catch (Exception e) {
                closeQuietly(c);
                created.decrementAndGet();
            }
        }

        private boolean isOk(Connection c) {
            try { return !c.isClosed() && c.isValid(2); }
            catch (Exception e) { return false; }
        }

        private void close() {
            Connection c;
            while ((c = queue.poll()) != null) closeQuietly(c);
        }

        private void closeQuietly(Connection c) { try { c.close(); } catch (Exception ignored) {} }
    }
}