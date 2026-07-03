package dk.xam.themes;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility class for normalizing terminal color values to #RRGGBB hex format.
 */
public final class ColorUtil {
    private ColorUtil() {}

    private static final Pattern HEX3 = Pattern.compile("^#?([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$");
    private static final Pattern HEX6 = Pattern.compile("^#?([0-9a-fA-F]{6})$");
    private static final Pattern HEX8 = Pattern.compile("^#?([0-9a-fA-F]{6})[0-9a-fA-F]{2}$");
    private static final Pattern RGB_FUNC = Pattern.compile(
            "^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*[\\d.]+)?\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Normalize a color string to uppercase #RRGGBB format.
     * Accepts: #RGB, #RRGGBB, #RRGGBBAA, RRGGBB, rgb(r,g,b), rgba(r,g,b,a).
     * Alpha channels are stripped.
     *
     * @throws IllegalArgumentException if the color cannot be parsed
     */
    public static String normalize(String color) {
        if (color == null) return null;
        color = color.trim();
        if (color.isEmpty()) return null;

        var m6 = HEX6.matcher(color);
        if (m6.matches()) return "#" + m6.group(1).toUpperCase(Locale.ROOT);

        var m8 = HEX8.matcher(color);
        if (m8.matches()) return "#" + m8.group(1).toUpperCase(Locale.ROOT);

        var m3 = HEX3.matcher(color);
        if (m3.matches()) {
            String r = m3.group(1), g = m3.group(2), b = m3.group(3);
            return "#" + (r + r + g + g + b + b).toUpperCase(Locale.ROOT);
        }

        var mRgb = RGB_FUNC.matcher(color);
        if (mRgb.matches()) {
            int r = Integer.parseInt(mRgb.group(1));
            int g = Integer.parseInt(mRgb.group(2));
            int b = Integer.parseInt(mRgb.group(3));
            if (r > 255 || g > 255 || b > 255) {
                throw new IllegalArgumentException("RGB values out of range: " + color);
            }
            return String.format("#%02X%02X%02X", r, g, b);
        }

        throw new IllegalArgumentException("Cannot parse color: " + color);
    }

    /**
     * Normalize a name for case-insensitive, whitespace/punctuation-tolerant lookup.
     */
    public static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_-]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Check if a string looks like a valid color that {@link #normalize} can parse.
     */
    public static boolean isValidColor(String color) {
        if (color == null) return false;
        try {
            normalize(color);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
