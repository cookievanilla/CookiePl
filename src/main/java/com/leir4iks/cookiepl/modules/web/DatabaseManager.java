package com.leir4iks.cookiepl.modules.web;

import com.google.gson.*;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.property.MojangSkinDataResult;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.storage.PlayerStorage;
import net.skinsrestorer.api.storage.SkinStorage;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DatabaseManager {

    private final CookiePl plugin;
    private final File discordSrvFolder;
    private final File userCacheFile;
    private final File dataFile;
    private final String externalDatabaseUrl = "http://212.80.7.211:20081/";

    private final AtomicReference<Map<String, String>> externalNickByDiscord = new AtomicReference<>(Map.of());
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>(Snapshot.empty());

    private final AtomicReference<WrappedTask> refreshTaskRef = new AtomicReference<>();
    private final AtomicLong lastSrHookAttemptMs = new AtomicLong(0);

    private volatile List<File> worldFolders = List.of();

    private volatile SkinsRestorer skinsRestorer;
    private volatile PlayerStorage playerStorage;
    private volatile SkinStorage skinStorage;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void start() {
        loadDataYml();

        plugin.getFoliaLib().getScheduler().runNextTick(task -> {
            List<File> wf = new ArrayList<>();
            for (World w : plugin.getServer().getWorlds()) {
                wf.add(w.getWorldFolder());
            }
            this.worldFolders = Collections.unmodifiableList(wf);
            ensureSkinsRestorerHook();
            plugin.getFoliaLib().getScheduler().runAsync(t -> refreshAsyncPipeline());
        });

        plugin.getFoliaLib().getScheduler().runTimerAsync(task -> {
            refreshTaskRef.compareAndSet(null, task);
            refreshAsyncPipeline();
        }, 20L * 60L, 20L * 60L * 10L);
    }

    public void stop() {
        WrappedTask t = refreshTaskRef.getAndSet(null);
        if (t != null) t.cancel();
    }

    public String getDatabaseJson() {
        return snapshotRef.get().allJson;
    }

    public String getPlayerJsonBySlug(String slug) {
        Snapshot s = snapshotRef.get();
        if (slug == null) return errorJson("Player not found");
        String key = slug.trim();
        if (key.isEmpty()) return errorJson("Player not found");

        String byId = s.byDiscordId.get(key);
        if (byId != null) return byId;

        String byUuid = s.byUuid.get(normalizeUuidKey(key));
        if (byUuid != null) return byUuid;

        String byName = s.byNameLower.get(key.toLowerCase(Locale.ROOT));
        if (byName != null) return byName;

        return errorJson("Player not found");
    }

    private void refreshAsyncPipeline() {
        try {
            Map<String, String> externalFetched = fetchExternalNickMapOrNull();
            Map<String, String> external = externalFetched != null ? externalFetched : externalNickByDiscord.get();

            Map<String, String> discordToUuid = readDiscordSrvAccounts();
            Map<String, String> uuidToName = loadUserCache();

            Map<String, JsonObject> statsByUuid = readCompactStats(discordToUuid.values());
            Map<String, JsonObject> advByUuid = readCompactAdvancements(discordToUuid.values());

            RefreshData data = new RefreshData(external, discordToUuid, uuidToName, statsByUuid, advByUuid);
            plugin.getFoliaLib().getScheduler().runNextTick(task -> applyRefreshSync(data));
        } catch (Throwable t) {
            plugin.getLogManager().warn("Refresh failed: " + t.getMessage());
        }
    }

    private void applyRefreshSync(RefreshData data) {
        try {
            externalNickByDiscord.set(Collections.unmodifiableMap(data.externalNickByDiscord));
            plugin.getFoliaLib().getScheduler().runAsync(task -> saveDataYml(data.externalNickByDiscord));

            ensureSkinsRestorerHook();

            boolean serverOnlineMode = plugin.getServer().getOnlineMode();

            Map<String, String> byDiscordId = new HashMap<>();
            Map<String, String> byNameLower = new HashMap<>();
            Map<String, String> byUuid = new HashMap<>();
            JsonArray all = new JsonArray();

            for (Map.Entry<String, String> e : data.discordToUuid.entrySet()) {
                String discordId = e.getKey();
                String uuidStr = e.getValue();

                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (Exception ex) {
                    continue;
                }

                String name = resolveName(discordId, uuidStr, data.externalNickByDiscord, data.uuidToName);
                boolean isOnline = plugin.getServer().getPlayer(uuid) != null;

                OfflinePlayer off = plugin.getServer().getOfflinePlayer(uuid);
                boolean hasPlayedBefore = off.hasPlayedBefore() || isOnline;
                long firstPlayed = off.getFirstPlayed();
                long lastPlayed = off.getLastPlayed();

                SkinInfo skin = resolveSkin(uuid, name, serverOnlineMode);

                JsonObject player = new JsonObject();
                player.addProperty("id", discordId);
                player.addProperty("minecraft_uuid", uuid.toString());
                player.addProperty("minecraft_name", name);
                player.addProperty("is_online", isOnline);

                player.addProperty("has_played_before", hasPlayedBefore);
                player.addProperty("first_played_ms", firstPlayed);
                player.addProperty("last_played_ms", lastPlayed);
                player.addProperty("first_played_iso", firstPlayed > 0 ? Instant.ofEpochMilli(firstPlayed).toString() : "");
                player.addProperty("last_played_iso", lastPlayed > 0 ? Instant.ofEpochMilli(lastPlayed).toString() : "");
                player.addProperty("is_banned", off.isBanned());
                player.addProperty("is_whitelisted", off.isWhitelisted());

                player.addProperty("skin_texture_id", skin.textureId);
                player.addProperty("skin_url", skin.avatarUrl);
                player.addProperty("skin_source", skin.source);

                JsonObject stats = data.statsByUuid.getOrDefault(uuid.toString(), emptyCompactStats());
                JsonObject adv = data.advByUuid.getOrDefault(uuid.toString(), emptyCompactAdv());

                player.add("stats", stats);
                player.add("advancements", adv);

                String json = player.toString();

                all.add(player);
                byDiscordId.put(discordId, json);

                String normUuid = normalizeUuidKey(uuid.toString());
                byUuid.put(normUuid, json);
                byUuid.put(normUuid.replace("-", ""), json);

                if (name != null && !name.isBlank() && !name.equalsIgnoreCase("Unknown")) {
                    byNameLower.put(name.toLowerCase(Locale.ROOT), json);
                }
            }

            snapshotRef.set(new Snapshot(
                    Collections.unmodifiableMap(byDiscordId),
                    Collections.unmodifiableMap(byNameLower),
                    Collections.unmodifiableMap(byUuid),
                    all.toString()
            ));
        } catch (Throwable t) {
            plugin.getLogManager().warn("Apply refresh failed: " + t.getMessage());
        }
    }

    private void ensureSkinsRestorerHook() {
        if (playerStorage != null && skinStorage != null) return;

        long now = System.currentTimeMillis();
        long last = lastSrHookAttemptMs.get();
        if (now - last < 5000) return;
        if (!lastSrHookAttemptMs.compareAndSet(last, now)) return;

        try {
            SkinsRestorer sr = SkinsRestorerProvider.get();
            this.skinsRestorer = sr;
            this.playerStorage = sr.getPlayerStorage();
            this.skinStorage = sr.getSkinStorage();
        } catch (Throwable ignored) {
            this.skinsRestorer = null;
            this.playerStorage = null;
            this.skinStorage = null;
        }
    }

    private SkinInfo resolveSkin(UUID uuid, String playerName, boolean serverOnlineMode) {
        ensureSkinsRestorerHook();

        SkinProperty prop = null;
        String source = "steve";

        PlayerStorage ps = this.playerStorage;
        SkinStorage ss = this.skinStorage;

        if (ps != null) {
            try {
                Optional<SkinProperty> stored = ps.getSkinOfPlayer(uuid);
                if (stored.isPresent()) {
                    prop = stored.get();
                    source = "skinsrestorer_stored";
                }
            } catch (Throwable ignored) { }

            if (prop == null && playerName != null && !playerName.isBlank() && !playerName.equalsIgnoreCase("Unknown")) {
                try {
                    Optional<SkinProperty> join = ps.getSkinForPlayer(uuid, playerName, serverOnlineMode);
                    if (join.isPresent()) {
                        prop = join.get();
                        source = "skinsrestorer_join";
                    }
                } catch (DataRequestException ignored) {
                } catch (Throwable ignored) {
                }
            }
        }

        if (prop == null && ss != null && playerName != null && !playerName.isBlank() && !playerName.equalsIgnoreCase("Unknown")) {
            try {
                Optional<MojangSkinDataResult> mojang = ss.getPlayerSkin(playerName, true);
                if (mojang.isPresent()) {
                    prop = mojang.get().getSkinProperty();
                    source = "mojang_cached";
                }
            } catch (DataRequestException ignored) {
            } catch (Throwable ignored) {
            }
        }

        if (prop != null) {
            String hash = null;
            try {
                hash = PropertyUtils.getSkinTextureHash(prop);
            } catch (Throwable ignored) { }
            if (hash != null && !hash.isBlank()) {
                String url = "https://mc-heads.net/avatar/" + hash + ".png";
                return new SkinInfo(hash, url, source);
            }
        }

        if (playerName != null && !playerName.isBlank() && !playerName.equalsIgnoreCase("Unknown")) {
            String url = "https://mc-heads.net/avatar/" + playerName + ".png";
            return new SkinInfo(playerName, url, "name_fallback");
        }

        return new SkinInfo("MHF_Steve", "https://mc-heads.net/avatar/MHF_Steve.png", "steve");
    }

    private Map<String, JsonObject> readCompactStats(Collection<String> uuids) {
        Map<String, JsonObject> out = new HashMap<>();
        List<File> folders = this.worldFolders;

        for (String uuid : uuids) {
            if (uuid == null) continue;
            String u = uuid.trim();
            if (u.isEmpty()) continue;

            long deaths = 0;
            long playerKills = 0;
            long mobKills = 0;
            long joins = 0;

            long playTicks = 0;

            long minedTotal = 0;
            long usedTotal = 0;
            long craftedTotal = 0;
            long pickedUpTotal = 0;
            long droppedTotal = 0;

            long walkCm = 0;
            long sprintCm = 0;
            long swimCm = 0;
            long flyCm = 0;
            long boatCm = 0;
            long horseCm = 0;
            long elytraCm = 0;

            long damageDealt = 0;
            long damageTaken = 0;

            long jump = 0;
            long openChest = 0;
            long sleepInBed = 0;
            long animalsBred = 0;
            long fishCaught = 0;
            long tradeVillager = 0;
            long raidWins = 0;

            TopN minedTop = new TopN(5);
            TopN usedTop = new TopN(5);
            TopN killedTop = new TopN(5);

            for (File worldFolder : folders) {
                File statsFile = new File(new File(worldFolder, "stats"), u + ".json");
                if (!statsFile.exists()) continue;

                try {
                    JsonObject root = JsonParser.parseString(Files.readString(statsFile.toPath())).getAsJsonObject();
                    if (!root.has("stats") || !root.get("stats").isJsonObject()) continue;
                    JsonObject stats = root.getAsJsonObject("stats");

                    JsonObject custom = stats.has("minecraft:custom") && stats.get("minecraft:custom").isJsonObject()
                            ? stats.getAsJsonObject("minecraft:custom")
                            : null;

                    if (custom != null) {
                        deaths += getLong(custom, "minecraft:deaths");
                        playerKills += getLong(custom, "minecraft:player_kills");
                        mobKills += getLong(custom, "minecraft:mob_kills");
                        joins += getLong(custom, "minecraft:leave_game");

                        playTicks += getLongMulti(custom, "minecraft:play_time", "minecraft:play_one_minute");

                        walkCm += getLong(custom, "minecraft:walk_one_cm");
                        sprintCm += getLong(custom, "minecraft:sprint_one_cm");
                        swimCm += getLong(custom, "minecraft:swim_one_cm");
                        flyCm += getLong(custom, "minecraft:fly_one_cm");
                        boatCm += getLong(custom, "minecraft:boat_one_cm");
                        horseCm += getLong(custom, "minecraft:horse_one_cm");
                        elytraCm += getLong(custom, "minecraft:aviate_one_cm");

                        damageDealt += getLong(custom, "minecraft:damage_dealt");
                        damageTaken += getLong(custom, "minecraft:damage_taken");

                        jump += getLong(custom, "minecraft:jump");
                        openChest += getLongMulti(custom, "minecraft:open_chest", "minecraft:open_barrel");
                        sleepInBed += getLong(custom, "minecraft:sleep_in_bed");
                        animalsBred += getLong(custom, "minecraft:animals_bred");
                        fishCaught += getLong(custom, "minecraft:fish_caught");
                        tradeVillager += getLong(custom, "minecraft:traded_with_villager");
                        raidWins += getLong(custom, "minecraft:raids_won");
                    }

                    minedTotal += sumCategory(stats, "minecraft:mined", minedTop);
                    usedTotal += sumCategory(stats, "minecraft:used", usedTop);
                    craftedTotal += sumCategory(stats, "minecraft:crafted", null);
                    pickedUpTotal += sumCategory(stats, "minecraft:picked_up", null);
                    droppedTotal += sumCategory(stats, "minecraft:dropped", null);
                    sumCategory(stats, "minecraft:killed", killedTop);

                } catch (Throwable ignored) {
                }
            }

            JsonObject res = new JsonObject();

            double playHours = playTicks / 20.0 / 3600.0;
            res.addProperty("play_time_hours", round1(playHours));
            res.addProperty("joins", joins);
            res.addProperty("deaths", deaths);

            JsonObject kills = new JsonObject();
            kills.addProperty("players", playerKills);
            kills.addProperty("mobs", mobKills);
            res.add("kills", kills);

            JsonObject totals = new JsonObject();
            totals.addProperty("mined", minedTotal);
            totals.addProperty("used", usedTotal);
            totals.addProperty("crafted", craftedTotal);
            totals.addProperty("picked_up", pickedUpTotal);
            totals.addProperty("dropped", droppedTotal);
            res.add("totals", totals);

            JsonObject dist = new JsonObject();
            dist.addProperty("walk_km", round1(walkCm / 100000.0));
            dist.addProperty("sprint_km", round1(sprintCm / 100000.0));
            dist.addProperty("swim_km", round1(swimCm / 100000.0));
            dist.addProperty("fly_km", round1(flyCm / 100000.0));
            dist.addProperty("boat_km", round1(boatCm / 100000.0));
            dist.addProperty("horse_km", round1(horseCm / 100000.0));
            dist.addProperty("elytra_km", round1(elytraCm / 100000.0));
            dist.addProperty("total_km", round1((walkCm + sprintCm + swimCm + flyCm + boatCm + horseCm + elytraCm) / 100000.0));
            res.add("distance", dist);

            JsonObject actions = new JsonObject();
            actions.addProperty("jump", jump);
            actions.addProperty("open_chest", openChest);
            actions.addProperty("sleep_in_bed", sleepInBed);
            actions.addProperty("animals_bred", animalsBred);
            actions.addProperty("fish_caught", fishCaught);
            actions.addProperty("trade_with_villager", tradeVillager);
            actions.addProperty("raid_wins", raidWins);
            actions.addProperty("damage_dealt", damageDealt);
            actions.addProperty("damage_taken", damageTaken);
            res.add("actions", actions);

            JsonObject top = new JsonObject();
            top.add("mined", minedTop.toJsonArray());
            top.add("used", usedTop.toJsonArray());
            top.add("killed", killedTop.toJsonArray());
            res.add("top", top);

            out.put(u, res);
        }

        return out;
    }

    private Map<String, JsonObject> readCompactAdvancements(Collection<String> uuids) {
        Map<String, JsonObject> out = new HashMap<>();
        List<File> folders = this.worldFolders;

        for (String uuid : uuids) {
            if (uuid == null) continue;
            String u = uuid.trim();
            if (u.isEmpty()) continue;

            int completed = 0;
            List<AdvRec> recs = new ArrayList<>();

            for (File worldFolder : folders) {
                File advFile = new File(new File(worldFolder, "advancements"), u + ".json");
                if (!advFile.exists()) continue;

                try {
                    JsonObject root = JsonParser.parseString(Files.readString(advFile.toPath())).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> en : root.entrySet()) {
                        if (!en.getValue().isJsonObject()) continue;
                        JsonObject adv = en.getValue().getAsJsonObject();
                        if (!adv.has("done") || !adv.get("done").isJsonPrimitive() || !adv.get("done").getAsBoolean()) continue;

                        completed++;
                        long ts = extractLatestCriteriaTime(adv);
                        recs.add(new AdvRec(en.getKey(), ts));
                    }
                } catch (Throwable ignored) {
                }
            }

            recs.sort((a, b) -> Long.compare(b.ts, a.ts));

            JsonObject res = new JsonObject();
            res.addProperty("completed", completed);

            JsonArray recent = new JsonArray();
            for (int i = 0; i < Math.min(5, recs.size()); i++) recent.add(recs.get(i).key);
            res.add("recent", recent);

            out.put(u, res);
        }

        return out;
    }

    private long extractLatestCriteriaTime(JsonObject adv) {
        if (!adv.has("criteria") || !adv.get("criteria").isJsonObject()) return 0L;
        JsonObject criteria = adv.getAsJsonObject("criteria");
        long best = 0L;
        for (Map.Entry<String, JsonElement> c : criteria.entrySet()) {
            if (!c.getValue().isJsonPrimitive()) continue;
            String s = c.getValue().getAsString();
            try {
                long t = Instant.parse(s).toEpochMilli();
                if (t > best) best = t;
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private long sumCategory(JsonObject stats, String category, TopN top) {
        if (!stats.has(category) || !stats.get(category).isJsonObject()) return 0L;
        long sum = 0;
        JsonObject obj = stats.getAsJsonObject(category);
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            long v = safeLong(e.getValue());
            sum += v;
            if (top != null) top.offer(e.getKey(), v);
        }
        return sum;
    }

    private String resolveName(String discordId, String uuidStr, Map<String, String> external, Map<String, String> usercache) {
        String n = external.get(discordId);
        if (n != null && !n.isBlank()) return n;

        n = usercache.get(uuidStr);
        if (n != null && !n.isBlank()) return n;

        String noDash = uuidStr.replace("-", "");
        n = usercache.get(noDash);
        if (n != null && !n.isBlank()) return n;

        return "Unknown";
    }

    private Map<String, String> fetchExternalNickMapOrNull() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(externalDatabaseUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) return null;

            String body;
            try (var in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Map<String, String> map = new HashMap<>();
            for (String line : body.split("\n")) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) continue;
                map.put(parts[0], parts[1]);
            }
            return map;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, String> readDiscordSrvAccounts() {
        File accountsFile = new File(discordSrvFolder, "accounts.aof");
        if (!accountsFile.exists()) return Map.of();

        Map<String, String> map = new HashMap<>();
        try {
            for (String line : Files.readAllLines(accountsFile.toPath())) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) continue;
                map.put(parts[0], parts[1]);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, String> loadUserCache() {
        Map<String, String> map = new HashMap<>();
        if (!userCacheFile.exists()) return map;

        try (FileReader reader = new FileReader(userCacheFile)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonArray()) return map;

            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                if (!entry.isJsonObject()) continue;
                JsonObject obj = entry.getAsJsonObject();
                if (!obj.has("uuid") || !obj.has("name")) continue;
                map.put(obj.get("uuid").getAsString(), obj.get("name").getAsString());
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private void loadDataYml() {
        if (!dataFile.exists()) return;
        try {
            var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
            Map<String, String> map = new HashMap<>();
            for (String key : cfg.getKeys(false)) {
                String v = cfg.getString(key);
                if (v != null) map.put(key, v);
            }
            externalNickByDiscord.set(Collections.unmodifiableMap(map));
        } catch (Exception ignored) {
        }
    }

    private void saveDataYml(Map<String, String> data) {
        try {
            var cfg = new org.bukkit.configuration.file.YamlConfiguration();
            for (Map.Entry<String, String> e : data.entrySet()) {
                cfg.set(e.getKey(), e.getValue());
            }
            cfg.save(dataFile);
        } catch (IOException ignored) {
        }
    }

    private static String errorJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o.toString();
    }

    private static String normalizeUuidKey(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.length() == 32) {
            return t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-" + t.substring(16, 20) + "-" + t.substring(20);
        }
        return t;
    }

    private static long getLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return 0L;
        return safeLong(obj.get(key));
    }

    private static long getLongMulti(JsonObject obj, String key1, String key2) {
        long a = getLong(obj, key1);
        if (a != 0) return a;
        return getLong(obj, key2);
    }

    private static long safeLong(JsonElement el) {
        try {
            return el.getAsLong();
        } catch (Exception e) {
            try {
                return el.getAsInt();
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static JsonObject emptyCompactStats() {
        JsonObject res = new JsonObject();
        res.addProperty("play_time_hours", 0.0);
        res.addProperty("joins", 0);
        res.addProperty("deaths", 0);

        JsonObject kills = new JsonObject();
        kills.addProperty("players", 0);
        kills.addProperty("mobs", 0);
        res.add("kills", kills);

        JsonObject totals = new JsonObject();
        totals.addProperty("mined", 0);
        totals.addProperty("used", 0);
        totals.addProperty("crafted", 0);
        totals.addProperty("picked_up", 0);
        totals.addProperty("dropped", 0);
        res.add("totals", totals);

        JsonObject dist = new JsonObject();
        dist.addProperty("walk_km", 0.0);
        dist.addProperty("sprint_km", 0.0);
        dist.addProperty("swim_km", 0.0);
        dist.addProperty("fly_km", 0.0);
        dist.addProperty("boat_km", 0.0);
        dist.addProperty("horse_km", 0.0);
        dist.addProperty("elytra_km", 0.0);
        dist.addProperty("total_km", 0.0);
        res.add("distance", dist);

        JsonObject actions = new JsonObject();
        actions.addProperty("jump", 0);
        actions.addProperty("open_chest", 0);
        actions.addProperty("sleep_in_bed", 0);
        actions.addProperty("animals_bred", 0);
        actions.addProperty("fish_caught", 0);
        actions.addProperty("trade_with_villager", 0);
        actions.addProperty("raid_wins", 0);
        actions.addProperty("damage_dealt", 0);
        actions.addProperty("damage_taken", 0);
        res.add("actions", actions);

        JsonObject top = new JsonObject();
        top.add("mined", new JsonArray());
        top.add("used", new JsonArray());
        top.add("killed", new JsonArray());
        res.add("top", top);

        return res;
    }

    private static JsonObject emptyCompactAdv() {
        JsonObject res = new JsonObject();
        res.addProperty("completed", 0);
        res.add("recent", new JsonArray());
        return res;
    }

    private record RefreshData(
            Map<String, String> externalNickByDiscord,
            Map<String, String> discordToUuid,
            Map<String, String> uuidToName,
            Map<String, JsonObject> statsByUuid,
            Map<String, JsonObject> advByUuid
    ) {}

    private record SkinInfo(String textureId, String avatarUrl, String source) {}

    private record Snapshot(
            Map<String, String> byDiscordId,
            Map<String, String> byNameLower,
            Map<String, String> byUuid,
            String allJson
    ) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), "[]");
        }
    }

    private record AdvRec(String key, long ts) {}

    private static class TopN {
        private final int n;
        private final PriorityQueue<Entry> pq;

        private TopN(int n) {
            this.n = n;
            this.pq = new PriorityQueue<>(Comparator.comparingLong(a -> a.value));
        }

        private void offer(String key, long value) {
            if (value <= 0) return;
            pq.offer(new Entry(key, value));
            if (pq.size() > n) pq.poll();
        }

        private JsonArray toJsonArray() {
            List<Entry> list = new ArrayList<>(pq);
            list.sort((a, b) -> Long.compare(b.value, a.value));
            JsonArray arr = new JsonArray();
            for (Entry e : list) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.key);
                o.addProperty("count", e.value);
                arr.add(o);
            }
            return arr;
        }

        private record Entry(String key, long value) {}
    }
}
