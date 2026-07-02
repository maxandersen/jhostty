///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//SOURCES ../../../../src/dk/xam/jhostty/ZmxSession.java

package dk.xam.jhostty;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.*;

class ZmxSessionTest {

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(ZmxSessionTest.class)).build();
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

    @Test void parseEmptyOutput() {
        assertEquals(0, ZmxSession.parseZmxList("").size());
        assertEquals(0, ZmxSession.parseZmxList(null).size());
    }

    @Test void parseSingleSession() {
        var output = "  name=dev\tpid=1234\tclients=1\tstart_dir=/home/user/project";
        var sessions = ZmxSession.parseZmxList(output);
        assertEquals(1, sessions.size());
        var s = sessions.getFirst();
        assertEquals("dev", s.name());
        assertEquals(1234, s.pid());
        assertEquals(1, s.clients());
        assertEquals("/home/user/project", s.startDir());
        assertFalse(s.ended());
    }

    @Test void parseEndedSession() {
        var output = "  name=old\tpid=5678\tclients=0\tended=12345\texit_code=1";
        var sessions = ZmxSession.parseZmxList(output);
        assertTrue(sessions.getFirst().ended());
        assertEquals(1, sessions.getFirst().exitCode());
    }

    @Test void parseArrowPrefix() {
        var output = "\u2192 name=current\tpid=9999\tclients=2\tstart_dir=/tmp";
        assertEquals("current", ZmxSession.parseZmxList(output).getFirst().name());
    }

    @Test void parseMultipleSessions() {
        var output = "  name=dev\tpid=1\tclients=1\tstart_dir=/a\n  name=test\tpid=2\tclients=0\tstart_dir=/b\n  name=prod\tpid=3\tclients=2\tstart_dir=/c";
        assertEquals(3, ZmxSession.parseZmxList(output).size());
    }

    @Test void isGeneratedName() {
        assertTrue(new ZmxSession("supa-24aecb1b-4a92-4d83-8b3a-4b9ebbcbdae2", 0, 0, "", "", "", false, 0).isGeneratedName());
        assertFalse(new ZmxSession("dev", 0, 0, "", "", "", false, 0).isGeneratedName());
    }

    @Test void friendlyNameForGeneratedName() {
        var home = System.getProperty("user.home");
        var s = new ZmxSession("supa-aaaa1111-2222-3333-4444-555566667777", 0, 0, home + "/code/myorg/myproject", "", "", false, 0);
        assertEquals("myorg/myproject", s.friendlyName());
    }

    @Test void friendlyNameForHumanName() {
        assertEquals("dev", new ZmxSession("dev", 0, 0, "/some/path", "", "", false, 0).friendlyName());
    }

    @Test void friendlyNameForHomedir() {
        var home = System.getProperty("user.home");
        assertEquals("~", new ZmxSession("supa-aaaa1111-2222-3333-4444-555566667777", 0, 0, home, "", "", false, 0).friendlyName());
    }

    @Test void displayLabelWithClients() {
        assertEquals("dev (2)", new ZmxSession("dev", 0, 2, "/tmp", "", "", false, 0).displayLabel());
    }

    @Test void displayLabelEnded() {
        assertTrue(new ZmxSession("dev", 0, 0, "/tmp", "", "", true, 0).displayLabel().contains("\u2718"));
    }

    @Test void displayLabelNoClients() {
        assertEquals("dev", new ZmxSession("dev", 0, 0, "/tmp", "", "", false, 0).displayLabel());
    }
}
