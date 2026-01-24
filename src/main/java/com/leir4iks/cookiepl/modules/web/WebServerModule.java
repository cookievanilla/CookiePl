package com.leir4iks.cookiepl.modules.web;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;

public class WebServerModule implements IModule {

    private WebServerManager webServerManager;
    private DatabaseManager databaseManager;

    @Override
    public void enable(CookiePl plugin) {
        this.databaseManager = new DatabaseManager(plugin);
        this.databaseManager.startTask();
        this.webServerManager = new WebServerManager(plugin, databaseManager);
        this.webServerManager.start();
    }

    @Override
    public void disable(CookiePl plugin) {
        if (this.webServerManager != null) {
            this.webServerManager.stop();
        }
        if (this.databaseManager != null) {
            this.databaseManager.stopTask();
        }
        this.webServerManager = null;
        this.databaseManager = null;
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