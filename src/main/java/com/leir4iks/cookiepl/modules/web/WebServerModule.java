package com.leir4iks.cookiepl.modules.web;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class WebServerModule implements IModule {

    private HttpServer server;
    private CookiePl plugin;

    @Override
    public void enable(CookiePl plugin) {
        this.plugin = plugin;
        int port = plugin.getConfig().getInt("modules.web-server.port", 8080);
        boolean cors = plugin.getConfig().getBoolean("modules.web-server.cors-enabled", true);

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                if (cors) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
                }

                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                String response = "hello";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            plugin.getLogManager().info("Web Server started on port " + port);

        } catch (IOException e) {
            plugin.getLogManager().severe("Failed to start Web Server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disable(CookiePl plugin) {
        if (server != null) {
            server.stop(0);
            plugin.getLogManager().info("Web Server stopped.");
        }
    }

    @Override
    public String getName() {
        return "WebServer";
    }

    @Override
    public String getConfigKey() {
        return "web-server";
    }
}