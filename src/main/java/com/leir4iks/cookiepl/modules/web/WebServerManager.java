package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class WebServerManager {
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

            server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
            server.start();

            plugin.getFoliaLib().getScheduler().runNextTick(this::updateCache);
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
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization,token");
        }
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
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