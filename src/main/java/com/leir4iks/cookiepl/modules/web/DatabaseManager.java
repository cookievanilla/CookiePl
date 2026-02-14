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
import java.util.Base64;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private final ConcurrentHashMap<UUID, Long> srNameLookupCooldownUntil = new ConcurrentHashMap<>();
    private volatile long srGlobalRateLimitUntil = 0L;
    private final ConcurrentHashMap<UUID, Long> skinResolveRetryAfter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mojangNameLookupCooldownUntil = new ConcurrentHashMap<>();
    private volatile long mojangNameGlobalRateLimitUntil = 0L;

    private final boolean serverOnlineMode;
    private final boolean includeHeavyStats;
    private final String skinsRestorerJdbcUrl;

    public DatabaseManager(CookiePl plugin) {
        this.plugin = plugin;
        this.discordSrvFolder = new File(plugin.getDataFolder().getParentFile(), "DiscordSRV");
        this.accountsFile = new File(discordSrvFolder, "accounts.aof");
        this.userCacheFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "usercache.json");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.serverOnlineMode = plugin.getServer().getOnlineMode();
        this.includeHeavyStats = plugin.getConfig().getBoolean("modules.web-server.include-heavy-stats", false);
        this.skinsRestorerJdbcUrl = plugin.getConfig().getString("modules.web-server.skinsrestorer-jdbc-url", "").trim();
    }

    public void start() {
        loadExternalCacheFromYml();

        plugin.getFoliaLib().getScheduler().runNextTick(t -> {
            ensureSkinsRestorerHook();
            rebuildSync();
        });

        plugin.getFoliaLib().getScheduler().runAsync(t -> refreshAsync());
        plugin.getFoliaLib().getScheduler().runTimerAsync(t -> refreshAsync(), 1200L, 1200L);
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
            ensureSkinsRestorerHook();
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
        if (links == null || links.isEmpty()) {
            latestResolvedSkins = Map.of();
            return;
        }

        Map<UUID, SkinInfo> map = new HashMap<>();

        Map<UUID, SkinInfo> fromDatabase = resolveSkinsFromSkinsRestorerDatabase(links);

        for (AccountLink link : links) {
            String preferredName = externalNickByDiscordId.get(link.discordId);
            String cacheName = uuidToName.get(link.uuid.toString());
            String offlineName = null;
            try {
                offlineName = Bukkit.getOfflinePlayer(link.uuid).getName();
            } catch (Throwable ignored) {
            }

            String skinId = firstValidMinecraftName(preferredName, cacheName, offlineName);
            if (skinId == null || skinId.isBlank()) skinId = "MHF_Steve";

            SkinInfo resolved = fromDatabase.get(link.uuid);
            if (resolved == null && playerStorage != null) {
                resolved = skinInfoFromProperty(tryGetSkinByUuid(playerStorage, link.uuid), "skinsrestorer-uuid", skinId);
                if (resolved == null) {
                    resolved = resolveSkinFromSkinsRestorerIdentifier(link.uuid, skinId);
                }
            }

            if (resolved != null) {
                map.put(link.uuid, resolved);
                continue;
            }

            SkinInfo si = new SkinInfo(
                    skinId,
                    mcHeadsAvatarUrl(skinId),
                    "https://mc-heads.net/skin/" + skinId + ".png",
                    "discordsrv-name"
            );

            map.put(link.uuid, si);
        }

        latestResolvedSkins = Collections.unmodifiableMap(map);
    }

    private Map<UUID, SkinInfo> resolveSkinsFromSkinsRestorerDatabase(List<AccountLink> links) {
        if (skinsRestorerJdbcUrl == null || skinsRestorerJdbcUrl.isBlank() || links == null || links.isEmpty()) {
            return Map.of();
        }

        Set<String> wanted = new HashSet<>();
        for (AccountLink link : links) {
            wanted.add(link.uuid.toString().toLowerCase(Locale.ROOT));
            wanted.add(link.uuid.toString().replace("-", "").toLowerCase(Locale.ROOT));
        }

        try (Connection connection = DriverManager.getConnection(skinsRestorerJdbcUrl)) {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, Set<String>> tableColumns = readTableColumns(metaData);

            Map<UUID, SkinInfo> direct = resolveDirectPlayerTextures(connection, tableColumns, links);

            String playersTable = pickPlayersTable(tableColumns);
            String skinsTable = pickSkinsTable(tableColumns);
            if (playersTable == null || skinsTable == null) return direct;

            String playerUuidColumn = pickFirst(tableColumns.get(playersTable), "uuid", "player_uuid", "uniqueid", "playeruniqueid");
            String playerSkinColumn = pickFirst(tableColumns.get(playersTable), "skin_identifier", "skinidentifier", "skin_id", "skinid", "identifier");

            String skinIdentifierColumn = pickFirst(tableColumns.get(skinsTable), "identifier", "skin_identifier", "skinidentifier", "skin_id", "skinid", "name");
            String skinValueColumn = pickFirst(tableColumns.get(skinsTable), "value", "texture", "property_value", "data");
            String skinUrlColumn = pickFirst(tableColumns.get(skinsTable), "url", "texture_url", "skin_url");
            String skinHashColumn = pickFirst(tableColumns.get(skinsTable), "hash", "texture_id", "texture_hash");

            if (playerUuidColumn == null || playerSkinColumn == null || skinIdentifierColumn == null) return Map.of();

            Map<String, String> uuidToIdentifier = new HashMap<>();
            String playersSql = "SELECT `" + playerUuidColumn + "`, `" + playerSkinColumn + "` FROM `" + playersTable + "`";
            try (PreparedStatement ps = connection.prepareStatement(playersSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawUuid = rs.getString(1);
                    String rawIdentifier = rs.getString(2);
                    if (rawUuid == null || rawIdentifier == null || rawIdentifier.isBlank()) continue;
                    String normalizedUuid = normalizeUuid(rawUuid);
                    if (normalizedUuid == null) continue;
                    if (!wanted.contains(normalizedUuid) && !wanted.contains(normalizedUuid.replace("-", ""))) continue;
                    uuidToIdentifier.put(normalizedUuid, rawIdentifier);
                }
            }

            if (uuidToIdentifier.isEmpty()) return Map.of();

            Map<String, SkinInfo> identifierToSkin = new HashMap<>();
            StringBuilder skinSql = new StringBuilder("SELECT `" + skinIdentifierColumn + "`");
            if (skinValueColumn != null) skinSql.append(", `").append(skinValueColumn).append("`");
            if (skinUrlColumn != null) skinSql.append(", `").append(skinUrlColumn).append("`");
            if (skinHashColumn != null) skinSql.append(", `").append(skinHashColumn).append("`");
            skinSql.append(" FROM `").append(skinsTable).append("`");

            try (PreparedStatement ps = connection.prepareStatement(skinSql.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String identifier = rs.getString(1);
                    if (identifier == null || identifier.isBlank()) continue;

                    String value = skinValueColumn == null ? null : rs.getString(skinValueColumn);
                    String textureUrl = skinUrlColumn == null ? null : rs.getString(skinUrlColumn);
                    String hash = skinHashColumn == null ? null : rs.getString(skinHashColumn);

                    SkinInfo skin = skinInfoFromDatabaseData(identifier, value, textureUrl, hash);
                    if (skin != null) identifierToSkin.put(identifier.toLowerCase(Locale.ROOT), skin);
                }
            }

            Map<UUID, SkinInfo> output = new HashMap<>(direct);
            for (AccountLink link : links) {
                String identifier = uuidToIdentifier.get(link.uuid.toString().toLowerCase(Locale.ROOT));
                if (identifier == null) continue;
                SkinInfo skin = identifierToSkin.get(identifier.toLowerCase(Locale.ROOT));
                if (skin != null) output.put(link.uuid, skin);
            }

            return output;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static SkinInfo skinInfoFromDatabaseData(String identifier, String propertyValue, String textureUrl, String hash) {
        String resolvedTextureUrl = (textureUrl == null) ? "" : textureUrl.trim();
        String resolvedHash = (hash == null) ? "" : hash.trim();

        if ((resolvedTextureUrl.isEmpty() || !resolvedTextureUrl.contains("/texture/")) && propertyValue != null && !propertyValue.isBlank()) {
            String decodedUrl = decodeTextureUrlFromEncodedProperty(propertyValue.trim());
            if (!decodedUrl.isBlank()) resolvedTextureUrl = decodedUrl;
        }

        if (resolvedHash.isEmpty()) {
            String extracted = extractTextureHash(resolvedTextureUrl);
            if (extracted != null && !extracted.isBlank()) resolvedHash = extracted;
        }

        if (resolvedHash.isEmpty() && identifier != null && !identifier.isBlank()) {
            String candidateFromUrl = extractTextureHash(identifier);
            if (candidateFromUrl != null && !candidateFromUrl.isBlank()) {
                resolvedHash = candidateFromUrl;
            } else {
                String candidate = firstValidMinecraftName(identifier);
                if (candidate != null) resolvedHash = candidate;
            }
        }

        if (resolvedHash.isEmpty()) return null;

        if (resolvedTextureUrl.isEmpty()) {
            resolvedTextureUrl = "https://textures.minecraft.net/texture/" + resolvedHash;
        }

        return new SkinInfo(resolvedHash, mcHeadsAvatarUrl(resolvedHash), resolvedTextureUrl, "skinsrestorer-db");
    }

    private static Map<String, Set<String>> readTableColumns(DatabaseMetaData metaData) throws Exception {
        Map<String, Set<String>> tableColumns = new HashMap<>();
        try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (tableName == null || tableName.isBlank()) continue;

                Set<String> cols = new HashSet<>();
                try (ResultSet columns = metaData.getColumns(null, null, tableName, "%")) {
                    while (columns.next()) {
                        String col = columns.getString("COLUMN_NAME");
                        if (col != null) cols.add(col.toLowerCase(Locale.ROOT));
                    }
                }
                tableColumns.put(tableName, cols);
            }
        }
        return tableColumns;
    }

    private static String pickPlayersTable(Map<String, Set<String>> tableColumns) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Set<String>> entry : tableColumns.entrySet()) {
            String table = entry.getKey();
            Set<String> cols = entry.getValue();
            if (cols == null) continue;
            boolean hasUuid = cols.contains("uuid") || cols.contains("player_uuid") || cols.contains("uniqueid") || cols.contains("playeruniqueid");
            boolean hasSkinRef = cols.contains("skin_identifier") || cols.contains("skinidentifier") || cols.contains("skin_id") || cols.contains("skinid") || cols.contains("identifier");
            if (!hasUuid || !hasSkinRef) continue;

            int score = 0;
            String lower = table.toLowerCase(Locale.ROOT);
            if (lower.contains("player")) score += 5;
            if (lower.contains("skin")) score += 2;
            if (cols.contains("skin_identifier") || cols.contains("skinidentifier")) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = table;
            }
        }
        return best;
    }

    private static String pickSkinsTable(Map<String, Set<String>> tableColumns) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Set<String>> entry : tableColumns.entrySet()) {
            String table = entry.getKey();
            Set<String> cols = entry.getValue();
            if (cols == null) continue;
            boolean hasIdentifier = cols.contains("identifier") || cols.contains("skin_identifier") || cols.contains("skinidentifier") || cols.contains("skin_id") || cols.contains("skinid") || cols.contains("name");
            boolean hasTexturePayload = cols.contains("value") || cols.contains("texture") || cols.contains("property_value") || cols.contains("url") || cols.contains("texture_url") || cols.contains("hash") || cols.contains("texture_hash");
            if (!hasIdentifier || !hasTexturePayload) continue;

            int score = 0;
            String lower = table.toLowerCase(Locale.ROOT);
            if (lower.contains("skin")) score += 5;
            if (lower.contains("player")) score -= 2;
            if (cols.contains("value") || cols.contains("property_value")) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = table;
            }
        }
        return best;
    }

    private Map<UUID, SkinInfo> resolveDirectPlayerTextures(Connection connection, Map<String, Set<String>> tableColumns, List<AccountLink> links) {
        Map<UUID, SkinInfo> out = new HashMap<>();
        if (tableColumns == null || tableColumns.isEmpty()) return out;

        Set<String> wanted = new HashSet<>();
        for (AccountLink link : links) {
            wanted.add(link.uuid.toString().toLowerCase(Locale.ROOT));
            wanted.add(link.uuid.toString().replace("-", "").toLowerCase(Locale.ROOT));
        }

        for (Map.Entry<String, Set<String>> entry : tableColumns.entrySet()) {
            String table = entry.getKey();
            Set<String> cols = entry.getValue();
            String uuidCol = pickFirst(cols, "uuid", "player_uuid", "uniqueid", "playeruniqueid");
            String valueCol = pickFirst(cols, "value", "texture", "property_value", "data");
            String urlCol = pickFirst(cols, "url", "texture_url", "skin_url");
            String hashCol = pickFirst(cols, "hash", "texture_hash", "texture_id");

            if (uuidCol == null || (valueCol == null && urlCol == null && hashCol == null)) continue;

            StringBuilder sql = new StringBuilder("SELECT `").append(uuidCol).append("`");
            if (valueCol != null) sql.append(", `").append(valueCol).append("`");
            if (urlCol != null) sql.append(", `").append(urlCol).append("`");
            if (hashCol != null) sql.append(", `").append(hashCol).append("`");
            sql.append(" FROM `").append(table).append("`");

            try (PreparedStatement ps = connection.prepareStatement(sql.toString());
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawUuid = rs.getString(1);
                    String normalizedUuid = normalizeUuid(rawUuid);
                    if (normalizedUuid == null) continue;
                    if (!wanted.contains(normalizedUuid) && !wanted.contains(normalizedUuid.replace("-", ""))) continue;

                    String value = valueCol == null ? null : rs.getString(valueCol);
                    String textureUrl = urlCol == null ? null : rs.getString(urlCol);
                    String hash = hashCol == null ? null : rs.getString(hashCol);

                    SkinInfo skin = skinInfoFromDatabaseData(null, value, textureUrl, hash);
                    if (skin != null) out.put(UUID.fromString(normalizedUuid), skin);
                }
            } catch (Throwable ignored) {
            }
        }

        return out;
    }

    private static String pickFirst(Set<String> haystack, String... candidates) {
        if (haystack == null || haystack.isEmpty()) return null;
        for (String candidate : candidates) {
            if (haystack.contains(candidate)) return candidate;
        }
        return null;
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
                addNameAlias(newNameToDiscord, n, link.discordId);
            }

            addNameAlias(newNameToDiscord, uuidToName.get(link.uuid.toString()), link.discordId);
            addNameAlias(newNameToDiscord, Bukkit.getOfflinePlayer(link.uuid).getName(), link.discordId);
            addNameAlias(newNameToDiscord, externalNickByDiscordId.get(link.discordId), link.discordId);
        }

        playersByDiscordId = Collections.unmodifiableMap(newPlayersById);
        uuidToDiscordId = Collections.unmodifiableMap(newUuidToDiscord);
        nameLowerToDiscordId = Collections.unmodifiableMap(newNameToDiscord);
        cachedDatabaseJson = arr.toString();
    }

    private static void addNameAlias(Map<String, String> aliases, String name, String discordId) {
        if (name == null) return;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return;
        aliases.putIfAbsent(normalized, discordId);
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

        if (hasPlayed && online && includeHeavyStats) {
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

        String fallback = (name == null || name.isBlank() || name.equalsIgnoreCase("Unknown")) ? "MHF_Steve" : name;
        return new SkinInfo(fallback, mcHeadsAvatarUrl(fallback), "", "fallback");
    }

    private SkinInfo resolveSkinFromSkinsRestorer(PlayerStorage ps, UUID uuid, String... candidateNames) {
        String firstName = firstValidMinecraftName(candidateNames);

        SkinInfo byUuid = skinInfoFromProperty(tryGetSkinByUuid(ps, uuid), "skinsrestorer", firstName);
        if (byUuid != null) return byUuid;

        SkinInfo byIdentifier = resolveSkinFromSkinsRestorerIdentifier(uuid, firstName);
        if (byIdentifier != null) return byIdentifier;

        if (!canAttemptSrNameLookup(uuid) || firstName == null) return null;

        SkinInfo byName = skinInfoFromProperty(tryGetSkinByName(ps, uuid, firstName), "skinsrestorer-name", firstName);
        if (byName != null) {
            srNameLookupCooldownUntil.remove(uuid);
            return byName;
        }

        return null;
    }

    private SkinProperty tryGetSkinByUuid(PlayerStorage ps, UUID uuid) {
        try {
            Optional<SkinProperty> direct = ps.getSkinOfPlayer(uuid);
            if (direct.isPresent()) return direct.get();
        } catch (Throwable ignored) {
        }

        try {
            return callOptionalSkinProperty(ps, "getSkinOfPlayer", new Class[]{UUID.class}, new Object[]{uuid}).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }



    private SkinProperty tryGetSkinByName(PlayerStorage ps, UUID uuid, String name) {
        if (name == null || name.isBlank()) return null;

        try {
            Optional<SkinProperty> direct = ps.getSkinForPlayer(uuid, name);
            if (direct.isPresent()) return direct.get();
            markSrNameLookupCooldown(uuid, false);
        } catch (Throwable t) {
            markSrNameLookupCooldown(uuid, isRateLimitError(t));
            return null;
        }

        try {
            Optional<SkinProperty> opt = callOptionalSkinProperty(ps, "getSkinForPlayer", new Class[]{UUID.class, String.class, boolean.class}, new Object[]{uuid, name, serverOnlineMode});
            if (opt.isEmpty()) {
                opt = callOptionalSkinProperty(ps, "getSkinForPlayer", new Class[]{UUID.class, String.class}, new Object[]{uuid, name});
            }
            SkinProperty result = opt.orElse(null);
            markSrNameLookupCooldown(uuid, false);
            return result;
        } catch (Throwable t) {
            markSrNameLookupCooldown(uuid, isRateLimitError(t));
            return null;
        }
    }

    private boolean canAttemptSrNameLookup(UUID uuid) {
        long now = System.currentTimeMillis();
        if (now < srGlobalRateLimitUntil) return false;
        Long next = srNameLookupCooldownUntil.get(uuid);
        return next == null || now >= next;
    }

    private void markSrNameLookupCooldown(UUID uuid, boolean rateLimited) {
        long now = System.currentTimeMillis();
        long delayMs = rateLimited ? 900_000L : 300_000L;
        srNameLookupCooldownUntil.put(uuid, now + delayMs);
        if (rateLimited) {
            srGlobalRateLimitUntil = Math.max(srGlobalRateLimitUntil, now + 600_000L);
        }
    }

    private static boolean isRateLimitError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("429") || lower.contains("rate limit") || lower.contains("please wait a minute")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private SkinInfo resolveSkinFromSkinsRestorerIdentifier(UUID uuid, String nameFallback) {
        SkinsRestorer sr = skinsRestorer;
        if (sr == null) return null;

        try {
            Object skinStorage = sr.getSkinStorage();
            if (skinStorage == null) return null;

            String identifier = null;
            try {
                Method getSkinId = playerStorage.getClass().getMethod("getSkinIdOfPlayer", UUID.class);
                Object rawId = getSkinId.invoke(playerStorage, uuid);
                if (rawId instanceof Optional<?> o) {
                    if (o.isPresent() && o.get() != null) identifier = String.valueOf(o.get());
                } else if (rawId != null) {
                    identifier = String.valueOf(rawId);
                }
            } catch (Throwable ignored) {
            }

            if (identifier == null || identifier.isBlank()) return null;

            for (String methodName : new String[]{"getSkinData", "getSkinDataByIdentifier", "findSkinData"}) {
                try {
                    Method m = skinStorage.getClass().getMethod(methodName, String.class);
                    Object res = m.invoke(skinStorage, identifier);
                    Object skinData = unwrapOptional(res);
                    if (skinData == null) continue;

                    SkinProperty prop = extractSkinPropertyFromSkinData(skinData);
                    if (prop != null) return skinInfoFromProperty(prop, "skinsrestorer-storage", nameFallback);
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> o) {
            return o.orElse(null);
        }
        return value;
    }

    private static SkinProperty extractSkinPropertyFromSkinData(Object skinData) {
        for (String methodName : new String[]{"getProperty", "getSkinProperty", "getValue"}) {
            try {
                Method m = skinData.getClass().getMethod(methodName);
                Object value = m.invoke(skinData);
                if (value instanceof SkinProperty prop) return prop;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String firstValidMinecraftName(String... names) {
        if (names == null) return null;
        for (String name : names) {
            if (name == null) continue;
            String normalized = name.trim();
            if (normalized.isEmpty()) continue;
            if (normalized.length() < 3 || normalized.length() > 16) continue;
            boolean valid = true;
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if (!(c == '_' || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                    valid = false;
                    break;
                }
            }
            if (valid) return normalized;
        }
        return null;
    }

    private static SkinInfo skinInfoFromProperty(SkinProperty prop, String source, String nameFallback) {
        if (prop == null) return null;

        String textureUrl = "";
        try {
            textureUrl = PropertyUtils.getSkinTextureUrl(prop);
        } catch (Throwable ignored) {
        }

        String hash = "";
        try {
            hash = PropertyUtils.getSkinTextureHash(prop);
        } catch (Throwable ignored) {
        }

        if (textureUrl == null || textureUrl.isBlank()) {
            textureUrl = decodeTextureUrlFromPropertyValue(prop);
        }

        if ((hash == null || hash.isBlank()) && textureUrl != null && !textureUrl.isBlank()) {
            String extracted = extractTextureHash(textureUrl);
            if (extracted != null) hash = extracted;
        }

        if ((hash == null || hash.isBlank()) && (textureUrl == null || textureUrl.isBlank())) {
            return null;
        }

        if (hash == null || hash.isBlank()) {
            hash = (nameFallback == null || nameFallback.isBlank()) ? "MHF_Steve" : nameFallback;
        }

        if (textureUrl == null || textureUrl.isBlank()) {
            textureUrl = "https://textures.minecraft.net/texture/" + hash;
        }

        String avatarUrl = mcHeadsAvatarUrl(hash);
        return new SkinInfo(hash, avatarUrl, textureUrl, source);
    }

    private static String decodeTextureUrlFromEncodedProperty(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";

        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(decoded).getAsJsonObject();
            if (!root.has("textures") || !root.get("textures").isJsonObject()) return "";
            JsonObject textures = root.getAsJsonObject("textures");
            if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) return "";
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (!skin.has("url")) return "";
            String url = skin.get("url").getAsString();
            return url == null ? "" : url;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String decodeTextureUrlFromPropertyValue(SkinProperty prop) {
        String encoded = null;
        for (String methodName : new String[]{"getValue", "value", "getTextureValue"}) {
            try {
                Method m = prop.getClass().getMethod(methodName);
                Object value = m.invoke(prop);
                if (value instanceof String str && !str.isBlank()) {
                    encoded = str;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        return decodeTextureUrlFromEncodedProperty(encoded);
    }

    private SkinInfo resolveSkinFromMojangByName(String name) {
        if (name == null || name.isBlank()) return null;

        String normalizedName = firstValidMinecraftName(name);
        if (normalizedName == null) return null;

        long now = System.currentTimeMillis();
        if (now < mojangNameGlobalRateLimitUntil) return null;

        String key = normalizedName.toLowerCase(Locale.ROOT);
        Long perNameCooldown = mojangNameLookupCooldownUntil.get(key);
        if (perNameCooldown != null && now < perNameCooldown) return null;

        HttpURLConnection con = null;
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + normalizedName);
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestMethod("GET");

            int code = con.getResponseCode();
            if (code == 429) {
                mojangNameLookupCooldownUntil.put(key, now + 900_000L);
                mojangNameGlobalRateLimitUntil = Math.max(mojangNameGlobalRateLimitUntil, now + 600_000L);
                return null;
            }
            if (code == 204 || code == 404) {
                mojangNameLookupCooldownUntil.put(key, now + 3_600_000L);
                return null;
            }
            if (code != 200) {
                mojangNameLookupCooldownUntil.put(key, now + 600_000L);
                return null;
            }

            String body;
            try (var in = con.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("id")) {
                mojangNameLookupCooldownUntil.put(key, now + 600_000L);
                return null;
            }

            String uuidStr = normalizeUuid(root.get("id").getAsString());
            if (uuidStr == null) {
                mojangNameLookupCooldownUntil.put(key, now + 600_000L);
                return null;
            }

            SkinInfo si = resolveSkinFromMojang(UUID.fromString(uuidStr));
            if (si != null) {
                mojangNameLookupCooldownUntil.put(key, now + 3_600_000L);
                return si;
            }

            mojangNameLookupCooldownUntil.put(key, now + 600_000L);
            return null;
        } catch (Throwable t) {
            if (isRateLimitError(t)) {
                mojangNameLookupCooldownUntil.put(key, now + 900_000L);
                mojangNameGlobalRateLimitUntil = Math.max(mojangNameGlobalRateLimitUntil, now + 600_000L);
            } else {
                mojangNameLookupCooldownUntil.put(key, now + 600_000L);
            }
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private SkinInfo resolveSkinFromMojang(UUID uuid) {
        HttpURLConnection con = null;
        try {
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", ""));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestMethod("GET");

            if (con.getResponseCode() != 200) return null;

            String body;
            try (var in = con.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("properties") || !root.get("properties").isJsonArray()) return null;

            for (JsonElement element : root.getAsJsonArray("properties")) {
                if (!element.isJsonObject()) continue;
                JsonObject propertyObj = element.getAsJsonObject();
                if (!propertyObj.has("name") || !"textures".equals(propertyObj.get("name").getAsString())) continue;
                if (!propertyObj.has("value")) continue;

                String encoded = propertyObj.get("value").getAsString();
                if (encoded == null || encoded.isBlank()) continue;

                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                JsonObject texturesRoot = JsonParser.parseString(decoded).getAsJsonObject();
                if (!texturesRoot.has("textures") || !texturesRoot.get("textures").isJsonObject()) continue;

                JsonObject textures = texturesRoot.getAsJsonObject("textures");
                if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) continue;

                JsonObject skinObject = textures.getAsJsonObject("SKIN");
                if (!skinObject.has("url")) continue;

                String textureUrl = skinObject.get("url").getAsString();
                String hash = extractTextureHash(textureUrl);
                if (hash == null) continue;

                return new SkinInfo(hash, mcHeadsAvatarUrl(hash), textureUrl, "mojang-sessionserver");
            }
        } catch (Throwable ignored) {
        } finally {
            if (con != null) con.disconnect();
        }

        return null;
    }

    private static String extractTextureHash(String textureUrl) {
        if (textureUrl == null || textureUrl.isBlank()) return null;
        String normalized = textureUrl.trim();
        normalized = normalized.replace("\"", "").replace("'", "");
        if (normalized.endsWith(")")) normalized = normalized.substring(0, normalized.length() - 1);

        int queryIdx = normalized.indexOf('?');
        if (queryIdx >= 0) normalized = normalized.substring(0, queryIdx);

        String marker = "/texture/";
        int idx = normalized.lastIndexOf(marker);
        if (idx < 0) return null;
        String hash = normalized.substring(idx + marker.length()).trim();
        if (hash.endsWith("/")) hash = hash.substring(0, hash.length() - 1);
        return hash.isEmpty() ? null : hash;
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
        fun.addProperty("jumps", stat(p, Statistic.JUMP));
        fun.addProperty("animals_bred", stat(p, Statistic.ANIMALS_BRED));
        fun.addProperty("fish_caught", stat(p, Statistic.FISH_CAUGHT));
        fun.addProperty("villager_trades", stat(p, Statistic.TRADED_WITH_VILLAGER));
        fun.addProperty("enchantments", statByName(p, "ITEM_ENCHANTED", "ENCHANT_ITEM"));
        stats.add("fun", fun);

        JsonObject top = new JsonObject();
        top.add("mined", new JsonArray());
        top.add("used", new JsonArray());
        stats.add("top", top);

        stats.addProperty("favorite_mined", "");
        stats.addProperty("favorite_used", "");

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
}
