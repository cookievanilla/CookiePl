package com.leir4iks.cookiepl.modules.web;

import com.google.gson.*;
import com.leir4iks.cookiepl.CookiePl;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.storage.PlayerStorage;
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

    private volatile List<File> worldFolders = List.of();

    private volatile SkinsRestorer skinsRestorer;
    private volatile PlayerStorage playerStorage;

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

            try {
                this.skinsRestorer = SkinsRestorerProvider.get();
                this.playerStorage = this.skinsRestorer.getPlayerStorage();
                plugin.getLogManager().info("SkinsRestorer API hooked.");
            } catch (Throwable t) {
                this.skinsRestorer = null;
                this.playerStorage = null;
                plugin.getLogManager().warn("SkinsRestorer API unavailable: " + t.getMessage());
            }

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

            Map<String, JsonObject> statsByUuid = readVanillaStats(discordToUuid.values());
            Map<String, JsonObject> advancementsByUuid = readVanillaAdvancements(discordToUuid.values());

            RefreshData data = new RefreshData(external, discordToUuid, uuidToName, statsByUuid, advancementsByUuid);

            plugin.getFoliaLib().getScheduler().runNextTick(task -> applyRefreshSync(data));
        } catch (Throwable t) {
            plugin.getLogManager().warn("Refresh pipeline failed: " + t.getMessage());
        }
    }

    private void applyRefreshSync(RefreshData data) {
        try {
            externalNickByDiscord.set(Collections.unmodifiableMap(data.externalNickByDiscord));
            plugin.getFoliaLib().getScheduler().runAsync(task -> saveDataYml(data.externalNickByDiscord));

            boolean onlineMode = plugin.getServer().getOnlineMode();

            Map<String, String> byDiscordId = new HashMap<>();
            Map<String, String> byNameLower = new HashMap<>();
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

                SkinInfo skin = resolveSkin(uuid, name, onlineMode);

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

                JsonObject stats = data.statsByUuid.getOrDefault(uuid.toString(), emptyStats());
                JsonObject adv = data.advancementsByUuid.getOrDefault(uuid.toString(), emptyAdvancements());

                player.add("stats", stats);
                player.add("advancements", adv);

                String json = player.toString();

                all.add(player);
                byDiscordId.put(discordId, json);
                if (name != null && !name.isBlank() && !name.equalsIgnoreCase("Unknown")) {
                    byNameLower.put(name.toLowerCase(Locale.ROOT), json);
                }
            }

            snapshotRef.set(new Snapshot(
                    Collections.unmodifiableMap(byDiscordId),
                    Collections.unmodifiableMap(byNameLower),
                    all.toString()
            ));
        } catch (Throwable t) {
            plugin.getLogManager().warn("applyRefreshSync failed: " + t.getMessage());
        }
    }

    private SkinInfo resolveSkin(UUID uuid, String playerName, boolean isOnlineMode) {
        String textureId = "MHF_Steve";
        String source = "steve";

        PlayerStorage ps = this.playerStorage;
        if (ps != null && playerName != null && !playerName.isBlank() && !playerName.equalsIgnoreCase("Unknown")) {
            try {
                Optional<SkinProperty> prop = ps.getSkinForPlayer(uuid, playerName, isOnlineMode);
                if (prop.isPresent()) {
                    String hash = PropertyUtils.getSkinTextureHash(prop.get());
                    if (hash != null && !hash.isBlank()) {
                        textureId = hash;
                        source = "skinsrestorer";
                    }
                }
            } catch (DataRequestException ignored) {
            } catch (Throwable ignored) {
            }
        }

        String avatarUrl = "https://mc-heads.net/avatar/" + textureId + ".png";
        return new SkinInfo(textureId, avatarUrl, source);
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

    private Map<String, JsonObject> readVanillaStats(Collection<String> uuids) {
        Map<String, JsonObject> out = new HashMap<>();
        List<File> folders = this.worldFolders;

        for (String uuid : uuids) {
            if (uuid == null) continue;
            String u = uuid.trim();
            if (u.isEmpty()) continue;

            JsonObject combined = new JsonObject();

            for (File worldFolder : folders) {
                File statsFile = new File(new File(worldFolder, "stats"), u + ".json");
                if (!statsFile.exists()) continue;

                try {
                    JsonObject root = JsonParser.parseString(Files.readString(statsFile.toPath())).getAsJsonObject();
                    if (!root.has("stats") || !root.get("stats").isJsonObject()) continue;
                    JsonObject stats = root.getAsJsonObject("stats");
                    mergeCategoryStats(combined, stats);
                } catch (Exception ignored) {
                }
            }

            out.put(u, wrapStatsWithSummary(combined));
        }

        return out;
    }

    private Map<String, JsonObject> readVanillaAdvancements(Collection<String> uuids) {
        Map<String, JsonObject> out = new HashMap<>();
        List<File> folders = this.worldFolders;

        for (String uuid : uuids) {
            if (uuid == null) continue;
            String u = uuid.trim();
            if (u.isEmpty()) continue;

            JsonObject merged = new JsonObject();

            for (File worldFolder : folders) {
                File advFile = new File(new File(worldFolder, "advancements"), u + ".json");
                if (!advFile.exists()) continue;

                try {
                    JsonObject root = JsonParser.parseString(Files.readString(advFile.toPath())).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> en : root.entrySet()) {
                        merged.add(en.getKey(), en.getValue());
                    }
                } catch (Exception ignored) {
                }
            }

            out.put(u, wrapAdvancements(merged));
        }

        return out;
    }

    private JsonObject wrapAdvancements(JsonObject byKey) {
        JsonObject res = new JsonObject();
        JsonArray doneKeys = new JsonArray();
        int completed = 0;

        for (Map.Entry<String, JsonElement> e : byKey.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject adv = e.getValue().getAsJsonObject();
            if (adv.has("done") && adv.get("done").isJsonPrimitive() && adv.get("done").getAsBoolean()) {
                completed++;
                doneKeys.add(e.getKey());
            }
        }

        res.addProperty("completed", completed);
        res.add("completed_keys", doneKeys);
        res.add("by_key", byKey);
        return res;
    }

    private void mergeCategoryStats(JsonObject target, JsonObject add) {
        for (Map.Entry<String, JsonElement> catEntry : add.entrySet()) {
            if (!catEntry.getValue().isJsonObject()) continue;

            String category = catEntry.getKey();
            JsonObject addObj = catEntry.getValue().getAsJsonObject();

            JsonObject tgtObj = target.has(category) && target.get(category).isJsonObject()
                    ? target.getAsJsonObject(category)
                    : new JsonObject();

            for (Map.Entry<String, JsonElement> statEntry : addObj.entrySet()) {
                int v = safeInt(statEntry.getValue());
                int cur = tgtObj.has(statEntry.getKey()) ? safeInt(tgtObj.get(statEntry.getKey())) : 0;
                tgtObj.addProperty(statEntry.getKey(), cur + v);
            }

            target.add(category, tgtObj);
        }
    }

    private JsonObject wrapStatsWithSummary(JsonObject byCategory) {
        JsonObject root = new JsonObject();
        JsonObject summary = new JsonObject();

        int deaths = getCustomInt(byCategory, "minecraft:deaths");
        int playerKills = getCustomInt(byCategory, "minecraft:player_kills");
        int mobKills = getCustomInt(byCategory, "minecraft:mob_kills");
        int leaveGame = getCustomInt(byCategory, "minecraft:leave_game");
        long playTicks = getCustomLong(byCategory, "minecraft:play_time");
        double playHours = playTicks / 20.0 / 3600.0;

        summary.addProperty("deaths", deaths);
        summary.addProperty("player_kills", playerKills);
        summary.addProperty("mob_kills", mobKills);
        summary.addProperty("joins", Math.max(0, leaveGame));
        summary.addProperty("play_time_hours", Math.round(playHours * 10.0) / 10.0);

        summary.addProperty("total_mined", sumCategory(byCategory, "minecraft:mined"));
        summary.addProperty("total_used", sumCategory(byCategory, "minecraft:used"));
        summary.addProperty("total_crafted", sumCategory(byCategory, "minecraft:crafted"));
        summary.addProperty("total_broken", sumCategory(byCategory, "minecraft:broken"));
        summary.addProperty("total_picked_up", sumCategory(byCategory, "minecraft:picked_up"));
        summary.addProperty("total_dropped", sumCategory(byCategory, "minecraft:dropped"));
        summary.addProperty("total_killed", sumCategory(byCategory, "minecraft:killed"));
        summary.addProperty("total_killed_by", sumCategory(byCategory, "minecraft:killed_by"));

        root.add("summary", summary);
        root.add("by_category", byCategory);
        return root;
    }

    private JsonObject emptyStats() {
        JsonObject root = new JsonObject();
        JsonObject summary = new JsonObject();
        summary.addProperty("deaths", 0);
        summary.addProperty("player_kills", 0);
        summary.addProperty("mob_kills", 0);
        summary.addProperty("joins", 0);
        summary.addProperty("play_time_hours", 0.0);
        summary.addProperty("total_mined", 0);
        summary.addProperty("total_used", 0);
        summary.addProperty("total_crafted", 0);
        summary.addProperty("total_broken", 0);
        summary.addProperty("total_picked_up", 0);
        summary.addProperty("total_dropped", 0);
        summary.addProperty("total_killed", 0);
        summary.addProperty("total_killed_by", 0);
        root.add("summary", summary);
        root.add("by_category", new JsonObject());
        return root;
    }

    private JsonObject emptyAdvancements() {
        JsonObject res = new JsonObject();
        res.addProperty("completed", 0);
        res.add("completed_keys", new JsonArray());
        res.add("by_key", new JsonObject());
        return res;
    }

    private int getCustomInt(JsonObject byCategory, String key) {
        JsonObject custom = byCategory.has("minecraft:custom") && byCategory.get("minecraft:custom").isJsonObject()
                ? byCategory.getAsJsonObject("minecraft:custom")
                : null;
        if (custom == null) return 0;
        return custom.has(key) ? safeInt(custom.get(key)) : 0;
    }

    private long getCustomLong(JsonObject byCategory, String key) {
        JsonObject custom = byCategory.has("minecraft:custom") && byCategory.get("minecraft:custom").isJsonObject()
                ? byCategory.getAsJsonObject("minecraft:custom")
                : null;
        if (custom == null) return 0L;
        if (!custom.has(key)) return 0L;
        try {
            return custom.get(key).getAsLong();
        } catch (Exception e) {
            return safeInt(custom.get(key));
        }
    }

    private long sumCategory(JsonObject byCategory, String category) {
        if (!byCategory.has(category) || !byCategory.get(category).isJsonObject()) return 0L;
        long sum = 0;
        for (Map.Entry<String, JsonElement> e : byCategory.getAsJsonObject(category).entrySet()) {
            sum += safeInt(e.getValue());
        }
        return sum;
    }

    private int safeInt(JsonElement el) {
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return 0;
        }
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

    private record RefreshData(
            Map<String, String> externalNickByDiscord,
            Map<String, String> discordToUuid,
            Map<String, String> uuidToName,
            Map<String, JsonObject> statsByUuid,
            Map<String, JsonObject> advancementsByUuid
    ) {}

    private record SkinInfo(String textureId, String avatarUrl, String source) {}

    private record Snapshot(
            Map<String, String> byDiscordId,
            Map<String, String> byNameLower,
            String allJson
    ) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), "[]");
        }
    }
}
