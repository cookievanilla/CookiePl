package com.leir4iks.cookiepl;

import com.leir4iks.cookiepl.commands.MainCommand;
import com.leir4iks.cookiepl.config.ConfigManager;
import com.leir4iks.cookiepl.modules.IModule;
import com.leir4iks.cookiepl.permissions.PermissionRegistrar;
import com.leir4iks.cookiepl.modules.tags.TagsManager;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import com.leir4iks.cookiepl.util.LogManager;
import com.tcoded.folialib.FoliaLib;
import org.bukkit.plugin.java.JavaPlugin;
import org.reflections.Reflections;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CookiePl extends JavaPlugin {

    private final List<IModule> allModules = new ArrayList<>();
    private final List<IModule> enabledModules = new ArrayList<>();
    private LogManager logManager;
    private FoliaLib foliaLib;
    private ConfigManager configManager;
    private PermissionRegistrar permissionRegistrar;
    private volatile DatabaseManager webDatabaseManager;
    private volatile TagsManager tagsManager;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);
        this.configManager = new ConfigManager(this);
        this.configManager.load();
        this.logManager = new LogManager(this);

        startupDebug();

        scanAndRegisterModules();
        loadEnabledModules();

        Objects.requireNonNull(getCommand("cookiepl")).setExecutor(new MainCommand(this));

        this.permissionRegistrar = new PermissionRegistrar(this);
        getServer().getPluginManager().registerEvents(this.permissionRegistrar, this);
        this.permissionRegistrar.apply();

        logInfo("CookiePl has been enabled with " + enabledModules.size() + " modules.");
    }

    @Override
    public void onDisable() {
        disableAllModules();
        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }
        webDatabaseManager = null;
        tagsManager = null;
        if (logManager != null) {
            logInfo("CookiePl has been disabled.");
            logManager.close();
        }
    }

    private void startupDebug() {
        try {
            logInfo("[DBG] java=" + System.getProperty("java.version") + " vendor=" + System.getProperty("java.vendor"));
        } catch (Exception ignored) {
        }

        try {
            File df = getDataFolder();
            File pluginsDir = df != null ? df.getParentFile() : null;
            String wd = new File(".").getAbsolutePath();

            logInfo("[DBG] dataFolder=" + (df == null ? "null" : safePath(df)));
            logInfo("[DBG] pluginsDir=" + (pluginsDir == null ? "null" : safePath(pluginsDir)));
            logInfo("[DBG] workDir=" + wd);

            File lbDir = pluginsDir == null ? new File("plugins", "LiteBans") : new File(pluginsDir, "LiteBans");
            logInfo("[DBG] litebansDir=" + safePath(lbDir) + " exists=" + lbDir.exists() + " isDir=" + lbDir.isDirectory());

            File lbDb = new File(lbDir, "litebans.mv.db");
            logInfo("[DBG] litebansDb=" + safePath(lbDb) + " exists=" + lbDb.exists() + " size=" + (lbDb.exists() ? lbDb.length() : -1) + " lm=" + (lbDb.exists() ? lbDb.lastModified() : -1));

            File lbLib = new File(lbDir, "lib");
            logInfo("[DBG] litebansLib=" + safePath(lbLib) + " exists=" + lbLib.exists() + " isDir=" + lbLib.isDirectory());

            File h2 = findH2Jar(lbLib);
            logInfo("[DBG] litebansH2Jar=" + (h2 == null ? "null" : safePath(h2)) + " exists=" + (h2 != null && h2.exists()) + " size=" + (h2 != null && h2.exists() ? h2.length() : -1));
        } catch (Exception e) {
            logWarn("[DBG] startupDebug failed: " + e.getMessage());
        }
    }

    private File findH2Jar(File libDir) {
        if (libDir == null || !libDir.exists() || !libDir.isDirectory()) return null;
        File best = null;
        File[] files = libDir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            if (n == null) continue;
            String nl = n.toLowerCase();
            if (!nl.startsWith("h2-") || !nl.endsWith(".jar")) continue;
            if (best == null || n.compareToIgnoreCase(best.getName()) > 0) best = f;
        }
        return best;
    }

    private String safePath(File f) {
        if (f == null) return "null";
        try {
            return f.getCanonicalPath();
        } catch (Exception ignored) {
            try {
                return f.getAbsolutePath();
            } catch (Exception ignored2) {
                return f.getPath();
            }
        }
    }

    private void logInfo(String msg) {
        try {
            getLogger().info(msg);
        } catch (Exception ignored) {
        }
        try {
            if (logManager != null) logManager.info(msg);
        } catch (Exception ignored) {
        }
    }

    private void logWarn(String msg) {
        try {
            getLogger().warning(msg);
        } catch (Exception ignored) {
        }
        try {
            if (logManager != null) logManager.warn(msg);
        } catch (Exception ignored) {
        }
    }

    private void logSevere(String msg) {
        try {
            getLogger().severe(msg);
        } catch (Exception ignored) {
        }
        try {
            if (logManager != null) logManager.severe(msg);
        } catch (Exception ignored) {
        }
    }

    private void scanAndRegisterModules() {
        allModules.clear();
        String basePackage = "com.leir4iks.cookiepl.modules";
        Reflections reflections = new Reflections(basePackage);
        Set<Class<? extends IModule>> moduleClasses = reflections.getSubTypesOf(IModule.class);

        logInfo("[DBG] Found module classes: " + (moduleClasses == null ? 0 : moduleClasses.size()));

        if (moduleClasses != null) {
            for (Class<? extends IModule> mc : moduleClasses) {
                try {
                    logInfo("[DBG] moduleClass=" + (mc == null ? "null" : mc.getName()));
                } catch (Exception ignored) {
                }
            }
        }

        for (Class<? extends IModule> moduleClass : moduleClasses) {
            try {
                if (!moduleClass.isInterface() && !java.lang.reflect.Modifier.isAbstract(moduleClass.getModifiers())) {
                    IModule moduleInstance = moduleClass.getDeclaredConstructor().newInstance();
                    allModules.add(moduleInstance);
                }
            } catch (Exception e) {
                logSevere("Could not instantiate module: " + moduleClass.getName());
                try {
                    e.printStackTrace();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void loadEnabledModules() {
        for (IModule module : allModules) {
            boolean shouldEnable = false;
            try {
                shouldEnable = getConfig().getBoolean("modules." + module.getConfigKey() + ".enabled", false);
            } catch (Exception ignored) {
            }

            logInfo("[DBG] module=" + module.getName() + " key=" + module.getConfigKey() + " enabled=" + shouldEnable);

            if (shouldEnable) {
                try {
                    module.enable(this);
                    enabledModules.add(module);
                    logInfo("Module '" + module.getName() + "' enabled.");
                } catch (Exception e) {
                    logSevere("Failed to enable module '" + module.getName() + "'.");
                    try {
                        e.printStackTrace();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void disableAllModules() {
        for (IModule module : enabledModules) {
            try {
                module.disable(this);
                logInfo("Module '" + module.getName() + "' disabled.");
            } catch (Exception e) {
                logSevere("Failed to disable module '" + module.getName() + "'.");
                try {
                    e.printStackTrace();
                } catch (Exception ignored) {
                }
            }
        }
        enabledModules.clear();
    }

    public void reloadAllModules() {
        logInfo("Reloading all modules...");

        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }

        disableAllModules();

        configManager.load();

        this.logManager = new LogManager(this);
        startupDebug();
        scanAndRegisterModules();
        loadEnabledModules();
        if (permissionRegistrar != null) {
            permissionRegistrar.apply();
        }
        logInfo("Reload complete. " + enabledModules.size() + " modules are now active.");
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    public DatabaseManager getWebDatabaseManager() {
        return webDatabaseManager;
    }

    public void setWebDatabaseManager(DatabaseManager manager) {
        this.webDatabaseManager = manager;
    }

    public TagsManager getTagsManager() {
        return tagsManager;
    }

    public void setTagsManager(TagsManager manager) {
        this.tagsManager = manager;
    }
}