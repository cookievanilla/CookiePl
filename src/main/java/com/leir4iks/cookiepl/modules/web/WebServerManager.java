package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leir4iks.cookiepl.CookiePl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
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
            server.createContext("/serverinfo", this::handleServerInfo);
            server.createContext("/serverstats", this::handleServerStats);
            server.createContext("/admin/players", this::handleAdminPlayers);

            server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
            server.start();

            plugin.getFoliaLib().getScheduler().runAsync(t -> updateCache());
            updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateCache, 600L, 600L);

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
            JsonObject json = db.newJsonObject();
            json.addProperty("online", s.getOnlinePlayers().size());
            json.addProperty("online_players", s.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.joining(", ")));
            json.addProperty("max_players", s.getMaxPlayers());
            json.addProperty("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / (1000L * 60L * 60L));
            json.addProperty("version", s.getBukkitVersion());
            json.addProperty("server", s.getName());

            try {
                String serverStats = db.getServerStatsJson();
                JsonElement el = JsonParser.parseString(serverStats);
                if (el != null && el.isJsonObject()) {
                    JsonObject so = el.getAsJsonObject();

                    JsonObject season = new JsonObject();
                    if (so.has("season_id") && !so.get("season_id").isJsonNull()) season.addProperty("id", so.get("season_id").getAsString());
                    if (so.has("season_players_total") && !so.get("season_players_total").isJsonNull()) season.addProperty("players_total", so.get("season_players_total").getAsInt());
                    if (so.has("season_peak_online") && !so.get("season_peak_online").isJsonNull()) season.addProperty("peak_online", so.get("season_peak_online").getAsInt());
                    json.add("season", season);

                    if (so.has("stats") && so.get("stats").isJsonObject()) json.add("stats", so.get("stats").getAsJsonObject());
                }
            } catch (Exception ignored) {
            }

            cachedServerInfoJson = db.toJsonString(json);
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
            if (id.isEmpty()) { send(ex, 200, db.getPlayersSummaryJson()); return; }

            String ip = clientIp(ex);
            String body = db.getPlayerJsonById(id, ip);
            send(ex, body.contains("\"error\":\"Player not found\"") ? 404 : 200, body);
            return;
        }

        send(ex, 200, db.getPlayersSummaryJson());
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
        if (query.isEmpty()) { send(ex, 400, "{\"error\":\"Bad request\"}"); return; }

        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            DatabaseManager.AdminResponse r = db.adminGetFlex(query);
            send(ex, r.status(), r.body());
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            if (adminToken == null || adminToken.isBlank()) { send(ex, 500, "{\"error\":\"Admin token is not configured\"}"); return; }
            String token = ex.getRequestHeaders().getFirst("token");
            if (token == null || !token.equals(adminToken)) { send(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }

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
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}