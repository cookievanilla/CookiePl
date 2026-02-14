package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseManager {

    private final CookiePl plugin;

    private final File discordSrvFolder;
    private final File accountsFile;
    private final File userCacheFile;
    private final File dataFile;

    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private final ConcurrentHashMap<String, String> externalNickByDiscordId = new ConcurrentHashMap<>();

    private volatile List<AccountLink> latestLinks = List.of();
    private volatile Map<String, String> latestUuidToName = Map.of();

    private volatile String cachedDatabaseJson = "[]";
    private volatile Map<String, JsonObject> playersByDiscordId = Map.of();
    private volatile Map<String, String> uuidToDiscordId = Map.of();
    private volatile Map<String, String> nameLowerToDiscordId = Map.of();

    private final AtomicBoolean rebuildQueued = new AtomicBoolean(false);

    private volatile SkinsRestorer skinsRestorer;
    private volatile PlayerStorage playerStorage;

    private volatile Map<UUID, SkinInfo> latestResolvedSkins = Map.of();

    private final boolean serverOnlineMode;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.accountsFile = new File(discordSrvFolder, "accounts.aof");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.serverOnlineMode = plugin.getServer().getOnlineMode();
    }

    public void start() {
        loadExternalCacheFromYml();

        plugin.getFoliaLib().getScheduler().runAsync(t -> refreshAsync());
        plugin.getFoliaLib().getScheduler().runTimerAsync(t -> refreshAsync(), 1200L, 1200L);

        plugin.getFoliaLib().getScheduler().runNextTick(t -> rebuildSync());
    }

    public void stop() {
    }

    public String getCachedDatabaseJson() {
        return cachedDatabaseJson;
    }

    public String getPlayerJsonBySlug(String slugRaw) {
        if (slugRaw == null) return errorJson("Player not found");
        String slug = slugRaw.trim();
        if (slug.isEmpty()) return errorJson("Player not found");

        JsonObject byId = playersByDiscordId.get(slug);
        if (byId != null) return byId.toString();

        String normalizedUuid = normalizeUuid(slug);
        if (normalizedUuid != null) {
            String did = uuidToDiscordId.get(normalizedUuid);
            if (did != null) {
                JsonObject obj = playersByDiscordId.get(did);
                if (obj != null) return obj.toString();
            }
        }

        String didByName = nameLowerToDiscordId.get(slug.toLowerCase(Locale.ROOT));
        if (didByName != null) {
            JsonObject obj = playersByDiscordId.get(didByName);
            if (obj != null) return obj.toString();
        }

        return errorJson("Player not found");
    }

    private void refreshAsync() {
        try {
            boolean changed = updateExternalFromHttp();
            if (changed) saveExternalCacheToYml();
        } catch (Exception e) {
            plugin.getLogManager().warn("External nick sync failed: " + e.getMessage());
        }

        try {
            latestLinks = readDiscordSrvLinks(accountsFile);
        } catch (Exception e) {
            latestLinks = List.of();
            plugin.getLogManager().warn("accounts.aof read failed: " + e.getMessage());
        }

        try {
            latestUuidToName = readUserCache(userCacheFile);
        } catch (Exception e) {
            latestUuidToName = Map.of();
            plugin.getLogManager().warn("usercache.json read failed: " + e.getMessage());
        }

        try {
            rebuildSkinsAsync(latestLinks, latestUuidToName);
        } catch (Throwable ignored) {
        }

        if (rebuildQueued.compareAndSet(false, true)) {
            plugin.getFoliaLib().getScheduler().runNextTick(t -> {
                try {
                    rebuildSync();
                } finally {
                    rebuildQueued.set(false);
                }
            });
        }
    }

    private void rebuildSkinsAsync(List<AccountLink> links, Map<String, String> uuidToName) {
        PlayerStorage ps = playerStorage;
        if (ps == null || links == null || links.isEmpty()) {
            latestResolvedSkins = Map.of();
            return;
        }

        Map<UUID, SkinInfo> map = new HashMap<>();

        for (AccountLink link : links) {
            String name = externalNickByDiscordId.get(link.discordId);
            if (name == null) name = uuidToName.get(link.uuid.toString());
            if (name == null || name.isBlank()) continue;

            SkinProperty prop = null;

            try {
                prop = callOptionalSkinProperty(ps, "getSkinOfPlayer", new Class[]{UUID.class}, new Object[]{link.uuid}).orElse(null);
            } catch (Throwable ignored) {
            }

            if (prop == null) {
                try {
                    Optional<SkinProperty> opt = callOptionalSkinProperty(ps, "getSkinForPlayer", new Class[]{UUID.class, String.class, boolean.class}, new Object[]{link.uuid, name, serverOnlineMode});
                    if (opt.isEmpty()) {
                        opt = callOptionalSkinProperty(ps, "getSkinForPlayer", new Class[]{UUID.class, String.class}, new Object[]{link.uuid, name});
                    }
                    prop = opt.orElse(null);
                } catch (Throwable ignored) {
                }
            }

            SkinInfo si = skinInfoFromProperty(prop, "skinsrestorer");
            if (si != null) map.put(link.uuid, si);
        }

        latestResolvedSkins = Collections.unmodifiableMap(map);
    }

    @SuppressWarnings("unchecked")
    private Optional<SkinProperty> callOptionalSkinProperty(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            Object res = m.invoke(target, args);
            if (res instanceof Optional<?> o) {
                return (Optional<SkinProperty>) o;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }

    private void rebuildSync() {
        ensureSkinsRestorerHook();

        List<AccountLink> links = latestLinks;
        Map<String, String> uuidToName = latestUuidToName;

        Map<String, JsonObject> newPlayersById = new HashMap<>();
        Map<String, String> newUuidToDiscord = new HashMap<>();
        Map<String, String> newNameToDiscord = new HashMap<>();

        JsonArray arr = new JsonArray();

        for (AccountLink link : links) {
            JsonObject obj = buildPlayerObject(link.discordId, link.uuid, uuidToName);
            if (obj == null) continue;

            arr.add(obj);
            newPlayersById.put(link.discordId, obj);
            newUuidToDiscord.put(link.uuid.toString(), link.discordId);

            if (obj.has("minecraft_name")) {
                String n = obj.get("minecraft_name").getAsString();
                if (n != null && !n.isBlank()) newNameToDiscord.put(n.toLowerCase(Locale.ROOT), link.discordId);
            }
        }

        playersByDiscordId = Collections.unmodifiableMap(newPlayersById);
        uuidToDiscordId = Collections.unmodifiableMap(newUuidToDiscord);
        nameLowerToDiscordId = Collections.unmodifiableMap(newNameToDiscord);
        cachedDatabaseJson = arr.toString();
    }

    private JsonObject buildPlayerObject(String discordId, UUID uuid, Map<String, String> uuidToName) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);

        String name = externalNickByDiscordId.get(discordId);
        if (name == null) name = uuidToName.get(uuid.toString());
        if (name == null) name = off.getName();
        if (name == null) name = "Unknown";

        SkinInfo skin = resolveSkin(uuid, name);

        JsonObject obj = new JsonObject();
        obj.addProperty("id", discordId);
        obj.addProperty("minecraft_uuid", uuid.toString());
        obj.addProperty("minecraft_name", name);

        boolean online = off.isOnline();
        obj.addProperty("is_online", online);

        boolean hasPlayed = off.hasPlayedBefore() || online;
        obj.addProperty("has_played_before", hasPlayed);

        long first = safeLong(off::getFirstPlayed);
        long last = safeLong(off::getLastPlayed);

        obj.addProperty("first_played_ms", first);
        obj.addProperty("last_played_ms", last);
        obj.addProperty("first_played_iso", first > 0 ? Instant.ofEpochMilli(first).toString() : "");
        obj.addProperty("last_played_iso", last > 0 ? Instant.ofEpochMilli(last).toString() : "");

        obj.addProperty("is_banned", off.isBanned());
        obj.addProperty("is_whitelisted", off.isWhitelisted());

        obj.addProperty("skin_texture_id", skin.textureId);
        obj.addProperty("skin_url", skin.avatarUrl);
        obj.addProperty("skin_texture_url", skin.textureUrl);
        obj.addProperty("skin_source", skin.source);

        if (hasPlayed) {
            obj.add("stats", buildInterestingStats(off));
        } else {
            obj.add("stats", emptyStats());
        }

        return obj;
    }

    private void ensureSkinsRestorerHook() {
        if (skinsRestorer != null && playerStorage != null) return;

        Plugin srPlugin = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
        if (srPlugin == null || !srPlugin.isEnabled()) return;

        try {
            SkinsRestorer sr = SkinsRestorerProvider.get();
            skinsRestorer = sr;
            playerStorage = sr.getPlayerStorage();
        } catch (Throwable ignored) {
        }
    }

    private SkinInfo resolveSkin(UUID uuid, String name) {
        SkinInfo resolved = latestResolvedSkins.get(uuid);
        if (resolved != null) return resolved;

        PlayerStorage ps = playerStorage;
        if (ps != null) {
            try {
                SkinProperty prop = callOptionalSkinProperty(ps, "getSkinOfPlayer", new Class[]{UUID.class}, new Object[]{uuid}).orElse(null);
                SkinInfo si = skinInfoFromProperty(prop, "skinsrestorer");
                if (si != null) return si;
            } catch (Throwable ignored) {
            }
        }

        String fallback = (name == null || name.isBlank() || name.equalsIgnoreCase("Unknown")) ? "MHF_Steve" : name;
        return new SkinInfo(fallback, mcHeadsAvatarUrl(fallback), "", "fallback");
    }

    private static SkinInfo skinInfoFromProperty(SkinProperty prop, String source) {
        if (prop == null) return null;

        String hash;
        try {
            hash = PropertyUtils.getSkinTextureHash(prop);
        } catch (Throwable t) {
            return null;
        }
        if (hash == null || hash.isBlank()) return null;

        String textureUrl = "";
        try {
            textureUrl = PropertyUtils.getSkinTextureUrl(prop);
        } catch (Throwable ignored) {
        }
        if (textureUrl == null || textureUrl.isBlank()) {
            textureUrl = "https://textures.minecraft.net/texture/" + hash;
        }

        String avatarUrl = mcHeadsAvatarUrl(hash);
        return new SkinInfo(hash, avatarUrl, textureUrl, source);
    }

    private static String mcHeadsAvatarUrl(String textureOrName) {
        return "https://mc-heads.net/avatar/" + textureOrName + "/64.png";
    }

    private JsonObject buildInterestingStats(OfflinePlayer p) {
        JsonObject stats = new JsonObject();

        long playTicks = stat(p, Statistic.PLAY_ONE_MINUTE);
        double hours = playTicks / 20.0 / 3600.0;
        stats.addProperty("play_time_hours", Math.round(hours * 10.0) / 10.0);

        long leaves = stat(p, Statistic.LEAVE_GAME);
        long joins = p.isOnline() ? leaves + 1 : leaves;
        stats.addProperty("joins", joins);

        stats.addProperty("deaths", stat(p, Statistic.DEATHS));

        JsonObject kills = new JsonObject();
        kills.addProperty("players", stat(p, Statistic.PLAYER_KILLS));
        kills.addProperty("mobs", stat(p, Statistic.MOB_KILLS));
        stats.add("kills", kills);

        JsonObject damage = new JsonObject();
        damage.addProperty("dealt", stat(p, Statistic.DAMAGE_DEALT));
        damage.addProperty("taken", stat(p, Statistic.DAMAGE_TAKEN));
        stats.add("damage", damage);

        long walk = stat(p, Statistic.WALK_ONE_CM);
        long sprint = stat(p, Statistic.SPRINT_ONE_CM);
        long fly = stat(p, Statistic.FLY_ONE_CM);
        long elytra = stat(p, Statistic.AVIATE_ONE_CM);
        long swim = stat(p, Statistic.SWIM_ONE_CM);
        long boat = stat(p, Statistic.BOAT_ONE_CM);

        long totalCm = walk + sprint + fly + elytra + swim + boat;

        JsonObject distance = new JsonObject();
        distance.addProperty("total_km", Math.round((totalCm / 100000.0) * 10.0) / 10.0);
        distance.addProperty("walk_km", Math.round((walk / 100000.0) * 10.0) / 10.0);
        distance.addProperty("fly_km", Math.round(((fly + elytra) / 100000.0) * 10.0) / 10.0);
        distance.addProperty("swim_km", Math.round((swim / 100000.0) * 10.0) / 10.0);
        stats.add("distance", distance);

        long minedTotal = 0;
        long usedTotal = 0;

        TopN minedTop = new TopN(5);
        TopN usedTop = new TopN(5);

        for (Material m : Material.values()) {
            if (m == Material.AIR || m.isLegacy()) continue;

            if (m.isBlock()) {
                long c = stat(p, Statistic.MINE_BLOCK, m);
                if (c > 0) {
                    minedTotal += c;
                    minedTop.offer(m.getKey().toString(), c);
                }
            }

            if (m.isItem()) {
                long u = stat(p, Statistic.USE_ITEM, m);
                if (u > 0) {
                    usedTotal += u;
                    usedTop.offer(m.getKey().toString(), u);
                }
            }
        }

        JsonObject blocks = new JsonObject();
        blocks.addProperty("mined_total", minedTotal);
        stats.add("blocks", blocks);

        JsonObject items = new JsonObject();
        items.addProperty("used_total", usedTotal);
        items.addProperty("crafted_total", sumStatByMaterial(p, Statistic.CRAFT_ITEM));
        items.addProperty("picked_up_total", sumStatByMaterial(p, Statistic.PICKUP));
        items.addProperty("dropped_total", sumStatByMaterial(p, Statistic.DROP));
        stats.add("items", items);

        JsonObject fun = new JsonObject();
        fun.addProperty("jumps", stat(p, Statistic.JUMP));
        fun.addProperty("animals_bred", stat(p, Statistic.ANIMALS_BRED));
        fun.addProperty("fish_caught", stat(p, Statistic.FISH_CAUGHT));
        fun.addProperty("villager_trades", stat(p, Statistic.TRADED_WITH_VILLAGER));
        fun.addProperty("enchantments", statByName(p, "ITEM_ENCHANTED", "ENCHANT_ITEM"));
        stats.add("fun", fun);

        JsonObject top = new JsonObject();
        top.add("mined", minedTop.toJsonArray());
        top.add("used", usedTop.toJsonArray());
        stats.add("top", top);

        if (minedTop.bestKey != null) stats.addProperty("favorite_mined", minedTop.bestKey);
        if (usedTop.bestKey != null) stats.addProperty("favorite_used", usedTop.bestKey);

        return stats;
    }

    private JsonObject emptyStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("play_time_hours", 0);
        stats.addProperty("joins", 0);
        stats.addProperty("deaths", 0);

        JsonObject kills = new JsonObject();
        kills.addProperty("players", 0);
        kills.addProperty("mobs", 0);
        stats.add("kills", kills);

        JsonObject damage = new JsonObject();
        damage.addProperty("dealt", 0);
        damage.addProperty("taken", 0);
        stats.add("damage", damage);

        JsonObject distance = new JsonObject();
        distance.addProperty("total_km", 0);
        distance.addProperty("walk_km", 0);
        distance.addProperty("fly_km", 0);
        distance.addProperty("swim_km", 0);
        stats.add("distance", distance);

        JsonObject blocks = new JsonObject();
        blocks.addProperty("mined_total", 0);
        stats.add("blocks", blocks);

        JsonObject items = new JsonObject();
        items.addProperty("used_total", 0);
        items.addProperty("crafted_total", 0);
        items.addProperty("picked_up_total", 0);
        items.addProperty("dropped_total", 0);
        stats.add("items", items);

        JsonObject fun = new JsonObject();
        fun.addProperty("jumps", 0);
        fun.addProperty("animals_bred", 0);
        fun.addProperty("fish_caught", 0);
        fun.addProperty("villager_trades", 0);
        fun.addProperty("enchantments", 0);
        stats.add("fun", fun);

        JsonObject top = new JsonObject();
        top.add("mined", new JsonArray());
        top.add("used", new JsonArray());
        stats.add("top", top);

        stats.addProperty("favorite_mined", "");
        stats.addProperty("favorite_used", "");

        return stats;
    }

    private static long statByName(OfflinePlayer p, String... names) {
        for (String n : names) {
            try {
                Statistic s = Statistic.valueOf(n);
                return p.getStatistic(s);
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private boolean updateExternalFromHttp() throws IOException {
        URL url = new URL(externalDatabaseUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(3000);
        con.setReadTimeout(3000);
        con.setRequestMethod("GET");

        boolean changed = false;

        int code = con.getResponseCode();
        if (code == 200) {
            List<String> lines;
            try (var in = con.getInputStream()) {
                lines = Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
            }

            for (String line : lines) {
                if (line == null) continue;
                String t = line.trim();
                if (t.isEmpty()) continue;

                String[] parts = t.split("\\s+");
                if (parts.length < 2) continue;

                String discordId = parts[0];
                String nick = parts[1];

                String prev = externalNickByDiscordId.put(discordId, nick);
                if (!Objects.equals(prev, nick)) changed = true;
            }
        }

        con.disconnect();
        return changed;
    }

    private void loadExternalCacheFromYml() {
        if (!dataFile.exists()) return;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
            for (String key : cfg.getKeys(false)) {
                String v = cfg.getString(key);
                if (v != null) externalNickByDiscordId.put(key, v);
            }
        } catch (Exception e) {
            plugin.getLogManager().warn("data.yml load failed: " + e.getMessage());
        }
    }

    private void saveExternalCacheToYml() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<String, String> e : externalNickByDiscordId.entrySet()) {
                cfg.set(e.getKey(), e.getValue());
            }
            cfg.save(dataFile);
        } catch (Exception e) {
            plugin.getLogManager().warn("data.yml save failed: " + e.getMessage());
        }
    }

    private static List<AccountLink> readDiscordSrvLinks(File file) throws IOException {
        if (!file.exists()) return List.of();
        List<String> lines = Files.readAllLines(file.toPath());
        List<AccountLink> out = new ArrayList<>();

        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;

            String[] parts = t.split("\\s+");
            if (parts.length < 2) continue;

            String discordId = parts[0];
            String uuidStr = normalizeUuid(parts[1]);
            if (uuidStr == null) continue;

            out.add(new AccountLink(discordId, UUID.fromString(uuidStr)));
        }

        return out;
    }

    private static Map<String, String> readUserCache(File file) throws Exception {
        if (!file.exists()) return Map.of();

        Map<String, String> map = new HashMap<>();
        try (FileReader r = new FileReader(file)) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonArray()) return map;

            JsonArray arr = el.getAsJsonArray();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (!o.has("uuid") || !o.has("name")) continue;

                String u = o.get("uuid").getAsString();
                String n = o.get("name").getAsString();

                String nu = normalizeUuid(u);
                if (nu != null && n != null && !n.isBlank()) map.put(nu, n);
            }
        }

        return map;
    }

    private static String normalizeUuid(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;

        if (t.length() == 36) {
            try {
                UUID.fromString(t);
                return t.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }

        String hex = t.replace("-", "");
        if (hex.length() != 32) return null;

        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        try {
            UUID.fromString(dashed);
            return dashed.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long safeLong(LongSupplierEx fn) {
        try {
            return fn.getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long stat(OfflinePlayer p, Statistic st) {
        try {
            return p.getStatistic(st);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long stat(OfflinePlayer p, Statistic st, Material m) {
        try {
            return p.getStatistic(st, m);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long sumStatByMaterial(OfflinePlayer p, Statistic stat) {
        long total = 0;
        for (Material m : Material.values()) {
            if (m == Material.AIR || m.isLegacy() || !m.isItem()) continue;
            long v = stat(p, stat, m);
            if (v > 0) total += v;
        }
        return total;
    }

    private static String errorJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o.toString();
    }

    private record AccountLink(String discordId, UUID uuid) {
    }

    private record SkinInfo(String textureId, String avatarUrl, String textureUrl, String source) {
    }

    private interface LongSupplierEx {
        long getAsLong();
    }

    private static final class TopN {
        private final int n;
        private final PriorityQueue<Entry> pq;
        private String bestKey;
        private long bestVal;

        private TopN(int n) {
            this.n = n;
            this.pq = new PriorityQueue<>(Comparator.comparingLong(a -> a.count));
        }

        private void offer(String key, long count) {
            if (count > bestVal) {
                bestVal = count;
                bestKey = key;
            }
            pq.offer(new Entry(key, count));
            if (pq.size() > n) pq.poll();
        }

        private JsonArray toJsonArray() {
            List<Entry> list = new ArrayList<>(pq);
            list.sort((a, b) -> Long.compare(b.count, a.count));
            JsonArray arr = new JsonArray();
            for (Entry e : list) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.key);
                o.addProperty("count", e.count);
                arr.add(o);
            }
            return arr;
        }

        private record Entry(String key, long count) {
        }
    }
}

