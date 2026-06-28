package com.leir4iks.cookiepl.modules.colourful;

import com.leir4iks.cookiepl.CookiePl;
import com.leir4iks.cookiepl.modules.web.DatabaseManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ColourCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "cookiepl.colour.use";

    private final CookiePl plugin;

    public ColourCommand(CookiePl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getString(
                    "modules.colourful.messages.players-only",
                    "&cЭту команду может использовать только игрок."
            )));
            return true;
        }

        if (!player.hasPermission(PERM)) {
            player.sendMessage(color(getString(
                    "modules.colourful.messages.no-permission",
                    "&cУ тебя нет права на изменение цвета."
            )));
            return true;
        }

        if (args.length == 0 || args.length > 3) {
            player.sendMessage(color("&cИспользование: /colour <цвет1> [цвет2] [цвет3]"));
            player.sendMessage(color("&7Пример: &f/colour gold"));
            player.sendMessage(color("&7Пример: &f/colour #FF0000 #0000FF"));
            player.sendMessage(color("&7Пример: &f/colour red yellow green"));
            return true;
        }

        String[] colours = Arrays.copyOf(args, args.length);
        for (String c : colours) {
            if (!ColourData.isValidColour(c)) {
                player.sendMessage(color(getString(
                        "modules.colourful.messages.invalid-colour",
                        "&cНеверный цвет. Используй HEX (#RRGGBB) или английское название."
                ) + " &7(" + c + ")"));
                return true;
            }
        }

        DatabaseManager db = plugin.getWebDatabaseManager();
        if (db == null) {
            player.sendMessage(color("&cСистема цветов временно недоступна."));
            return true;
        }

        String colourPart = ColourData.buildColourPart(colours);
        if (colourPart == null) {
            player.sendMessage(color(getString(
                    "modules.colourful.messages.invalid-colour",
                    "&cНеверный цвет. Используй HEX (#RRGGBB) или английское название."
            )));
            return true;
        }

        String existingRaw = db.getPlayerColour(player.getUniqueId());
        String newRaw = ColourData.setColourPart(existingRaw, colourPart);

        db.setPlayerColour(player.getUniqueId(), newRaw);

        String preview = ColourData.toMiniMessage(newRaw);
        player.sendMessage(color(getString(
                "modules.colourful.messages.colour-set",
                "&7Цвет установлен."
        ) + " &7Тег: &f" + preview));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (!player.hasPermission(PERM)) return List.of();

        List<String> suggestions = new ArrayList<>(ColourData.NAMED_COLOURS);
        suggestions.add("#FF0000");
        suggestions.add("#00FF00");
        suggestions.add("#0000FF");
        suggestions.add("#FFFFFF");

        String current = args.length > 0 ? args[args.length - 1] : "";
        List<String> result = new ArrayList<>();
        for (String s : suggestions) {
            if (s.toLowerCase().startsWith(current.toLowerCase())) {
                result.add(s);
            }
        }
        return result;
    }

    private String getString(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
