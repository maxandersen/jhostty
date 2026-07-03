package dk.xam.themes.sync;

import dk.xam.themes.TerminalColorScheme;
import java.util.List;

/**
 * A source of terminal color themes that can be synced from upstream.
 */
public interface ThemeSource {
    /** Human-readable name of this source (e.g. "iTerm2-Color-Schemes"). */
    String name();

    /** URL for attribution. */
    String url();

    /**
     * Fetch and parse all themes from this source.
     * This method may download files from the network.
     */
    List<TerminalColorScheme> fetch() throws Exception;
}
