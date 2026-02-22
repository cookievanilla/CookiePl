package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LiteBansBridge {

    private static final long CACHE_MS = 15000L;
    private static final long API_RETRY_MS = 10000L;
    private static final int LIMIT = 50;

    private final CookiePl plugin;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> throttle = new ConcurrentHashMap<>();

    private volatile Api api;
    private volatile long lastApiAttemptMs = 0L;

    public LiteBansBridge(CookiePl plugin) {
        this.plugin = plugin;
    }

    public void warmup() {
        ensureApi(true);
    }

    public void close() {
        cache.clear();
        throttle.clear();
        api = null;
        lastApiAttemptMs = 0L;
    }

    public JsonObject getLiteBansJson(UUID uuid, boolean ipAllowed, String remoteIp) {
        JsonObject out = new JsonObject();
        if (uuid == null) {
            out.add("bans", new JsonArray());
            dbgOnce("lb:nullUuid", 5000L, "[DBG][LB] uuid=null remoteIp=" + safe(remoteIp));
            return out;
        }

        long now = System.currentTimeMillis();
        String key = uuid.toString().toLowerCase(Locale.ROOT);

        CacheEntry ce = cache.get(key);
        if (ce == null || (now - ce.cachedAtMs) > CACHE_MS) {
            CacheEntry fresh = fetch(uuid, remoteIp);
            cache.put(key, fresh);
            ce = fresh;
        }

        JsonArray bans = ipAllowed ? ce.bans : redactIps(ce.bans);
        out.add("bans", bans);

        dbgOnce("lb:req:" + key, 3000L,
                "[DBG][LB] uuid=" + uuid +
                        " remoteIp=" + safe(remoteIp) +
                        " ipAllowed=" + ipAllowed +
                        " source=" + ce.source +
                        " bans=" + (bans == null ? 0 : bans.size()) +
                        " latestIp=" + (ce.latestIp == null || ce.latestIp.isBlank() ? "none" : "present"));

        return out;
    }

    private CacheEntry fetch(UUID uuid, String remoteIp) {
        Api a = ensureApi(true);
        if (a == null) {
            return new CacheEntry(System.currentTimeMillis(), new JsonArray(), null, "no_api");
        }

        String u1 = uuid.toString();
        String u2 = u1.replace("-", "");

        String latestIp = null;
        JsonArray bans = new JsonArray();

        try {
            Object db = a.dbGet.invoke(null);
            if (db == null) {
                dbgOnce("lb:dbnull", 5000L, "[DBG][LB] Database.get() returned null");
                return new CacheEntry(System.currentTimeMillis(), bans, null, "api_db_null");
            }

            latestIp = queryLatestIp(a, db, u1, u2);
            bans = queryBans(a, db, u1, u2, latestIp);

            if (bans.size() == 0) {
                long active = queryActiveCount(a, db, u1, u2);
                dbgOnce("lb:empty:" + u1, 3000L,
                        "[DBG][LB] bans=0 uuid=" + u1 + " activeCount=" + active + " remoteIp=" + safe(remoteIp));
            }

            return new CacheEntry(System.currentTimeMillis(), bans, latestIp, "api");
        } catch (Exception e) {
            dbgOnce("lb:fetcherr:" + u1, 3000L,
                    "[DBG][LB] fetch error uuid=" + u1 + " err=" + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            return new CacheEntry(System.currentTimeMillis(), new JsonArray(), latestIp, "api_error");
        }
    }

    private String queryLatestIp(Api a, Object db, String u1, String u2) {
        String sql = "SELECT ip FROM {history} WHERE uuid=? OR uuid=? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement st = (PreparedStatement) a.prepareStatement.invoke(db, sql)) {
            st.setString(1, u1);
            st.setString(2, u2);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return null;
                String ip = rs.getString(1);
                return ip == null ? null : ip.trim();
            }
        } catch (Exception e) {
            dbgOnce("lb:iperr:" + u1, 3000L,
                    "[DBG][LB] latest ip query error uuid=" + u1 + " err=" + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            return null;
        }
    }

    private long queryActiveCount(Api a, Object db, String u1, String u2) {
        String sql = "SELECT COUNT(*) FROM {bans} WHERE (uuid=? OR uuid=?) AND active=TRUE";
        try (PreparedStatement st = (PreparedStatement) a.prepareStatement.invoke(db, sql)) {
            st.setString(1, u1);
            st.setString(2, u2);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return 0L;
                return rs.getLong(1);
            }
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private JsonArray queryBans(Api a, Object db, String u1, String u2, String latestIp) throws Exception {
        String sql = "SELECT * FROM {bans} WHERE uuid=? OR uuid=? ORDER BY time DESC LIMIT " + LIMIT;
        try (PreparedStatement st = (PreparedStatement) a.prepareStatement.invoke(db, sql)) {
            st.setString(1, u1);
            st.setString(2, u2);

            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Integer> idx = buildIndex(md);

                JsonArray arr = new JsonArray();
                while (rs.next()) {
                    JsonObject ban = new JsonObject();

                    putLong(ban, "id", rs, idx, "ID");
                    putString(ban, "uuid", rs, idx, "UUID");
                    putString(ban, "reason", rs, idx, "REASON");
                    putLong(ban, "time", rs, idx, "TIME");
                    putLong(ban, "until", rs, idx, "UNTIL");
                    putBool(ban, "active", rs, idx, "ACTIVE");

                    putString(ban, "banned_by_uuid", rs, idx, "BANNED_BY_UUID");
                    putString(ban, "banned_by_name", rs, idx, "BANNED_BY_NAME");

                    putString(ban, "removed_by_uuid", rs, idx, "REMOVED_BY_UUID");
                    putString(ban, "removed_by_name", rs, idx, "REMOVED_BY_NAME");
                    putString(ban, "removed_by_reason", rs, idx, "REMOVED_BY_REASON");
                    putString(ban, "removed_by_date", rs, idx, "REMOVED_BY_DATE");

                    putString(ban, "server_scope", rs, idx, "SERVER_SCOPE");
                    putString(ban, "server_origin", rs, idx, "SERVER_ORIGIN");

                    putBool(ban, "silent", rs, idx, "SILENT");
                    putBool(ban, "ipban", rs, idx, "IPBAN");
                    putBool(ban, "ipban_wildcard", rs, idx, "IPBAN_WILDCARD");

                    String ip = getString(rs, idx, "IP");
                    if (ip == null || ip.isBlank()) ip = latestIp;
                    ban.addProperty("ip", ip == null ? "" : ip);

                    arr.add(ban);
                }
                return arr;
            }
        }
    }

    private Api ensureApi(boolean log) {
        Api existing = api;
        if (existing != null) return existing;

        long now = System.currentTimeMillis();
        if ((now - lastApiAttemptMs) < API_RETRY_MS) return null;
        lastApiAttemptMs = now;

        Plugin lb = Bukkit.getPluginManager().getPlugin("LiteBans");
        if (lb == null || !lb.isEnabled()) {
            if (log) dbgOnce("lb:noplugin", 5000L, "[DBG][LB] LiteBans plugin not found/enabled");
            return null;
        }

        try {
            ClassLoader cl = lb.getClass().getClassLoader();
            Class<?> dbClass = Class.forName("litebans.api.Database", true, cl);

            Method get = dbClass.getMethod("get");
            Method ps = dbClass.getMethod("prepareStatement", String.class);

            Api a = new Api(get, ps);
            api = a;

            if (log) dbgOnce("lb:ready", 5000L, "[DBG][LB] LiteBansAPI ready (loaded via LiteBans classloader)");
            return a;
        } catch (Exception e) {
            if (log) dbgOnce("lb:apierr", 5000L,
                    "[DBG][LB] LiteBansAPI load error " + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            return null;
        }
    }

    private JsonArray redactIps(JsonArray in) {
        if (in == null || in.isEmpty()) return new JsonArray();
        JsonArray out = new JsonArray();
        for (JsonElement el : in) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject copy = el.getAsJsonObject().deepCopy();
            copy.addProperty("ip", "not allowed");
            out.add(copy);
        }
        return out;
    }

    private Map<String, Integer> buildIndex(ResultSetMetaData md) throws Exception {
        int n = md.getColumnCount();
        Map<String, Integer> idx = new HashMap<>(Math.max(16, n * 2));
        for (int i = 1; i <= n; i++) {
            String label = md.getColumnLabel(i);
            String name = md.getColumnName(i);
            if (label != null && !label.isBlank()) idx.put(label.toUpperCase(Locale.ROOT), i);
            if (name != null && !name.isBlank()) idx.putIfAbsent(name.toUpperCase(Locale.ROOT), i);
        }
        return idx;
    }

    private String getString(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return null;
        try {
            String s = rs.getString(i);
            return s == null ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long getLong(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return null;
        try {
            long v = rs.getLong(i);
            if (rs.wasNull()) return null;
            return v;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean getBool(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return null;
        try {
            boolean v = rs.getBoolean(i);
            if (rs.wasNull()) return null;
            return v;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putString(JsonObject o, String outKey, ResultSet rs, Map<String, Integer> idx, String col) {
        String v = getString(rs, idx, col);
        if (v == null) o.addProperty(outKey, "");
        else o.addProperty(outKey, v);
    }

    private void putLong(JsonObject o, String outKey, ResultSet rs, Map<String, Integer> idx, String col) {
        Long v = getLong(rs, idx, col);
        if (v == null) o.addProperty(outKey, 0L);
        else o.addProperty(outKey, v);
    }

    private void putBool(JsonObject o, String outKey, ResultSet rs, Map<String, Integer> idx, String col) {
        Boolean v = getBool(rs, idx, col);
        if (v == null) o.addProperty(outKey, false);
        else o.addProperty(outKey, v);
    }

    private void dbgOnce(String key, long ms, String msg) {
        long now = System.currentTimeMillis();
        Long prev = throttle.put(key, now);
        if (prev != null && (now - prev) < ms) return;
        plugin.getLogger().info(msg);
    }

    private String safe(String s) {
        if (s == null) return "null";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        if (t.length() > 300) return t.substring(0, 300);
        return t;
    }

    private record Api(Method dbGet, Method prepareStatement) {
    }

    private record CacheEntry(long cachedAtMs, JsonArray bans, String latestIp, String source) {
    }
}