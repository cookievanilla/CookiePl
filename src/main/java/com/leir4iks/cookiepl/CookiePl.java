package com.leir4iks.cookiepl;

import com.leir4iks.cookiepl.commands.MainCommand;
import com.leir4iks.cookiepl.modules.IModule;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.FoliaLib;
import de.tr7zw.nbtapi.NBT;
import org.bukkit.plugin.java.JavaPlugin;
import org.reflections.Reflections;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CookiePl extends JavaPlugin {

    private final List<IModule> allModules = new ArrayList<>();
    private final List<IModule> enabledModules = new ArrayList<>();
    private LogManager logManager;
    private FoliaLib foliaLib;

    @Override
    public void onEnable() {
        if (!NBT.preloadApi()) {
            getLogger().warning("NBT-API wasn't initialized properly, disabling the plugin");
            getPluginLoader().disablePlugin(this);
            return;
        }
        this.foliaLib = new FoliaLib(this);
        saveDefaultConfig();
        this.logManager = new LogManager(this);

        scanAndRegisterModules();
        loadEnabledModules();

        Objects.requireNonNull(getCommand("cookiepl")).setExecutor(new MainCommand(this));

        logManager.info("CookiePl has been enabled with " + enabledModules.size() + " modules.");
    }

    @Override
    public void onDisable() {
        disableAllModules();
        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }
        if (logManager != null) {
            logManager.info("CookiePl has been disabled.");
            logManager.close();
        }
    }

    private void scanAndRegisterModules() {
        allModules.clear();
        String basePackage = "com.leir4iks.cookiepl.modules";
        Reflections reflections = new Reflections(basePackage);
        Set<Class<? extends IModule>> moduleClasses = reflections.getSubTypesOf(IModule.class);

        for (Class<? extends IModule> moduleClass : moduleClasses) {
            try {
                if (!moduleClass.isInterface() && !java.lang.reflect.Modifier.isAbstract(moduleClass.getModifiers())) {
                    IModule moduleInstance = moduleClass.getDeclaredConstructor().newInstance();
                    allModules.add(moduleInstance);
                }
            } catch (Exception e) {
                logManager.severe("Could not instantiate module: " + moduleClass.getName());
                e.printStackTrace();
            }
        }
    }

    private void loadEnabledModules() {
        for (IModule module : allModules) {
            if (getConfig().getBoolean("modules." + module.getConfigKey() + ".enabled", false)) {
                try {
                    module.enable(this);
                    enabledModules.add(module);
                    logManager.info("Module '" + module.getName() + "' enabled.");
                } catch (Exception e) {
                    logManager.severe("Failed to enable module '" + module.getName() + "'.");
                    e.printStackTrace();
                }
            }
        }
    }

    private void disableAllModules() {
        for (IModule module : enabledModules) {
            try {
                module.disable(this);
                logManager.info("Module '" + module.getName() + "' disabled.");
            } catch (Exception e) {
                logManager.severe("Failed to disable module '" + module.getName() + "'.");
                e.printStackTrace();
            }
        }
        enabledModules.clear();
    }

    public void reloadAllModules() {
        logManager.info("Reloading all modules...");
        disableAllModules();
        reloadConfig();
        this.logManager = new LogManager(this);
        scanAndRegisterModules();
        loadEnabledModules();
        logManager.info("Reload complete. " + enabledModules.size() + " modules are now active.");
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }
}```