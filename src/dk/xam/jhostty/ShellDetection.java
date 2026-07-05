package dk.xam.jhostty;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ShellDetection {
    private ShellDetection() {}

    public record ShellOption(String label, List<String> command) {
        @Override public String toString() { return label; }
    }

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    public static final boolean IS_MAC = OS_NAME.contains("mac");
    public static final boolean IS_WINDOWS = OS_NAME.contains("win");

    public static List<ShellOption> detectTerminals() {
        return IS_WINDOWS ? detectWindowsShells() : detectUnixShells();
    }

    private static List<ShellOption> detectWindowsShells() {
        var result = new ArrayList<ShellOption>();
        var seen = new LinkedHashSet<Path>();
        addShell(result, seen, "PowerShell", "pwsh.exe");
        addShell(result, seen, "Windows PowerShell", "powershell.exe");
        addShell(result, seen, "Command Prompt", "cmd.exe");
        return result;
    }

    private static List<ShellOption> detectUnixShells() {
        var result = new ArrayList<ShellOption>();
        var seen = new LinkedHashSet<Path>();
        var shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            addShell(result, seen, Path.of(shell).getFileName().toString(), shell);
        }
        addShell(result, seen, "bash", "bash");
        addShell(result, seen, "zsh", "zsh");
        addShell(result, seen, "fish", "fish");
        addShell(result, seen, "sh", "sh");
        return result;
    }

    private static void addShell(List<ShellOption> list, LinkedHashSet<Path> seen, String label, String cmd) {
        var path = resolveExecutable(cmd);
        if (path != null && seen.add(path)) {
            list.add(new ShellOption(label, List.of(path.toString())));
        }
    }

    public static Path resolveExecutable(String candidate) {
        var p = Path.of(candidate);
        if (p.isAbsolute()) return Files.isRegularFile(p) ? p : null;
        for (var entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            var resolved = Path.of(entry).resolve(candidate);
            if (Files.isExecutable(resolved)) return resolved.toAbsolutePath().normalize();
        }
        return null;
    }
}
