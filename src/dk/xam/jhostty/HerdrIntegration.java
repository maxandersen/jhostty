package dk.xam.jhostty;

import dk.xam.jherdr.Herdr;
import dk.xam.jherdr.api.params.*;
import dk.xam.jherdr.api.results.*;
import javafx.application.Platform;

import java.util.List;
import java.util.function.Consumer;

/**
 * Connects to a running herdr instance and polls workspace/pane state.
 * Runs on a daemon thread; pushes updates to the FX thread.
 */
final class HerdrIntegration {

    record HerdrState(
        boolean connected,
        String serverVersion,
        List<WorkspaceInfo> workspaces,
        List<PaneInfo> panes
    ) {
        static final HerdrState DISCONNECTED = new HerdrState(false, null, List.of(), List.of());
    }

    private volatile boolean running;
    private Thread poller;
    private Consumer<HerdrState> onUpdate;

    void setOnUpdate(Consumer<HerdrState> callback) { this.onUpdate = callback; }

    void start() {
        if (running) return;
        running = true;
        poller = new Thread(this::pollLoop, "herdr-poller");
        poller.setDaemon(true);
        poller.start();
    }

    void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    private void pollLoop() {
        while (running) {
            try {
                var state = fetchState();
                if (onUpdate != null) Platform.runLater(() -> onUpdate.accept(state));
            } catch (Exception _) {
                if (onUpdate != null) Platform.runLater(() -> onUpdate.accept(HerdrState.DISCONNECTED));
            }
            try { Thread.sleep(3000); } catch (InterruptedException _) { break; }
        }
    }

    private HerdrState fetchState() {
        try (var herdr = Herdr.connect()) {
            var workspaces = herdr.api().workspace().list(new EmptyParams()).workspaces();
            var panes = herdr.api().pane().list(new PaneListParams(null)).panes();
            return new HerdrState(true, herdr.serverVersion(), workspaces, panes);
        } catch (Exception _) {
            return HerdrState.DISCONNECTED;
        }
    }
}
