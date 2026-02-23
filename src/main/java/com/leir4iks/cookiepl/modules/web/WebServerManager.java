package com.leir4iks.cookiepl.modules.web;

import com.leir4iks.cookiepl.CookiePl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class WebServerManager {

    private final CookiePl plugin;
    private final DatabaseManager databaseManager;
    private HttpServer server;
    private final int port;
    private final boolean corsEnabled;

    private WrappedTask updateTask;
    private volatile String cachedServerInfoJson = "{}";

    public WebServerManager(CookiePl plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.port = plugin.getConfig().getInt("modules.web-server.port", 8080);
        this.corsEnabled = plugin.getConfig().getBoolean("modules.web-server.cors-enabled", true);
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/players", this::handlePlayersRequest);
            server.createContext("/serverinfo", this::handleServerInfoRequest);
            server.createContext("/admin/players", this::handleAdminPlayersRequest);

            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            server.setExecutor(Executors.newFixedThreadPool(threads));
            server.start();

            plugin.getFoliaLib().getScheduler().runAsync(task -> updateCache());
            this.updateTask = plugin.getFoliaLib().getScheduler().runTimerAsync(this::updateCache, 600L, 600L);

            plugin.getLogManager().info("Web Server started on port " + port);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to start Web Server on port " + port);
            e.printStackTrace();
        }
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        if (server != null) {
            server.stop(0);
            plugin.getLogManager().info("Web Server stopped.");
        }
    }

    private void updateCache() {
        try {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("online", plugin.getServer().getOnlinePlayers().size());

            String playersList = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(", "));
            json.addProperty("online_players", playersList);

            json.addProperty("max_players", plugin.getServer().getMaxPlayers());

            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeHours = uptimeMillis / (1000 * 60 * 60);
            json.addProperty("uptime", uptimeHours);

            json.addProperty("version", plugin.getServer().getBukkitVersion());
            json.addProperty("server", plugin.getServer().getName());

            this.cachedServerInfoJson = json.toString();
        } catch (Exception e) {
            plugin.getLogManager().warn("Failed to update web server cache: " + e.getMessage());
        }
    }

    private void handlePlayersRequest(HttpExchange exchange) throws IOException {
        handleCors(exchange);

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.equals("/players") || path.equals("/players/")) {
            sendResponse(exchange, 200, databaseManager.getPlayersSummaryJson());
            return;
        }

        if (path.startsWith("/players/")) {
            String[] segments = path.split("/");
            if (segments.length > 2) {
                String playerId = segments[2];
                String ip = getClientIp(exchange);

                String response = databaseManager.getPlayerJsonById(playerId, ip);

                if (response.contains("\"error\":\"Player not found\"")) {
                    sendResponse(exchange, 404, response);
                } else {
                    sendResponse(exchange, 200, response);
                }
                return;
            }
        }

        sendResponse(exchange, 200, databaseManager.getPlayersSummaryJson());
    }

    private void handleAdminPlayersRequest(HttpExchange exchange) throws IOException {
        handleCors(exchange);

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null) {
            sendResponse(exchange, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String[] segments = path.split("/");
        if (segments.length < 4) {
            sendResponse(exchange, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String id = segments[3];
        if (id == null || id.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String method = exchange.getRequestMethod();

        if (method.equalsIgnoreCase("GET")) {
            String flex = databaseManager.getFlexJsonById(id);
            if (flex == null || flex.isBlank()) {
                sendResponse(exchange, 404, "{\"error\":\"Player not found\"}");
                return;
            }
            sendResponse(exchange, 200, flex);
            return;
        }

        if (method.equalsIgnoreCase("POST")) {
            String body = readBody(exchange);
            String token = extractToken(exchange, body);
            String required = getAdminToken();

            if (required == null || required.isBlank() || token == null || token.isBlank() || !required.equals(token)) {
                sendResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }

            try {
                String updated = databaseManager.updateFlexJsonById(id, body);
                if (updated == null || updated.isBlank()) {
                    sendResponse(exchange, 404, "{\"error\":\"Player not found\"}");
                    return;
                }
                sendResponse(exchange, 200, updated);
            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
            }
            return;
        }

        sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
    }

    private String getAdminToken() {
        String t = plugin.getConfig().getString("modules.web-server.admin.token", null);
        if (t == null || t.isBlank()) t = plugin.getConfig().getString("modules.web-server.admin-token", null);
        if (t == null || t.isBlank()) t = plugin.getConfig().getString("modules.web-server.token", null);
        if (t == null || t.isBlank()) t = "CHANGE_ME";
        return t.trim();
    }

    private String extractToken(HttpExchange exchange, String body) {
        try {
            String t = exchange.getRequestHeaders().getFirst("token");
            if (t != null && !t.isBlank()) return t.trim();

            t = exchange.getRequestHeaders().getFirst("Token");
            if (t != null && !t.isBlank()) return t.trim();

            t = exchange.getRequestHeaders().getFirst("X-Token");
            if (t != null && !t.isBlank()) return t.trim();

            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && !auth.isBlank()) {
                String a = auth.trim();
                if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    String b = a.substring(7).trim();
                    if (!b.isBlank()) return b;
                }
            }

            String q = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : null;
            if (q != null && !q.isBlank()) {
                String token = getQueryParam(q, "token");
                if (token != null && !token.isBlank()) return token.trim();
            }

            if (body != null && !body.isBlank()) {
                try {
                    com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(body.trim());
                    if (el.isJsonObject()) {
                        com.google.gson.JsonObject obj = el.getAsJsonObject();
                        if (obj.has("token") && !obj.get("token").isJsonNull()) {
                            String bt = obj.get("token").getAsString();
                            if (bt != null && !bt.isBlank()) return bt.trim();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) return null;
        String[] parts = rawQuery.split("&");
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            int idx = p.indexOf('=');
            String k = idx >= 0 ? p.substring(0, idx) : p;
            String v = idx >= 0 ? p.substring(idx + 1) : "";
            try {
                String kk = URLDecoder.decode(k, StandardCharsets.UTF_8);
                if (!key.equals(kk)) continue;
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            if (is == null) return "";
            byte[] data = is.readAllBytes();
            if (data == null || data.length == 0) return "";
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String getClientIp(HttpExchange exchange) {
        try {
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                if (parts.length > 0) {
                    String ip = parts[0].trim();
                    if (!ip.isBlank()) return ip;
                }
            }

            String xri = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (xri != null && !xri.isBlank()) return xri.trim();

            if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
                return exchange.getRemoteAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handleServerInfoRequest(HttpExchange exchange) throws IOException {
        handleCors(exchange);

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        sendResponse(exchange, 200, cachedServerInfoJson);
    }

    private void handleCors(HttpExchange exchange) {
        if (corsEnabled) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization,token,Token,X-Token");
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        if (response == null) response = "";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}