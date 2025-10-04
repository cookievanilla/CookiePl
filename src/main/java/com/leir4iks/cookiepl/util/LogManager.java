package com.leir4iks.cookiepl.util;

import com.leir4iks.cookiepl.CookiePl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

public class LogManager {

    private final CookiePl plugin;
    private final boolean enabled;
    private final Level logLevel;
    private PrintWriter writer;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public LogManager(CookiePl plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("logging.enabled", true);
        Level parsedLevel;
        try {
            parsedLevel = Level.parse(plugin.getConfig().getString("logging.log-level", "INFO").toUpperCase());
        } catch (IllegalArgumentException e) {
            parsedLevel = Level.INFO;
            plugin.getLogger().warning("Invalid log-level in config.yml. Defaulting to INFO.");
        }
        this.logLevel = parsedLevel;

        if (this.enabled) {
            setupFile();
        }
    }

    private void setupFile() {
        try {
            File logsDir = new File(plugin.getDataFolder(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File logFile = new File(logsDir, date + ".log");
            this.writer = new PrintWriter(new FileWriter(logFile, true), true);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not initialize LogManager file writer.");
            e.printStackTrace();
        }
    }

    private synchronized void log(Level level, String message) {
        if (!enabled || writer == null || level.intValue() < this.logLevel.intValue()) {
            return;
        }
        String formattedMessage = String.format("[%s %s]: %s", timeFormat.format(new Date()), level.getName(), message);
        writer.println(formattedMessage);
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    public void warn(String message) {
        log(Level.WARNING, message);
    }

    public void severe(String message) {
        log(Level.SEVERE, message);
    }

    public void debug(String message) {
        log(Level.FINE, message);
    }

    public synchronized void close() {
        if (writer != null) {
            writer.close();
        }
    }
}