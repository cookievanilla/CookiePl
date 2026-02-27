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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LiteBansBridge {
    private static final long CACHE_MS = 15000L, API_RETRY_MS = 10000L;
    private static final int LIMIT = 0;

    private static final String[][] STR = {
            {"uuid", "UUID"}, {"reason", "REASON"},
            {"banned_by_uuid", "BANNED_BY_UUID"}, {"banned_by_name", "BANNED_BY_NAME"},
            {"removed_by_uuid", "REMOVED_BY_UUID"}, {"removed_by_name", "REMOVED_BY_NAME"},
            {"removed_by_reason", "REMOVED_BY_REASON"}, {"removed_by_date", "REMOVED_BY_DATE"},
            {"server_scope", "SERVER_SCOPE"}, {"server_origin", "SERVER_ORIGIN"}
    };
    private static final String[][] LNG = {{"id", "ID"}, {"time", "TIME"}, {"until", "UNTIL"}};
    private static final String[][] BOOL = {
            {"active", "ACTIVE"}, {"silent", "SILENT"}, {"ipban", "IPBAN"}, {"ipban_wildcard", "IPBAN_WILDCARD"}, {"warned", "WARNED"}
    };

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile Api api;
    private volatile long lastApiAttemptMs;

    public LiteBansBridge(CookiePl plugin) {}

    public void warmup() { ensureApi(); }

    public void close() {
        cache.clear();
        api = null;
        lastApiAttemptMs = 0L;
    }

    public JsonObject getLiteBansJson(UUID uuid, boolean ipAllowed, String remoteIp) {
        CacheEntry ce = (uuid == null) ? CacheEntry.empty() : get(uuid);
        JsonObject out = new JsonObject();
        out.add("bans", ipAllowed ? ce.bans : redactIps(ce.bans));
        out.add("mutes", ipAllowed ? ce.mutes : redactIps(ce.mutes));
        out.add("warnings", ipAllowed ? ce.warnings : redactIps(ce.warnings));
        out.add("kicks", ipAllowed ? ce.kicks : redactIps(ce.kicks));
        out.add("history", ipAllowed ? ce.history : redactIps(ce.history));
        return out;
    }

    private CacheEntry get(UUID uuid) {
        long now = System.currentTimeMillis();
        String key = uuid.toString().toLowerCase(Locale.ROOT);
        return cache.compute(key, (k, old) -> (old == null || now - old.cachedAtMs > CACHE_MS) ? fetch(uuid) : old);
    }

    private CacheEntry fetch(UUID uuid) {
        Api a = ensureApi();
        if (a == null) return CacheEntry.empty();

        String u1 = uuid.toString(), u2 = u1.replace("-", "");
        Object db;
        try { db = a.dbGet.invoke(null); } catch (Exception ignored) { return CacheEntry.empty(); }
        if (db == null) return CacheEntry.empty();

        String latestIp = queryLatestIp(a, db, u1, u2);

        JsonArray bans = safeQuery(a, db, "{bans}", u1, u2, latestIp);
        JsonArray mutes = safeQuery(a, db, "{mutes}", u1, u2, latestIp);
        JsonArray warnings = safeQuery(a, db, "{warnings}", u1, u2, latestIp);
        JsonArray kicks = safeQuery(a, db, "{kicks}", u1, u2, latestIp);

        JsonArray history = mergeHistory(bans, mutes, warnings, kicks);

        return new CacheEntry(System.currentTimeMillis(), bans, mutes, warnings, kicks, history, latestIp);
    }

    private JsonArray safeQuery(Api a, Object db, String table, String u1, String u2, String latestIp) {
        try { return queryTable(a, db, table, u1, u2, latestIp); }
        catch (Exception ignored) { return new JsonArray(); }
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonArray queryTable(Api a, Object db, String table, String u1, String u2, String latestIp) throws Exception {
        String sql = "SELECT * FROM " + table + " WHERE uuid=? OR uuid=? ORDER BY time DESC" + (LIMIT > 0 ? " LIMIT " + LIMIT : "");
        try (PreparedStatement st = (PreparedStatement) a.prepareStatement.invoke(db, sql)) {
            st.setString(1, u1);
            st.setString(2, u2);
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Integer> idx = buildIndex(md);
                JsonArray arr = new JsonArray();

                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    for (String[] m : LNG) o.addProperty(m[0], optLong(rs, idx, m[1]));
                    for (String[] m : STR) o.addProperty(m[0], optStr(rs, idx, m[1]));
                    for (String[] m : BOOL) o.addProperty(m[0], optBool(rs, idx, m[1]));

                    String ip = rawStr(rs, idx, "IP");
                    if (isBlank(ip)) ip = latestIp;
                    o.addProperty("ip", isBlank(ip) ? "" : ip);

                    arr.add(o);
                }
                return arr;
            }
        }
    }

    private JsonArray mergeHistory(JsonArray bans, JsonArray mutes, JsonArray warnings, JsonArray kicks) {
        ArrayList<JsonObject> all = new ArrayList<>(
                (bans == null ? 0 : bans.size()) + (mutes == null ? 0 : mutes.size()) +
                        (warnings == null ? 0 : warnings.size()) + (kicks == null ? 0 : kicks.size())
        );
        addTyped(all, bans, "ban");
        addTyped(all, mutes, "mute");
        addTyped(all, warnings, "warn");
        addTyped(all, kicks, "kick");
        all.sort((a, b) -> Long.compare(longProp(b, "time"), longProp(a, "time")));
        JsonArray out = new JsonArray();
        for (JsonObject o : all) out.add(o);
        return out;
    }

    private void addTyped(List<JsonObject> out, JsonArray arr, String type) {
        if (arr == null || arr.isEmpty()) return;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject().deepCopy();
            o.addProperty("type", type);
            out.add(o);
        }
    }

    private long longProp(JsonObject o, String k) {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L; }
        catch (Exception ignored) { return 0L; }
    }

    private Api ensureApi() {
        Api existing = api;
        if (existing != null) return existing;

        long now = System.currentTimeMillis();
        if (now - lastApiAttemptMs < API_RETRY_MS) return null;
        lastApiAttemptMs = now;

        Plugin lb = Bukkit.getPluginManager().getPlugin("LiteBans");
        if (lb == null || !lb.isEnabled()) return null;

        try {
            ClassLoader cl = lb.getClass().getClassLoader();
            Class<?> dbClass = Class.forName("litebans.api.Database", true, cl);
            Api a = new Api(dbClass.getMethod("get"), dbClass.getMethod("prepareStatement", String.class));
            api = a;
            return a;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonArray redactIps(JsonArray in) {
        JsonArray out = new JsonArray();
        if (in == null || in.isEmpty()) return out;
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
            String a = md.getColumnLabel(i), b = md.getColumnName(i);
            if (!isBlank(a)) idx.put(a.toUpperCase(Locale.ROOT), i);
            if (!isBlank(b)) idx.putIfAbsent(b.toUpperCase(Locale.ROOT), i);
        }
        return idx;
    }

    private String rawStr(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return null;
        try { return rs.getString(i); } catch (Exception ignored) { return null; }
    }

    private String optStr(ResultSet rs, Map<String, Integer> idx, String key) {
        String s = rawStr(rs, idx, key);
        return s == null ? "" : s;
    }

    private long optLong(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return 0L;
        try {
            long v = rs.getLong(i);
            return rs.wasNull() ? 0L : v;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean optBool(ResultSet rs, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null) return false;
        try {
            boolean v = rs.getBoolean(i);
            return !rs.wasNull() && v;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private record Api(Method dbGet, Method prepareStatement) {}
    private record CacheEntry(long cachedAtMs, JsonArray bans, JsonArray mutes, JsonArray warnings, JsonArray kicks, JsonArray history, String latestIp) {
        static CacheEntry empty() { return new CacheEntry(System.currentTimeMillis(), new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray(), null); }
    }
}