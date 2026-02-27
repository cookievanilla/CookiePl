package com.leir4iks.cookiepl.modules.web;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;

public class WebServerModule implements IModule {
    private WebServerManager webServerManager;
    private DatabaseManager databaseManager;

    @Override
    public void enable(CookiePl plugin) {
        databaseManager = new DatabaseManager(plugin);
        databaseManager.start();
        webServerManager = new WebServerManager(plugin, databaseManager);
        webServerManager.start();
    }

    @Override
    public void disable(CookiePl plugin) {
        if (webServerManager != null) webServerManager.stop();
        if (databaseManager != null) databaseManager.stop();
        webServerManager = null;
        databaseManager = null;
    }

    @Override public String getName() { return "WebServer"; }
    @Override public String getConfigKey() { return "web-server"; }
}