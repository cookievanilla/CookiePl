package com.leir4iks.cookiepl.modules.updateserver;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

public class UpdateServerModule implements IModule {

    private CookiePl plugin;
    private HttpServer server;
    private HttpClient httpClient;

    private int port;
    private String apiKey;
    private String publicUrl;
    private String downloadsDir;
    private String updateJsonFile;
    private String updateRoute;

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newHttpClient();
        loadConfig();

        try {
            Files.createDirectories(Paths.get(plugin.getDataFolder().getAbsolutePath(), downloadsDir));
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(updateRoute, this::handleUpdate);
            server.createContext("/" + downloadsDir + "/", this::handleDownload);
            server.setExecutor(null);
            server.start();
            plugin.getLogManager().info("Update server started on port " + port);
        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to start update server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        if (server != null) {
            server.stop(0);
            plugin.getLogManager().info("Update server stopped.");
        }
    }

    private void loadConfig() {
        String configPrefix = "modules." + getConfigKey() + ".";
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.port = plugin.getConfig().getInt(configPrefix + "port", 20564);
        this.apiKey = plugin.getConfig().getString(configPrefix + "api-key", "DEFAULT_API_KEY_CHANGE_ME");
        this.publicUrl = plugin.getConfig().getString(configPrefix + "public-url", "http://127.0.0.1:" + port);
        this.downloadsDir = plugin.getConfig().getString(configPrefix + "downloads-dir", "downloads");
        this.updateJsonFile = plugin.getConfig().getString(configPrefix + "update-json-file", "update.json");
        this.updateRoute = plugin.getConfig().getString(configPrefix + "update-route", "/update/coreprotecttnt");
    }

    private void handleUpdate(HttpExchange exchange) throws IOException {
        try {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handlePostUpdate(exchange);
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGetUpdate(exchange);
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        } catch (Exception e) {
            plugin.getLogManager().severe("Error handling update request: " + e.getMessage());
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private void handleGetUpdate(HttpExchange exchange) throws IOException {
        File updateFile = new File(plugin.getDataFolder(), updateJsonFile);
        if (!updateFile.exists()) {
            sendResponse(exchange, 404, "{\"error\":\"Update info not available yet\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, updateFile.length());
        try (OutputStream os = exchange.getResponseBody()) {
            Files.copy(updateFile.toPath(), os);
        }
    }

    private void handlePostUpdate(HttpExchange exchange) throws IOException, InterruptedException {
        String requestApiKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
        if (requestApiKey == null || !requestApiKey.equals(this.apiKey)) {
            sendResponse(exchange, 401, "{\"error\":\"Invalid API Key\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String version = extractJsonField(body, "version");
        String changelogB64 = extractJsonField(body, "changelog_b64");
        String artifactUrl = extractJsonField(body, "artifact_url");
        String githubToken = extractJsonField(body, "github_token");

        if (version == null || changelogB64 == null || artifactUrl == null || githubToken == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing required fields\"}");
            return;
        }

        HttpRequest artifactListRequest = HttpRequest.newBuilder()
                .uri(URI.create(artifactUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "token " + githubToken)
                .build();

        HttpResponse<String> artifactListResponse = httpClient.send(artifactListRequest, HttpResponse.BodyHandlers.ofString());
        if (artifactListResponse.statusCode() != 200) {
            throw new IOException("Failed to fetch artifact list: " + artifactListResponse.body());
        }

        String downloadUrlZip = extractJsonField(artifactListResponse.body(), "archive_download_url");
        if (downloadUrlZip == null) {
            throw new IOException("No artifacts found in the specified run");
        }

        HttpRequest zipRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrlZip))
                .header("Authorization", "token " + githubToken)
                .build();

        HttpResponse<byte[]> zipResponse = httpClient.send(zipRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (zipResponse.statusCode() != 200) {
            throw new IOException("Failed to download artifact zip");
        }

        String filename = "CoreProtectTNT-" + version + ".jar";
        Path filepath = Paths.get(plugin.getDataFolder().getAbsolutePath(), downloadsDir, filename);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipResponse.body()))) {
            if (zis.getNextEntry() != null) {
                Files.copy(zis, filepath);
            } else {
                throw new IOException("ZIP file is empty");
            }
        }
        plugin.getLogManager().info("Saved new JAR: " + filename);

        String changelog = new String(Base64.getDecoder().decode(changelogB64), StandardCharsets.UTF_8);
        String finalDownloadUrl = String.format("%s/%s/%s", this.publicUrl, this.downloadsDir, filename);

        String updateJson = String.format(
                "{\n" +
                        "  \"latest_version\": \"%s\",\n" +
                        "  \"download_url\": \"%s\",\n" +
                        "  \"changelog\": [\n    \"%s\"\n  ]\n" +
                        "}",
                version, finalDownloadUrl, String.join("\",\n    \"", changelog.strip().split("\\r?\\n"))
        );

        Files.writeString(Paths.get(plugin.getDataFolder().getAbsolutePath(), updateJsonFile), updateJson, StandardCharsets.UTF_8);
        plugin.getLogManager().info("Updated " + updateJsonFile + " to version " + version);

        sendResponse(exchange, 200, "{\"success\":true, \"version\":\"" + version + "\"}");
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        String filename = requestPath.substring(("/" + downloadsDir + "/").length());

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid filename\"}");
            return;
        }

        File file = new File(plugin.getDataFolder(), Paths.get(downloadsDir, filename).toString());
        if (!file.exists() || !file.isFile()) {
            sendResponse(exchange, 404, "{\"error\":\"File not found\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
        exchange.sendResponseHeaders(200, file.length());
        try (OutputStream os = exchange.getResponseBody()) {
            Files.copy(file.toPath(), os);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private String extractJsonField(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public String getName() {
        return "UpdateServer";
    }

    @Override
    public String getConfigKey() {
        return "update-server";
    }
}