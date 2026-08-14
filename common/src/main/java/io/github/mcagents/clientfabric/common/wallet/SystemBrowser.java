package io.github.mcagents.clientfabric.common.wallet;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Opens a URL in whatever browser the player uses.
 *
 * <h2>Why not {@code java.awt.Desktop}</h2>
 *
 * <p>Touching AWT from inside Minecraft is a way to hang or crash the game: on macOS the
 * window system and AWT both want the first thread, and initialising AWT under a running
 * LWJGL client is exactly the collision that produces. Handing the URL to the operating
 * system's own opener costs three lines and avoids the whole subject — it is what Minecraft
 * itself does.
 *
 * <p>Failing to open a browser is never fatal. The screen shows the address and a copy
 * button beside it, which is also the only thing that helps the player whose default
 * browser is not the one MetaMask is installed in.
 */
public final class SystemBrowser {

    private static final Logger LOGGER = System.getLogger(SystemBrowser.class.getName());

    private SystemBrowser() {
        throw new AssertionError("No instances.");
    }

    /**
     * Asks the operating system to open {@code uri}.
     *
     * @return whether the request was handed over — not whether a browser actually appeared,
     *         which nothing here can know
     */
    public static boolean open(URI uri) {
        if (uri == null || !isSafe(uri)) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
        } else if (os.contains("mac")) {
            command = List.of("open", uri.toString());
        } else {
            command = List.of("xdg-open", uri.toString());
        }

        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.WARNING, "Could not open a browser; the player can copy the address instead", e);
            return false;
        }
    }

    /**
     * Whether this is a URL worth handing to a shell at all.
     *
     * <p>The only caller passes an address this mod built a moment earlier, so this is
     * belt and braces — but the argument does reach a process launcher, and a check that
     * costs nothing is cheaper than reasoning about every future caller.
     */
    static boolean isSafe(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return false;
        }
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }
}
