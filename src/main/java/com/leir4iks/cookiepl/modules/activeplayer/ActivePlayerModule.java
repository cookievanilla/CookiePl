package com.leir4iks.cookiepl.modules.activeplayer;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;

public class ActivePlayerModule implements IModule {
    private ActivePlayerManager manager;

    @Override
    public void enable(CookiePl plugin) {
        manager = new ActivePlayerManager(plugin, getConfigKey());
        manager.start();
    }

    @Override
    public void disable(CookiePl plugin) {
        if (manager != null) manager.shutdown();
        manager = null;
    }

    @Override
    public String getName() {
        return "ActivePlayer";
    }

    @Override
    public String getConfigKey() {
        return "active-player";
    }
}
