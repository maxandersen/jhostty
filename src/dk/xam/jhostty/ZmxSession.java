package dk.xam.jhostty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ZmxSession(String name, int pid, int clients, String startDir, String cwd, String cmd, boolean ended, int exitCode) {

    public String displayLabel() {
        var status = ended ? " \u2718" : (clients > 0 ? " (" + clients + ")" : "");
        return friendlyName() + status;
    }

    public String friendlyName() {
        var displayName = name;
        if (isGeneratedName()) {
            var dir = cwd != null && !cwd.isBlank() ? cwd : startDir;
            if (dir != null && !dir.isBlank()) {
                var p = Path.of(dir);
                var home = Path.of(System.getProperty("user.home", ""));
                if (p.equals(home)) {
                    displayName = "~";
                } else if (p.startsWith(home) && p.getNameCount() > home.getNameCount()) {
                    var rel = home.relativize(p);
                    var count = rel.getNameCount();
                    displayName = count >= 2
                            ? rel.getName(count - 2) + "/" + rel.getName(count - 1)
                            : rel.getFileName().toString();
                } else {
                    displayName = p.getFileName().toString();
                }
            }
        }
        return displayName;
    }

    public boolean isGeneratedName() {
        return name.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*");
    }

    // --- Parsing ---

    public static List<ZmxSession> parseZmxList(String output) {
        if (output == null || output.isBlank()) return List.of();
        var result = new ArrayList<ZmxSession>();
        for (var line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("\u2192 ")) line = line.substring(2).trim();
            if (line.isBlank()) continue;
            var fields = new java.util.HashMap<String, String>();
            for (var part : line.split("\t")) {
                part = part.trim();
                var eq = part.indexOf('=');
                if (eq > 0) {
                    fields.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
            var name = fields.getOrDefault("name", "");
            if (name.isBlank()) continue;
            result.add(new ZmxSession(
                    name,
                    parseInt(fields.getOrDefault("pid", "0")),
                    parseInt(fields.getOrDefault("clients", "0")),
                    fields.getOrDefault("start_dir", ""),
                    fields.getOrDefault("cwd", ""),
                    fields.getOrDefault("cmd", ""),
                    fields.containsKey("ended"),
                    parseInt(fields.getOrDefault("exit_code", "0"))
            ));
        }
        return result;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException _) { return 0; }
    }
}
