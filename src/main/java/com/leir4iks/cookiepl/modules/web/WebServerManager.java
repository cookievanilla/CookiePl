package com.leir4iks.cookiepl.modules.web;

import com.leir4iks.cookiepl.CookiePl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
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
    private final DatabaseManager databaseManager;
    private HttpServer server;
    private final int port;
    private final boolean corsEnabled;
    private final String adminToken;

    private WrappedTask updateTask;
    private volatile String cachedServerInfoJson = "{}";

    public WebServerManager(CookiePl plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.port = plugin.getConfig().getInt("modules.web-server.port", 8080);
        this.corsEnabled = plugin.getConfig().getBoolean("modules.web-server.cors-enabled", true);
        this.adminToken = plugin.getConfig().getString("modules.web-server.admin-token", "");
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
            var json = databaseManager.newJsonObject();
            json.addProperty("online", plugin.getServer().getOnlinePlayers().size());

            String playersList = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(", "));
            json.addProperty("online_players", playersList);

            json.addProperty("max_players", plugin.getServer().getMaxPlayers());

            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeHours = uptimeMillis / (1000L * 60L * 60L);
            json.addProperty("uptime", uptimeHours);

            json.addProperty("version", plugin.getServer().getBukkitVersion());
            json.addProperty("server", plugin.getServer().getName());

            this.cachedServerInfoJson = databaseManager.toJsonString(json);
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
        String[] segments = path.split("/");
        if (segments.length < 4) {
            sendResponse(exchange, 400, "{\"error\":\"Bad request\"}");
            return;
        }

        String query = segments[3];

        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            DatabaseManager.AdminResponse r = databaseManager.adminGetFlex(query);
            sendResponse(exchange, r.status(), r.body());
            return;
        }

        if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            String token = exchange.getRequestHeaders().getFirst("token");
            if (adminToken != null && !adminToken.isBlank()) {
                if (token == null || !token.equals(adminToken)) {
                    sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
            } else {
                sendResponse(exchange, 500, "{\"error\":\"Admin token is not configured\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            DatabaseManager.AdminResponse r = databaseManager.adminPostFlex(query, body);
            sendResponse(exchange, r.status(), r.body());
            return;
        }

        sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
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
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization,token");
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}