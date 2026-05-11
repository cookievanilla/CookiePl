package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.tags.TagsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class WebServerManager {
    private static final String TAGS_SECRET_HEADER = "X-Tags-Secret";
    private final CookiePl plugin;
    private final DatabaseManager db;
    private final int port;
    private final boolean corsEnabled;
    private final String adminToken;

    private HttpServer server;
    private WrappedTask updateTask;
    private volatile String cachedServerInfoJson = "{}";

    public WebServerManager(CookiePl plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.db = databaseManager;
        this.port = plugin.getConfig().getInt("modules.web-server.port", 8080);
        this.corsEnabled = plugin.getConfig().getBoolean("modules.web-server.cors-enabled", true);
        this.adminToken = plugin.getConfig().getString("modules.web-server.admin-token", "");
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/players", this::handlePlayers);
            server.createContext("/ip", this::handleIpLookup);
            server.createContext("/serverinfo", this::handleServerInfo);
            server.createContext("/serverstats", this::handleServerStats);
            server.createContext("/admin/players", this::handleAdminPlayers);
            server.createContext("/tags", this::handleTags);
            server.createContext("/whitelist", this::handleWhitelist);

            server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
            server.start();

            plugin.getFoliaLib().getScheduler().runNextTick(task -> updateCache());
            updateTask = plugin.getFoliaLib().getScheduler().runTimer(this::updateCache, 600L, 600L);

            plugin.getLogManager().info("Web Server started on port " + port);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to start Web Server on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
        updateTask = null;
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.getLogManager().info("Web Server stopped.");
        }
    }

    private void updateCache() {
        try {
            Server s = plugin.getServer();
            JsonObject root = db.newJsonObject();

            JsonObject serverObj = new JsonObject();
            serverObj.addProperty("online", s.getOnlinePlayers().size());
            serverObj.addProperty("online_players", s.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
            serverObj.addProperty("max_players", s.getMaxPlayers());
            serverObj.addProperty("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / (1000L * 60L * 60L));
            serverObj.addProperty("version", s.getBukkitVersion());
            serverObj.addProperty("server", s.getName());
            try { serverObj.addProperty("average_mspt", Bukkit.getAverageTickTime()); } catch (Exception ignored) {}
            root.add("server", serverObj);

            try {
                String totalStats = db.getServerStatsJson();
                JsonElement el = JsonParser.parseString(totalStats);
                if (el != null && el.isJsonObject()) root.add("total_stats", el.getAsJsonObject());
                else root.add("total_stats", new JsonObject());
            } catch (Exception ignored) {
                root.add("total_stats", new JsonObject());
            }

            try {
                String worlds = db.getWorldsJson();
                JsonElement el = JsonParser.parseString(worlds);
                root.add("worlds", el);
            } catch (Exception ignored) {
                root.add("worlds", JsonParser.parseString("[]"));
            }

            try {
                String regions = db.getFoliaRegionsJson();
                JsonElement el = JsonParser.parseString(regions);
                root.add("folia_regions", el);
            } catch (Exception ignored) {
                root.add("folia_regions", JsonParser.parseString("[]"));
            }

            try {
                String discord = db.getDiscordStatsJson();
                JsonElement el = JsonParser.parseString(discord);
                if (el != null && el.isJsonObject()) root.add("discord", el.getAsJsonObject());
                else root.add("discord", new JsonObject());
            } catch (Exception ignored) {
                root.add("discord", new JsonObject());
            }

            cachedServerInfoJson = db.toJsonString(root);
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update web server cache: " + e.getMessage());
        }
    }

    private void handlePlayers(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (path.equals("/players") || path.equals("/players/")) {
            send(ex, 200, db.getPlayersSummaryJson());
            return;
        }

        if (path.startsWith("/players/")) {
            String id = path.substring("/players/".length()).trim();
            if (id.isEmpty()) {
                send(ex, 200, db.getPlayersSummaryJson());
                return;
            }

            String ip = clientIp(ex);
            String body = db.getPlayerJsonById(id, ip);
            send(ex, body.contains("\"error\":\"Player not found\"") ? 404 : 200, body);
            return;
        }

        send(ex, 200, db.getPlayersSummaryJson());
    }

    private void handleIpLookup(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (path.equals("/ip") || path.equals("/ip/")) {
            send(ex, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        if (!path.startsWith("/ip/")) {
            send(ex, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String ipQuery = path.substring("/ip/".length()).trim();
        if (ipQuery.isEmpty()) {
            send(ex, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String remoteIp = clientIp(ex);
        String body = db.getPlayersByIpJson(ipQuery, remoteIp);

        if (body.contains("\"error\":\"Forbidden\"")) {
            send(ex, 403, body);
            return;
        }
        if (body.contains("\"error\":\"Invalid IP\"")) {
            send(ex, 400, body);
            return;
        }

        send(ex, 200, body);
    }

    private void handleAdminPlayers(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        String base = "/admin/players/";
        if (!path.startsWith(base) || path.length() <= base.length()) {
            send(ex, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String query = path.substring(base.length()).trim();
        if (query.isEmpty()) {
            send(ex, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            DatabaseManager.AdminResponse r = db.adminGetFlex(query);
            send(ex, r.status(), r.body());
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            if (adminToken == null || adminToken.isBlank()) {
                send(ex, 500, "{\"error\":\"Admin token is not configured\"}");
                return;
            }
            String token = ex.getRequestHeaders().getFirst("token");
            if (token == null || !token.equals(adminToken)) {
                send(ex, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            DatabaseManager.AdminResponse r = db.adminPostFlex(query, body);
            send(ex, r.status(), r.body());
            return;
        }

        send(ex, 405, "{\"error\":\"Method not allowed\"}");
    }

    private void handleTags(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (!"/tags/sync".equals(path) && !"/tags/sync/".equals(path)) {
            send(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        TagsManager tagsManager = plugin.getTagsManager();
        if (tagsManager == null) {
            send(ex, 503, "{\"error\":\"Tags module is disabled\"}");
            return;
        }

        String remoteIp = clientIp(ex);
        if (!isTagsIpAllowed(remoteIp)) {
            send(ex, 403, "{\"error\":\"Forbidden\"}");
            return;
        }

        String expectedSecret = plugin.getConfig().getString("modules.tags.secret", "");
        if (expectedSecret != null && !expectedSecret.isBlank()) {
            String providedSecret = ex.getRequestHeaders().getFirst(TAGS_SECRET_HEADER);
            if (providedSecret == null || !expectedSecret.equals(providedSecret)) {
                send(ex, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            TagsManager.SyncResult result = tagsManager.applySync(body);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("guild_id", TagsManager.REQUIRED_GUILD_ID);
            response.addProperty("definitions", result.definitionsCount());
            response.addProperty("members", result.membersCount());
            response.addProperty("synced_at", result.syncedAt());
            db.refreshPlayersDataAsync();
            send(ex, 200, response.toString());
        } catch (IllegalArgumentException e) {
            send(ex, 400, jsonError(e.getMessage()));
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to process tags sync: " + e.getMessage());
            send(ex, 500, jsonError("Internal server error"));
        }
    }

    private void handleWhitelist(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (!"/whitelist".equals(path) && !"/whitelist/".equals(path)) {
            send(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String remoteIp = clientIp(ex);
        if (!isTagsIpAllowed(remoteIp)) {
            send(ex, 403, "{\"error\":\"Forbidden\"}");
            return;
        }

        File file = whitelistFile();
        if (!file.isFile()) {
            send(ex, 404, "{\"error\":\"Whitelist file not found\"}");
            return;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            plugin.getLogManager().warn("Failed to read whitelist file: " + e.getMessage());
            send(ex, 500, "{\"error\":\"Failed to read whitelist file\"}");
            return;
        }

        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private File whitelistFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder != null) {
            return new File(new File(pluginsFolder, "simplewhitelist"), "whitelist.txt");
        }
        return new File("plugins/simplewhitelist/whitelist.txt");
    }

    private void handleServerInfo(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        send(ex, 200, cachedServerInfoJson);
    }

    private void handleServerStats(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        send(ex, 200, db.getServerStatsJson());
    }

    private boolean preflight(HttpExchange ex) throws IOException {
        if (corsEnabled) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization,token," + TAGS_SECRET_HEADER);
        }
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private boolean isTagsIpAllowed(String ip) {
        List<String> whitelist = plugin.getConfig().getStringList("modules.tags.ip-whitelist");
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (Exception ignored) {
            return false;
        }
        for (String entry : whitelist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (matchesIpRule(address, entry.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesIpRule(InetAddress address, String rule) {
        try {
            if (rule.contains("/")) {
                String[] parts = rule.split("/", 2);
                InetAddress networkAddress = InetAddress.getByName(parts[0]);
                int prefixLength = Integer.parseInt(parts[1]);
                byte[] addressBytes = address.getAddress();
                byte[] networkBytes = networkAddress.getAddress();
                if (addressBytes.length != networkBytes.length) {
                    return false;
                }
                int totalBits = addressBytes.length * 8;
                if (prefixLength < 0 || prefixLength > totalBits) {
                    return false;
                }
                BigInteger addressValue = new BigInteger(1, addressBytes);
                BigInteger networkValue = new BigInteger(1, networkBytes);
                BigInteger mask = prefixLength == 0
                        ? BigInteger.ZERO
                        : BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE).shiftRight(totalBits - prefixLength).shiftLeft(totalBits - prefixLength);
                return addressValue.and(mask).equals(networkValue.and(mask));
            }
            InetAddress exactAddress = InetAddress.getByName(rule);
            return Arrays.equals(address.getAddress(), exactAddress.getAddress());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String jsonError(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message == null ? "Unknown error" : message);
        return out.toString();
    }

    private String clientIp(HttpExchange ex) {
        try {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                if (parts.length > 0 && !parts[0].trim().isBlank()) return parts[0].trim();
            }
            String xri = ex.getRequestHeaders().getFirst("X-Real-IP");
            if (xri != null && !xri.isBlank()) return xri.trim();
            if (ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null) return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = (json == null ? "" : json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}