package com.leir4iks.cookiepl.modules.colourful;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ColourData {

    private static final Pattern HEX_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    static final Set<String> NAMED_COLOURS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue",
            "green", "aqua", "red", "light_purple", "yellow", "white"
    );

    static final Set<String> VALID_FLAGS = Set.of("bold", "italic", "underline");

    private ColourData() {}

    public static boolean isHex(String s) {
        return s != null && HEX_PATTERN.matcher(s).matches();
    }

    public static boolean isNamed(String s) {
        return s != null && NAMED_COLOURS.contains(s.toLowerCase());
    }

    public static boolean isValidColour(String s) {
        return isHex(s) || isNamed(s);
    }

    public static String buildColourPart(String... args) {
        if (args == null || args.length == 0) return null;
        for (String arg : args) {
            if (!isValidColour(arg)) return null;
        }
        String[] norm = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            norm[i] = isHex(args[i]) ? args[i] : args[i].toLowerCase();
        }
        if (norm.length == 1) {
            return norm[0];
        }
        return "gradient:" + String.join(":", norm);
    }

    public static String encode(String colourPart, List<String> flags) {
        String cp = colourPart == null ? "" : colourPart.trim();
        String fl = flags == null || flags.isEmpty() ? "" : String.join(",", flags);
        if (cp.isEmpty() && fl.isEmpty()) return "";
        if (fl.isEmpty()) return cp;
        if (cp.isEmpty()) return "|" + fl;
        return cp + "|" + fl;
    }

    public static String parseColourPart(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int sep = raw.indexOf('|');
        if (sep < 0) return raw.trim();
        return raw.substring(0, sep).trim();
    }

    public static List<String> parseFlags(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        int sep = raw.indexOf('|');
        if (sep < 0) return Collections.emptyList();
        String flagStr = raw.substring(sep + 1).trim();
        if (flagStr.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String f : flagStr.split(",")) {
            String trimmed = f.trim().toLowerCase();
            if (VALID_FLAGS.contains(trimmed)) result.add(trimmed);
        }
        return Collections.unmodifiableList(result);
    }

    public static String toggleFlag(String raw, String flag) {
        if (!VALID_FLAGS.contains(flag)) return raw;
        String colourPart = parseColourPart(raw);
        List<String> flags = new ArrayList<>(parseFlags(raw));
        if (flags.contains(flag)) {
            flags.remove(flag);
        } else {
            flags.add(flag);
        }
        return encode(colourPart, flags);
    }

    public static String setColourPart(String raw, String newColourPart) {
        List<String> flags = parseFlags(raw);
        return encode(newColourPart, flags);
    }

    public static String toMiniMessageOpen(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String colourPart = parseColourPart(raw);
        List<String> flags = parseFlags(raw);
        if (colourPart.isEmpty() && flags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!colourPart.isEmpty()) {
            sb.append("<").append(colourPart).append(">");
        }
        for (String flag : flags) {
            sb.append("<").append(flag).append(">");
        }
        return sb.toString();
    }

    public static String toMiniMessageClose(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String colourPart = parseColourPart(raw);
        List<String> flags = parseFlags(raw);
        if (colourPart.isEmpty() && flags.isEmpty()) return "";
        return "<reset>";
    }

    public static String toMiniMessage(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        return toMiniMessageOpen(raw);
    }
}
