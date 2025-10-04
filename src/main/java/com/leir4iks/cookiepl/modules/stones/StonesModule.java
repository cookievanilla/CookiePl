package com.leir4iks.cookiepl.modules.stones;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.IModule;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.event.HandlerList;

public class StonesModule implements IModule {
    private StoneManager stoneManager;
    private StoneListener listener;
    private WrappedTask physicsEngineTask;

    @Override
    public void enable(CookiePl plugin) {
        this.stoneManager = new StoneManager(plugin, plugin.getLogManager());
        this.listener = new StoneListener(plugin, stoneManager);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        StonePhysicsEngine physicsEngine = new StonePhysicsEngine(stoneManager);
        this.physicsEngineTask = plugin.getFoliaLib().getScheduler().runTimer(physicsEngine, 0L, 1L);
    }

    @Override
    public void disable(CookiePl plugin) {
        if (physicsEngineTask != null && !physicsEngineTask.isCancelled()) {
            physicsEngineTask.cancel();
        }
        if (stoneManager != null) {
            stoneManager.cleanupAllStones();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    @Override
    public String getName() {
        return "ThrowableStones";
    }

    @Override
    public String getConfigKey() {
        return "throwable-stones";
    }
}