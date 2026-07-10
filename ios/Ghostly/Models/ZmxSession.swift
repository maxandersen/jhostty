import Foundation

/// A persistent `zmx` session living on a remote host.
///
/// This is a faithful Swift port of `dk.xam.jhostty.ZmxSession` from the
/// desktop jhostty app, so the two clients agree on how `zmx list` output is
/// parsed and how sessions are named for display.
///
/// `zmx list` prints one session per line as tab-separated `key=value` pairs,
/// with the currently-attached session prefixed by `→ `. Example:
///
/// ```
/// → name=dotfiles	pid=4321	clients=1	start_dir=/home/max	cwd=/home/max/.config	cmd=zsh
///   name=b1f2c3d4-...	pid=4400	clients=0	start_dir=/srv	cwd=/srv	cmd=zsh	ended	exit_code=0
/// ```
struct ZmxSession: Identifiable, Hashable {
    let name: String
    let pid: Int
    let clients: Int
    let startDir: String
    let cwd: String
    let cmd: String
    let ended: Bool
    let exitCode: Int

    var id: String { name }

    /// Whether any client is currently attached to this session.
    var isAttached: Bool { clients > 0 }

    /// Short status suffix used in list rows, e.g. `dotfiles (1)` or `srv ✘`.
    var displayLabel: String {
        let status = ended ? " ✘" : (clients > 0 ? " (\(clients))" : "")
        return friendlyName + status
    }

    /// zmx auto-generates UUID-style names for unnamed sessions. When that
    /// happens we present the working directory instead, mirroring the desktop
    /// app's behaviour (last two path components, `~` for home).
    var friendlyName: String {
        guard isGeneratedName else { return name }
        let dir = !cwd.isEmpty ? cwd : startDir
        guard !dir.isEmpty else { return name }

        let home = NSHomeDirectory()
        let path = (dir as NSString).standardizingPath
        if path == home { return "~" }
        if !home.isEmpty, path.hasPrefix(home + "/") {
            let rel = String(path.dropFirst(home.count + 1))
            let parts = rel.split(separator: "/")
            if parts.count >= 2 {
                return "\(parts[parts.count - 2])/\(parts[parts.count - 1])"
            }
            return parts.last.map(String.init) ?? name
        }
        return (path as NSString).lastPathComponent
    }

    /// Matches the UUID embedded in zmx's auto-generated session names.
    var isGeneratedName: Bool {
        name.range(of: "[0-9a-f]{8}-[0-9a-f]{4}-", options: .regularExpression) != nil
    }

    // MARK: - Parsing

    /// Parse the raw stdout of `zmx list` into a list of sessions.
    static func parseList(_ output: String) -> [ZmxSession] {
        guard !output.isEmpty else { return [] }
        var result: [ZmxSession] = []

        for rawLine in output.split(whereSeparator: \.isNewline) {
            var line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("→ ") { line = String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces) }
            if line.isEmpty { continue }

            var fields: [String: String] = [:]
            for part in line.split(separator: "\t") {
                let piece = part.trimmingCharacters(in: .whitespaces)
                guard let eq = piece.firstIndex(of: "="), eq > piece.startIndex else { continue }
                let key = String(piece[piece.startIndex..<eq]).trimmingCharacters(in: .whitespaces)
                let value = String(piece[piece.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                fields[key] = value
            }

            let name = fields["name"] ?? ""
            if name.isEmpty { continue }

            result.append(
                ZmxSession(
                    name: name,
                    pid: Int(fields["pid"] ?? "0") ?? 0,
                    clients: Int(fields["clients"] ?? "0") ?? 0,
                    startDir: fields["start_dir"] ?? "",
                    cwd: fields["cwd"] ?? "",
                    cmd: fields["cmd"] ?? "",
                    ended: fields.keys.contains("ended"),
                    exitCode: Int(fields["exit_code"] ?? "0") ?? 0
                )
            )
        }
        return result
    }
}
