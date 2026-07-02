///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//SOURCES ../../../../src/dk/xam/jhostty/LayoutCodec.java

package dk.xam.jhostty;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class LayoutCodecTest {

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(LayoutCodecTest.class)).build();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        if (summary.getTestsFailedCount() > 0) {
            summary.getFailures().forEach(f -> { System.err.println(f.getTestIdentifier().getDisplayName()); f.getException().printStackTrace(); });
            System.exit(1);
        }
    }

    @Test void encodeDecodeRoundtrip() {
        var cmd = List.of("/opt/homebrew/bin/fish");
        assertEquals("/opt/homebrew/bin/fish", LayoutCodec.decodeCommand(LayoutCodec.encodeCommand(cmd)));
    }

    @Test void encodeDecodeSpecialChars() {
        var cmd = List.of("/bin/sh", "-c", "echo hello|world;done,[end]");
        var encoded = LayoutCodec.encodeCommand(cmd);
        assertFalse(encoded.contains("|"));
        assertFalse(encoded.contains(";"));
        assertFalse(encoded.contains("["));
        assertEquals("/bin/sh -c echo hello|world;done,[end]", LayoutCodec.decodeCommand(encoded));
    }

    @Test void encodeDecodeBackslash() {
        var cmd = List.of("C:\\Windows\\cmd.exe");
        assertEquals("C:\\Windows\\cmd.exe", LayoutCodec.decodeCommand(LayoutCodec.encodeCommand(cmd)));
    }

    @Test void parseCommandDefault() {
        var def = List.of("/bin/sh");
        assertEquals(def, LayoutCodec.parseCommand("", def));
    }

    @Test void parseCommandSimple() {
        assertEquals(List.of("/opt/homebrew/bin/fish"), LayoutCodec.parseCommand("/opt/homebrew/bin/fish", List.of("/bin/sh")));
    }

    @Test void splitTabDescsSimple() {
        assertEquals(List.of("1[fish]", "V2[a|b]"), LayoutCodec.splitTabDescs("1[fish],V2[a|b]"));
    }

    @Test void splitTabDescsWithBrackets() {
        assertEquals(List.of("1[a,b]", "1[c]"), LayoutCodec.splitTabDescs("1[a,b],1[c]"));
    }

    @Test void splitCommandsSimple() {
        assertEquals(List.of("fish", "bash"), LayoutCodec.splitCommands("fish|bash"));
    }

    @Test void splitCommandsSingle() {
        assertEquals(List.of("fish"), LayoutCodec.splitCommands("fish"));
    }
}
