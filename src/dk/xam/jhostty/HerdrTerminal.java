package dk.xam.jhostty;

import dk.xam.jherdr.Herdr;
import dk.xam.jherdr.EventFilter;
import dk.xam.jherdr.EventSubscription;
import dk.xam.jherdr.api.ids.PaneId;
import dk.xam.jherdr.api.params.*;
import io.github.vlaaad.ghosttyfx.Terminal;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * A Terminal implementation that bridges a herdr pane to ghosttyfx.
 * Output: polls pane.read(source=VISIBLE, format=ANSI) and streams ANSI to TerminalView.
 * Input: forwards keystrokes to herdr via pane.send_text.
 */
final class HerdrTerminal implements Terminal {

    private final PaneId paneId;
    private final PipedOutputStream outputWriter;
    private final PipedInputStream outputReader;
    private final Thread poller;
    private volatile boolean closed;

    HerdrTerminal(PaneId paneId) throws IOException {
        this.paneId = paneId;
        System.err.println("[jhostty] HerdrTerminal created for pane: " + paneId);
        this.outputWriter = new PipedOutputStream();
        this.outputReader = new PipedInputStream(outputWriter, 64 * 1024);

        // Poll herdr for visible screen content
        this.poller = Thread.ofVirtual().name("herdr-read-" + paneId).start(() -> {
            // Subscribe to output changes, refresh full VISIBLE screen on each event
            try {
                var h = Herdr.connect();
                // Initial full screen
                refreshVisible(h);
                // Subscribe: any output on this pane triggers an event
                var filter = EventFilter.of(new Subscription.PaneOutputMatchedSub(
                        50L, new OutputMatch.RegexMatch("[\\s\\S]"), paneId, ReadSource.RECENT, null));
                var sub = h.events().subscribe(filter);
                sub.onEvent(_ -> {
                    if (closed) return;
                    try (var h2 = Herdr.connect()) { refreshVisible(h2); }
                    catch (Exception _) {}
                });
                // Keep alive until closed
                while (!closed) { try { Thread.sleep(1000); } catch (InterruptedException _) { break; } }
                sub.close();
                h.close();
            } catch (Exception _) {
                // Fallback to polling if subscription fails
                while (!closed) {
                    try (var h = Herdr.connect()) { refreshVisible(h); }
                    catch (Exception _2) { if (closed) break; }
                    try { Thread.sleep(100); } catch (InterruptedException _2) { break; }
                }
            }
        });
    }

    private void refreshVisible(Herdr h) {
        try {
            var node = h.raw("pane.read", dk.xam.jherdr.protocol.JherdrJson.MAPPER
                    .valueToTree(new PaneReadParams(ReadFormat.ANSI, 200L, paneId, ReadSource.VISIBLE, false)));
            var text = node.path("read").path("text").asText("");
            if (!text.isEmpty()) {
                outputWriter.write(("\033[2J\033[H" + text).getBytes(StandardCharsets.UTF_8));
                outputWriter.flush();
            }
        } catch (Exception _) {}
    }

    @Override
    public InputStream output() { return outputReader; }

    @Override
    public OutputStream input() {
        return new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

            @Override public void write(byte[] b, int off, int len) {
                if (closed || len == 0) return;
                var text = new String(b, off, len, StandardCharsets.UTF_8);
                if (text.isEmpty()) return;
                Thread.ofVirtual().name("herdr-send").start(() -> {
                    try (var h = Herdr.connect()) {
                        h.api().pane().sendText(new PaneSendTextParams(paneId, text));
                    } catch (Exception e) {
                        System.err.println("[jhostty] herdr send failed for " + paneId + ": " + e.getMessage());
                    }
                });
            }
        };
    }

    @Override
    public void resize(int columns, int rows) {
        // ponytail: herdr manages its own terminal sizes, no-op for now
    }

    @Override
    public void close() {
        closed = true;
        poller.interrupt();
        try { outputWriter.close(); } catch (IOException _) {}
        try { outputReader.close(); } catch (IOException _) {}
    }
}
