package dk.xam.jhostty;

import java.util.ArrayList;
import java.util.List;

/**
 * Encoding/decoding logic for layout persistence.
 * Pure functions — no JavaFX dependencies, fully testable.
 */
public final class LayoutCodec {
    private LayoutCodec() {}

    public static String encodeCommand(List<String> cmd) {
        return String.join(" ", cmd)
                .replace("\\", "\\\\")
                .replace("|", "\\p")
                .replace(",", "\\c")
                .replace(";", "\\s")
                .replace("[", "\\o")
                .replace("]", "\\e");
    }

    public static String decodeCommand(String encoded) {
        // Must decode \\ first (via sentinel) to avoid false matches like \c in C:\cmd
        return encoded
                .replace("\\\\", "\u0000")  // \\ → sentinel
                .replace("\\e", "]")
                .replace("\\o", "[")
                .replace("\\s", ";")
                .replace("\\c", ",")
                .replace("\\p", "|")
                .replace("\u0000", "\\");     // sentinel → \
    }

    public static List<String> parseCommand(String encoded, List<String> defaultShell) {
        var decoded = decodeCommand(encoded);
        return decoded.isBlank() ? defaultShell : List.of(decoded.split(" "));
    }

    /** Split on commas that are not inside brackets. */
    public static List<String> splitTabDescs(String windowDesc) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < windowDesc.length(); i++) {
            var ch = windowDesc.charAt(i);
            if (ch == '[' || ch == '{') depth++;
            else if (ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) {
                result.add(windowDesc.substring(start, i));
                start = i + 1;
            }
        }
        result.add(windowDesc.substring(start));
        return result;
    }

    /** Split on | that are not escaped. */
    public static List<String> splitCommands(String cmdsPart) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < cmdsPart.length(); i++) {
            if (cmdsPart.charAt(i) == '|' && (i == 0 || cmdsPart.charAt(i - 1) != '\\')) {
                result.add(cmdsPart.substring(start, i));
                start = i + 1;
            }
        }
        result.add(cmdsPart.substring(start));
        return result;
    }
}
