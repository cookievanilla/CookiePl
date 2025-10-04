package com.leir4iks.cookiepl.modules;

import com.leir4iks.cookiepl.CookiePl;

public interface IModule {
    void enable(CookiePl plugin);
    void disable(CookiePl plugin);
    String getName();
    String getConfigKey();
}