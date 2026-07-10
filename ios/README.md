# Ghostly — a zmx-aware SSH terminal for iOS

Part of the [jhostty](../README.md) family. Where desktop **jhostty** is a
Ghostty-powered terminal you run with JBang, **Ghostly** is its iOS sibling: a
SwiftUI app that SSHes into your machines, opens a terminal, and is
**[zmx](https://zmx.sh) session-aware** — so a dropped connection doesn't lose
your work.

> Status: **early scaffold.** The architecture, SSH, zmx logic and UI are all
> here and structured to build with Xcode on a Mac. A few native integration
> seams are called out below — this repo can't be compiled in the Linux CI
> environment it was authored in, so treat the first `xcodebuild` as the real
> smoke test.

## What it does

- **Manage SSH hosts** — nickname, host, port, user, password *or* private key.
  Secrets go in the **Keychain**, never in plaintext or `UserDefaults`.
- **Open a terminal** — SwiftTerm renders an xterm-256 grid with the on-screen
  keyboard, selection, copy, and links.
- **zmx-awareness (the headline feature)** — on connect, Ghostly probes the host
  for zmx and lists its persistent sessions. You can:
  - **Re-attach** to a running session (zmx restores its state),
  - start a **new** named session, or
  - fall back to a **plain login shell**.
  A **Detach** action (Ctrl+`\`) leaves the session running on the host so you
  can pick it up later — the whole point of zmx over plain SSH.
- **Ghostty themes** — the same JSON theme format as desktop jhostty; a curated
  set is bundled (`Resources/themes.json`), swappable per-host.

## How zmx-awareness works

zmx keeps a daemon-per-session on the remote, reachable over a Unix socket, so
SSH drops don't kill your shells. Ghostly is a first-class zmx client:

| Step | What Ghostly runs on the host |
|------|-------------------------------|
| Detect | `command -v zmx` then `zmx list` |
| Attach / create | `zmx attach <name>` (an upsert) exec'd in a PTY shell |
| New session | `zmx attach ios-<random>` |
| Detach | sends `Ctrl+\` — session keeps running |
| Kill | `zmx kill <name> --force` |

`zmx list` output is parsed by `ZmxSession.parseList` — a faithful Swift port of
`dk.xam.jhostty.ZmxSession` from the desktop app, so both clients agree on
session naming and display.

## Architecture

```
Ghostly/
├── App/            GhostlyApp (entry), RootView
├── Models/         Host, AuthMethod, ZmxSession (parser), Theme
├── Storage/        HostStore (JSON), ThemeStore, Keychain (secrets)
├── SSH/            SSHConnection (protocol) + swift-nio-ssh implementation
│                     ├─ NIOSSHConnection   connect / auth / exec channel
│                     └─ NIOSSHShell        interactive PTY shell channel
├── Zmx/            ZmxController            discover / attach / kill commands
├── Terminal/       TerminalEngine (protocol) + SwiftTermEngine
│                     TerminalSession        orchestrates SSH ⇄ engine ⇄ zmx
└── UI/             HostList, HostEdit, TerminalScreen, ZmxSessionPicker, Settings
```

The layers are deliberately decoupled:

- **`TerminalEngine`** is a protocol. `SwiftTermEngine` implements it today; a
  **libghostty** engine (Ghostty's real renderer via an XCFramework) can be
  dropped in later without touching SSH or zmx code.
- **`SSHConnection`** is a protocol, so the transport can be swapped (e.g. Mosh,
  or a libssh2 backend) independently of the UI.

## Build & run

Requires a Mac with Xcode 15+ and [XcodeGen](https://github.com/yonwoo9/XcodeGen)
(`brew install xcodegen`).

```bash
cd ios
xcodegen generate      # creates Ghostly.xcodeproj from project.yml
open Ghostly.xcodeproj  # then pick a Simulator and Run
```

Swift Package dependencies (resolved automatically by Xcode):

| Package | Role |
|---------|------|
| [SwiftTerm](https://github.com/migueldeicaza/SwiftTerm) | Terminal emulator / view |
| [swift-nio-ssh](https://github.com/apple/swift-nio-ssh) | SSH transport, auth, channels |
| [swift-nio](https://github.com/apple/swift-nio) | Event loop / byte buffers |
| [swift-crypto](https://github.com/apple/swift-crypto) | Key parsing |

Run the unit tests (zmx parsing / command building) with **⌘U**.

## Known seams / TODO

These are the places most likely to need a first-build tweak or follow-up:

1. **Host-key verification** — `AcceptAllHostKeys` in `NIOSSHConnection.swift`
   trusts any host key so the flow works out of the box. Replace with
   trust-on-first-use pinning before real use. *(Clearly marked in code.)*
2. **Private-key auth** — currently accepts ECDSA **P-256** PEM keys via
   swift-crypto. Ed25519 / RSA OpenSSH keys need extra parsing. Password auth is
   complete.
3. **libghostty engine** — the `TerminalEngine` seam is ready; vendoring a
   libghostty XCFramework and writing `GhosttyEngine` is the path to Ghostty's
   real renderer on device.
4. **swift-nio-ssh API pinning** — pinned to `0.9.0+`; if a newer major shifts
   the child-channel or auth-delegate API, adjust `NIOSSHConnection` /
   `NIOSSHShell` accordingly.

## License

MIT, matching the rest of jhostty.
