package com.leir4iks.cookiepl.modules.web;

import com.google.gson.JsonObject;
import com.leir4iks.cookiepl.CookiePl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.entity.Player;

import java.io.IOException;
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

    private WrappedTask serverInfoTask;
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
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            plugin.getFoliaLib().getScheduler().runNextTick(task -> updateServerInfoSync());
            this.serverInfoTask = plugin.getFoliaLib().getScheduler().runTimer(task -> updateServerInfoSync(), 20L * 10L, 20L * 10L);

            plugin.getLogManager().info("Web Server started on port " + port);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to start Web Server on port " + port);
            e.printStackTrace();
        }
    }

    public void stop() {
        if (serverInfoTask != null) serverInfoTask.cancel();
        if (server != null) {
            server.stop(0);
            plugin.getLogManager().info("Web Server stopped.");
        }
    }

    private void updateServerInfoSync() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("online", plugin.getServer().getOnlinePlayers().size());

            String playersList = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.joining(", "));
            json.addProperty("online_players", playersList);

            json.addProperty("max_players", plugin.getServer().getMaxPlayers());

            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeHours = uptimeMillis / (1000L * 60L * 60L);
            json.addProperty("uptime_hours", uptimeHours);

            json.addProperty("version", plugin.getServer().getBukkitVersion());
            json.addProperty("server", plugin.getServer().getName());

            this.cachedServerInfoJson = json.toString();
        } catch (Exception ignored) {
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
            sendResponse(exchange, 200, databaseManager.getDatabaseJson());
            return;
        }

        if (path.startsWith("/players/")) {
            String[] segments = path.split("/");
            if (segments.length > 2) {
                String slug = URLDecoder.decode(segments[2], StandardCharsets.UTF_8);
                String response = databaseManager.getPlayerJsonBySlug(slug);
                if (response.contains("\"error\"")) {
                    sendResponse(exchange, 404, response);
                } else {
                    sendResponse(exchange, 200, response);
                }
                return;
            }
        }

        sendResponse(exchange, 200, databaseManager.getDatabaseJson());
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
        if (!corsEnabled) return;
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
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
